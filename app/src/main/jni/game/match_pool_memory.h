#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

namespace peerlink_match_pool {

inline constexpr size_t capacity = 230 * 1024 * 1024;

struct BlockHeader {
    uint32_t flags;
    uint32_t previous;
    uint32_t span;
    uint32_t requested;
};
static_assert(sizeof(BlockHeader) == 16);

struct Pages {
    uintptr_t start = 0;
    size_t size = 0;
};

// Engine 11.1.1 allocates from the tail and retains the backing allocation
// after releasing suballocations. The caller holds the engine's pool lock.
inline bool freeTailPages(uintptr_t storage, size_t bytes, uintptr_t tail, size_t pageSize, Pages &pages) {
    constexpr uint32_t freeMarker = 0x62857f00;
    constexpr size_t headerSize = sizeof(BlockHeader);
    pages = {};
    if (!storage || bytes != capacity || storage > std::numeric_limits<uintptr_t>::max() - bytes ||
        !pageSize || (pageSize & (pageSize - 1)) || (storage | tail) % headerSize) return false;
    const uintptr_t sentinelAddress = storage + bytes - headerSize;
    if (tail < storage || tail > sentinelAddress - headerSize) return false;

    BlockHeader header{}, sentinel{};
    std::memcpy(&header, reinterpret_cast<const void *>(tail), sizeof(header));
    std::memcpy(&sentinel, reinterpret_cast<const void *>(sentinelAddress), sizeof(sentinel));
    if (header.flags != freeMarker || header.requested || header.span != sentinelAddress - tail ||
        header.previous > tail - storage || (header.previous & (headerSize - 1)) ||
        (tail == storage) != (header.previous == 0) ||
        sentinel.flags != freeMarker || sentinel.previous != header.span || sentinel.span ||
        sentinel.requested) return false;

    // Exclude both headers, any partial pages, and the system allocator's
    // metadata outside this backing allocation.
    const uintptr_t end = sentinelAddress & ~(pageSize - 1);
    const uintptr_t payload = tail + headerSize;
    const size_t prefix = (-payload) & (pageSize - 1);
    if (payload < end && prefix < end - payload) pages = {payload + prefix, end - payload - prefix};
    return true;
}

} // namespace peerlink_match_pool
