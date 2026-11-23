#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <new>
#include <sys/mman.h>
#include <type_traits>

struct UdpTraceData {
    uint64_t ns;
    uint64_t sender_t0_ns;
    uint64_t sender_s1_ns;
    uint64_t sender_send_attempt_ns;
    uint64_t kernel_rx_rt_ns;
    uint64_t tunnel_seq;
    uint32_t flow_hash;
    uint32_t sender_id;
    uint32_t kernel_to_user_us;
    uint32_t tx_user_to_sched_us;
    uint32_t tx_user_to_soft_us;
    uint32_t tx_sched_to_soft_us;
    uint16_t length;
    uint16_t source_port;
    uint16_t dest_port;
    uint8_t stage;
    uint8_t ip_version;
    uint16_t flags;
};

struct UdpTraceEvent : UdpTraceData {
    uint64_t commit_id;
    uint8_t busy;
};

static_assert(std::is_trivial<UdpTraceEvent>::value);
static_assert(__atomic_always_lock_free(sizeof(uint64_t), nullptr));

// A diagnostic may be omitted under contention. A packet must never wait for
// the exporter, another producer, or a late kernel timestamp to release a slot.
struct TryTraceSlotGuard {
    UdpTraceEvent &slot;
    const bool acquired;
    explicit TryTraceSlotGuard(UdpTraceEvent &event)
        : slot(event), acquired(__atomic_exchange_n(&event.busy, 1, __ATOMIC_ACQUIRE) == 0) {}
    ~TryTraceSlotGuard() { if (acquired) __atomic_store_n(&slot.busy, 0, __ATOMIC_RELEASE); }
};

struct UdpTraceStorageDeleter {
    size_t bytes = 0;
    void operator()(UdpTraceEvent *slots) const { munmap(slots, bytes); }
};
using UdpTraceStorage = std::unique_ptr<UdpTraceEvent[], UdpTraceStorageDeleter>;

inline UdpTraceStorage reserve_udp_trace_storage(size_t capacity) {
    if (!capacity || capacity > SIZE_MAX / sizeof(UdpTraceEvent)) return {};
    const size_t bytes = capacity * sizeof(UdpTraceEvent);
    void *storage = mmap(nullptr, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (storage == MAP_FAILED) return {};
    // Trivial default construction starts the objects' lifetimes without
    // writing to their demand-zero backing. Producers initialize every
    // published field, including the unavailable timestamp sentinels.
    return UdpTraceStorage(new (storage) UdpTraceEvent[capacity], UdpTraceStorageDeleter{bytes});
}
