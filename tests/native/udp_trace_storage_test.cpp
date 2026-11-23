#include "udp_trace_storage.h"

#include <algorithm>
#include <cassert>
#include <cstdio>
#include <thread>
#include <unistd.h>
#include <vector>

int main() {
    constexpr size_t capacity = 262144;
    auto trace = reserve_udp_trace_storage(capacity);
    assert(trace);
    const size_t page_size = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    const size_t bytes = capacity * sizeof(UdpTraceEvent);
    std::vector<unsigned char> mapped((bytes + page_size - 1) / page_size);
    auto resident = [&] {
        assert(mincore(trace.get(), bytes, mapped.data()) == 0);
        return std::count_if(mapped.begin(), mapped.end(), [](unsigned char page) { return page & 1; });
    };
    assert(resident() == 0);
    auto &slot = trace[0];
    {
        TryTraceSlotGuard producer(slot);
        assert(producer.acquired);
        assert(__atomic_load_n(&slot.commit_id, __ATOMIC_RELAXED) == 0);
        std::thread competing([&] {
            TryTraceSlotGuard reader(slot);
            assert(!reader.acquired);
        });
        competing.join();
        slot.ns = 32487;
        slot.tx_user_to_soft_us = 0xffffffffu;
        __atomic_store_n(&slot.commit_id, 1, __ATOMIC_RELEASE);
    }
    {
        TryTraceSlotGuard reader(slot);
        assert(reader.acquired);
        assert(__atomic_load_n(&slot.commit_id, __ATOMIC_ACQUIRE) == 1);
        assert(slot.ns == 32487 && slot.tx_user_to_soft_us == 0xffffffffu);
    }
    {
        TryTraceSlotGuard last(trace[capacity - 1]);
        assert(last.acquired);
        assert(__atomic_load_n(&trace[capacity - 1].commit_id, __ATOMIC_RELAXED) == 0);
    }
    assert(resident() <= 2);
    std::printf("UDP trace retains %zu slots and reserves %zu bytes without eager residency\n", capacity, bytes);
}
