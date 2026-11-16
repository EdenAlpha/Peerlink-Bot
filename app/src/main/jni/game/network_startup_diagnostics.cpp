#include "network_startup_diagnostics.h"
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <string>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

namespace {
// 11.1.1: 7dc25a0 -> 7dc9c84 -> platform vtable+d0 -> 7dfc590.
// The final function calls getaddrinfo and returns an NTL result. Neither a
// pending DNS call nor its failure is exposed by nativeTransportIoReady().
using Resolve = uint32_t (*)(uintptr_t, uint32_t, int, void *, size_t *, const char *);
constexpr uintptr_t kResolveSlot = 0x9931a30;
constexpr uintptr_t kResolveFunction = 0x7dfc590;
constexpr uint32_t kSuccess = 0x0e000001;
std::atomic<Resolve> original_resolve{nullptr};
std::atomic<int> observer_state{0}; // 0 unavailable, 1 installed, -1 unsupported.
struct Resolution {
    std::atomic<unsigned> calls{0}, active{0};
    std::atomic<uint32_t> result{0};
    std::atomic<uint64_t> started_ms{0}, duration_ms{0};
};
Resolution ipv4, ipv6;

uint64_t now_ms() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return uint64_t(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

uint32_t observe_resolution(uintptr_t platform, uint32_t family, int kind,
                            void *output, size_t *length, const char *hostname) {
    const int incoming_errno = errno;
    Resolve resolve = original_resolve.load(std::memory_order_acquire);
    Resolution *record = family == 1 ? &ipv4 : (family == 2 ? &ipv6 : nullptr);
    uint64_t started = 0;
    if (record) {
        started = now_ms();
        record->started_ms.store(started, std::memory_order_relaxed);
        record->calls.fetch_add(1, std::memory_order_relaxed);
        record->active.fetch_add(1, std::memory_order_release);
    }
    errno = incoming_errno;
    // Forward every argument unchanged, including the original output buffer.
    // Never substitute an address, a result, or a socket/connection flag.
    const uint32_t result = resolve(platform, family, kind, output, length, hostname);
    const int outgoing_errno = errno;
    if (record) {
        const uint64_t elapsed = now_ms() - started;
        record->duration_ms.store(elapsed, std::memory_order_relaxed);
        record->result.store(result, std::memory_order_relaxed);
        record->active.fetch_sub(1, std::memory_order_release);
        __android_log_print(result == kSuccess ? ANDROID_LOG_INFO : ANDROID_LOG_WARN,
            "PeerlinkEngineHost", "NETWORK_ADDRESS_RESOLUTION family=%u result=0x%08x durationMs=%llu",
            family, result, static_cast<unsigned long long>(elapsed));
    }
    errno = outgoing_errno;
    return result;
}

bool protect(uintptr_t address, int flags) {
    const long size = sysconf(_SC_PAGESIZE);
    if (size < 4096 || size > 65536 || (size & (size - 1))) return false;
    return mprotect(reinterpret_cast<void *>(address & ~(uintptr_t(size) - 1)),
                    size_t(size), flags) == 0;
}

void resolution_text(const Resolution &record, const char *family, char *text, size_t size) {
    const unsigned active = record.active.load(std::memory_order_acquire);
    const unsigned calls = record.calls.load(std::memory_order_relaxed);
    const uint32_t result = record.result.load(std::memory_order_relaxed);
    const uint64_t started = record.started_ms.load(std::memory_order_relaxed);
    const uint64_t elapsed = active && started ? now_ms() - started :
        record.duration_ms.load(std::memory_order_relaxed);
    const char *state = active ? "pending" : (!calls ? "not_called" :
        (result == kSuccess ? "resolved" : "failed"));
    snprintf(text, size, " dns%s=%s dns%sResult=0x%08x dns%sMs=%llu dns%sCalls=%u",
             family, state, family, result, family,
             static_cast<unsigned long long>(elapsed), family, calls);
}
}

void peerlink_observe_network_startup(uintptr_t base) {
    if (!base || observer_state.load(std::memory_order_acquire)) return;
    auto *slot = reinterpret_cast<uintptr_t *>(base + kResolveSlot);
    const uintptr_t expected = base + kResolveFunction;
    if (__atomic_load_n(slot, __ATOMIC_ACQUIRE) != expected ||
        !protect(reinterpret_cast<uintptr_t>(slot), PROT_READ | PROT_WRITE)) {
        observer_state.store(-1, std::memory_order_release);
        __android_log_print(ANDROID_LOG_WARN, "PeerlinkEngineHost",
                            "NETWORK_STARTUP_OBSERVER unavailable; original startup retained");
        return;
    }
    original_resolve.store(reinterpret_cast<Resolve>(expected), std::memory_order_release);
    __atomic_store_n(slot, reinterpret_cast<uintptr_t>(&observe_resolution), __ATOMIC_RELEASE);
    if (!protect(reinterpret_cast<uintptr_t>(slot), PROT_READ)) {
        __atomic_store_n(slot, expected, __ATOMIC_RELEASE);
        protect(reinterpret_cast<uintptr_t>(slot), PROT_READ);
        observer_state.store(-1, std::memory_order_release);
        return;
    }
    observer_state.store(1, std::memory_order_release);
}

void peerlink_network_startup_diagnostics(uintptr_t client, char *text, size_t size) {
    if (!client) {
        snprintf(text, size, "networkStartup=not_started");
        return;
    }
    // Passive scalar reads only. Do not call the SDK's active state/check
    // methods, take its locks, or dereference network objects being replaced.
    auto word = [client](uintptr_t offset) {
        return __atomic_load_n(reinterpret_cast<const uint32_t *>(client + offset), __ATOMIC_RELAXED);
    };
    auto byte = [client](uintptr_t offset) {
        return __atomic_load_n(reinterpret_cast<const uint8_t *>(client + offset), __ATOMIC_RELAXED);
    };
    auto present = [client](uintptr_t offset) {
        return __atomic_load_n(reinterpret_cast<const uintptr_t *>(client + offset), __ATOMIC_RELAXED) != 0;
    };
    char v4[160], v6[160], socket_details[768];
    resolution_text(ipv4, "V4", v4, sizeof(v4));
    resolution_text(ipv6, "V6", v6, sizeof(v6));
    peerlink_network_socket_diagnostics(socket_details, sizeof(socket_details));
    snprintf(text, size,
        "networkSdkPhase=%u networkSdkError=%u networkThreadStop=%u networkAutoPort=%u "
        "networkWifi=%u networkEthernet=%u networkV4Holder=%u networkV6Holder=%u "
        "networkResolverObserver=%d%s%s%s",
        word(0x89c), word(0x8b0), byte(0x8b8), byte(0x8b9), byte(0xb70), byte(0xe28),
        present(0x8f8), present(0x900), observer_state.load(std::memory_order_acquire), v4, v6, socket_details);
}
