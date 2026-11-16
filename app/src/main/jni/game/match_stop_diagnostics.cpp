#include "match_stop_diagnostics.h"
#include "match_state_diagnostics.h"
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {
// Pinned libUE4 11.1.1: MatchOnlineCommandController vtable at 98aedd0.
// 70165d8 requests stop (+45); 7016670 constructs outgoing commands;
// 70167e8 applies the synchronized frame. 701794c commits stop (+49),
// called by 7017560 locally or 7018738 when applying command 0x50.
using Request = void (*)(uintptr_t);
using Outgoing = void (*)(uintptr_t, uintptr_t);
using Apply = uint32_t (*)(uintptr_t, uintptr_t);
constexpr uintptr_t kTable = 0x98aedd0;
constexpr uintptr_t kOffsets[] = {0x60, 0x98, 0xa8};
constexpr uintptr_t kFunctions[] = {0x70165d8, 0x7016670, 0x70167e8};
std::atomic<int> installed{0};
std::atomic<uintptr_t> image_base{0};
Request original_request = nullptr;
Outgoing original_outgoing = nullptr;
Apply original_apply = nullptr;
std::atomic<unsigned> requests{0}, local_stops{0}, input_stops{0};
struct Event {
    // Publish the complete first event, never a partially populated snapshot.
    std::atomic<int> state{0};
    uint64_t ms = 0;
    uintptr_t caller = 0;
    uint32_t frame = 0, missing = 0, ahead = 0;
};
Event first_request, first_local_stop, first_input_stop;

uint64_t now_ms() {
    timespec value{};
    clock_gettime(CLOCK_MONOTONIC, &value);
    return uint64_t(value.tv_sec) * 1000 + value.tv_nsec / 1000000;
}

unsigned byte(uintptr_t controller, size_t offset) {
    return *reinterpret_cast<const unsigned char *>(controller + offset);
}

uint32_t word(uintptr_t controller, size_t offset) {
    return *reinterpret_cast<const uint32_t *>(controller + offset);
}

void record(Event &event, const char *kind, uintptr_t controller, uintptr_t caller) {
    int expected = 0;
    if (!event.state.compare_exchange_strong(expected, 1, std::memory_order_acq_rel)) return;
    event.ms = now_ms();
    const uintptr_t base = image_base.load(std::memory_order_relaxed);
    // Only offsets within this verified image, never ASLR addresses or inputs.
    event.caller = caller >= base && caller - base < 0xb000000 ? caller - base : 0;
    event.frame = word(controller, 0x30);
    event.missing = word(controller, 0x28);
    event.ahead = word(controller, 0x2c);
    event.state.store(2, std::memory_order_release);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_WARN, "PeerlinkEngineHost",
        "MATCH_STOP_EVENT path=%s atMs=%llu callerOffset=0x%llx frame=%u missingMask=%u aheadMask=%u",
        kind, static_cast<unsigned long long>(event.ms),
        static_cast<unsigned long long>(event.caller), event.frame, event.missing, event.ahead);
#else
    (void)kind;
#endif
}

extern "C" __attribute__((used, noinline)) void peerlink_match_observe_request_impl(
        uintptr_t controller, uintptr_t checker, uintptr_t context, uintptr_t caller) {
    const int before_errno = errno;
    const bool pending = byte(controller, 0x45) != 0;
    errno = before_errno;
    original_request(controller);
    const int after_errno = errno;
    if (!pending && byte(controller, 0x45)) {
        requests.fetch_add(1, std::memory_order_relaxed);
        record(first_request, "local_request", controller, caller);
        peerlink_match_check_request(controller, checker, context, caller);
    }
    errno = after_errno;
}

#if defined(__aarch64__)
// At the verified 70539e0 call, x19 is the check object and x20 is its
// comparison context. Capture them before any C++ prologue can reuse them.
// The implementation preserves callee-saved registers and the original LR.
__attribute__((naked)) void observe_request(uintptr_t) {
    __asm__ volatile("mov x1, x19\nmov x2, x20\nmov x3, x30\nb peerlink_match_observe_request_impl");
}
#else
__attribute__((noinline)) void observe_request(uintptr_t controller) {
    peerlink_match_observe_request_impl(controller, 0, 0,
        reinterpret_cast<uintptr_t>(__builtin_return_address(0)));
}
#endif

__attribute__((noinline)) void observe_outgoing(uintptr_t controller, uintptr_t frame) {
    const int before_errno = errno;
    const bool stopped = byte(controller, 0x49) != 0;
    const uintptr_t caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    errno = before_errno;
    original_outgoing(controller, frame);
    const int after_errno = errno;
    if (!stopped && byte(controller, 0x49)) {
        local_stops.fetch_add(1, std::memory_order_relaxed);
        record(first_local_stop, "outgoing_command", controller, caller);
    }
    errno = after_errno;
}

__attribute__((noinline)) uint32_t observe_apply(uintptr_t controller, uintptr_t frame) {
    const int before_errno = errno;
    const bool stopped = byte(controller, 0x49) != 0;
    const uintptr_t caller = reinterpret_cast<uintptr_t>(__builtin_return_address(0));
    errno = before_errno;
    const uint32_t result = original_apply(controller, frame);
    const int after_errno = errno;
    if (!stopped && byte(controller, 0x49)) {
        input_stops.fetch_add(1, std::memory_order_relaxed);
        record(first_input_stop, "applied_input_command", controller, caller);
    }
    errno = after_errno;
    return result;
}

bool protect(uintptr_t address, int flags) {
    const long size = sysconf(_SC_PAGESIZE);
    if (size < 4096 || size > 65536 || (size & (size - 1))) return false;
    return mprotect(reinterpret_cast<void *>(address & ~(uintptr_t(size) - 1)),
                    size_t(size), flags) == 0;
}

void event_text(const Event &event, const char *prefix, char *text, size_t size) {
    if (event.state.load(std::memory_order_acquire) != 2) {
        snprintf(text, size, " %sAtMs=0", prefix);
        return;
    }
    snprintf(text, size, " %sAtMs=%llu %sCaller=0x%llx %sFrame=%u %sMissing=%u %sAhead=%u",
        prefix, static_cast<unsigned long long>(event.ms), prefix,
        static_cast<unsigned long long>(event.caller), prefix, event.frame,
        prefix, event.missing, prefix, event.ahead);
}
}

void peerlink_observe_match_stop(uintptr_t base) {
    if (!base || installed.load(std::memory_order_acquire)) return;
    for (size_t i = 0; i < 3; ++i)
        if (__atomic_load_n(reinterpret_cast<uintptr_t *>(base + kTable + kOffsets[i]), __ATOMIC_ACQUIRE)
                != base + kFunctions[i]) {
            installed.store(-1, std::memory_order_release);
            return;
        }
    // These three slots share one page on every supported Android page size.
    const uintptr_t page = base + kTable + kOffsets[0];
    if (!protect(page, PROT_READ | PROT_WRITE)) {
        installed.store(-1, std::memory_order_release);
        return;
    }
    image_base.store(base, std::memory_order_relaxed);
    peerlink_match_state_begin(base);
    original_request = reinterpret_cast<Request>(base + kFunctions[0]);
    original_outgoing = reinterpret_cast<Outgoing>(base + kFunctions[1]);
    original_apply = reinterpret_cast<Apply>(base + kFunctions[2]);
    const uintptr_t observers[] = {reinterpret_cast<uintptr_t>(&observe_request),
        reinterpret_cast<uintptr_t>(&observe_outgoing), reinterpret_cast<uintptr_t>(&observe_apply)};
    for (size_t i = 0; i < 3; ++i)
        __atomic_store_n(reinterpret_cast<uintptr_t *>(base + kTable + kOffsets[i]), observers[i], __ATOMIC_RELEASE);
    if (!protect(page, PROT_READ)) {
        for (size_t i = 0; i < 3; ++i)
            __atomic_store_n(reinterpret_cast<uintptr_t *>(base + kTable + kOffsets[i]), base + kFunctions[i], __ATOMIC_RELEASE);
        protect(page, PROT_READ);
        installed.store(-1, std::memory_order_release);
        return;
    }
    installed.store(1, std::memory_order_release);
}

void peerlink_match_stop_diagnostics(uintptr_t controller, char *text, size_t size) {
    char request[240], local[240], input[240];
    event_text(first_request, "stopRequest", request, sizeof(request));
    event_text(first_local_stop, "stopOutgoing", local, sizeof(local));
    event_text(first_input_stop, "stopInput", input, sizeof(input));
    const uintptr_t base = image_base.load(std::memory_order_relaxed);
    const uintptr_t table = controller ? *reinterpret_cast<const uintptr_t *>(controller) : 0;
    const bool valid = controller && base && table == base + kTable;
    const uintptr_t table_offset = base && table >= base && table - base < 0xb000000 ? table - base : 0;
    snprintf(text, size,
        "stopObserver=%d stopRequests=%u stopOutgoing=%u stopInput=%u "
        "commandControllerValid=%u commandControllerVtable=0x%llx "
        "commandFrame=%u commandMissingMask=%u commandAheadMask=%u commandStopPending=%u commandStopped=%u%s%s%s",
        installed.load(std::memory_order_acquire), requests.load(std::memory_order_relaxed),
        local_stops.load(std::memory_order_relaxed), input_stops.load(std::memory_order_relaxed),
        unsigned(valid), static_cast<unsigned long long>(table_offset),
        valid ? word(controller, 0x30) : 0, valid ? word(controller, 0x28) : 0,
        valid ? word(controller, 0x2c) : 0, valid ? byte(controller, 0x45) : 0,
        valid ? byte(controller, 0x49) : 0, request, local, input);
}
