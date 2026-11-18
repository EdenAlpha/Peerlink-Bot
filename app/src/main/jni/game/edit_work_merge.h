#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace peerlink {

// 11.1.1 EditWork stores a sorted array of fixed-size player edit records.
// A record starts with its little-endian 64-bit player key.  The engine's
// lookup at 0x82449f4 searches this array and accepts at most 0x12ed3 records.
constexpr size_t kEditRecordSize = 0x50;
constexpr size_t kEditRecordCapacity = 0x12ed3;

struct EditMergeResult {
    bool ok = false;
    size_t added = 0;
};

inline uint64_t editRecordKey(const uint8_t *record) {
    uint64_t key = 0;
    for (unsigned i = 0; i < 8; ++i) key |= uint64_t(record[i]) << (i * 8);
    return key;
}

inline bool validEditRecords(const uint8_t *data, size_t size) {
    if ((!data && size) || size % kEditRecordSize || size / kEditRecordSize > kEditRecordCapacity)
        return false;
    uint64_t previous = 0;
    for (size_t pos = 0; pos < size; pos += kEditRecordSize) {
        const uint64_t key = editRecordKey(data + pos);
        if (pos && key <= previous) return false;
        previous = key;
    }
    return true;
}

// Build a separate result before modifying engine memory. Existing records are
// authoritative; the supplement only fills keys the game has not loaded.
inline EditMergeResult mergeEditRecords(const uint8_t *current, size_t currentSize,
                                        const uint8_t *supplement, size_t supplementSize,
                                        std::vector<uint8_t> &output) {
    if (!validEditRecords(current, currentSize) || !validEditRecords(supplement, supplementSize))
        return {};
    const size_t currentCount = currentSize / kEditRecordSize;
    const size_t extraCount = supplementSize / kEditRecordSize;
    std::vector<uint8_t> merged;
    merged.reserve(std::min(currentCount + extraCount, kEditRecordCapacity) * kEditRecordSize);
    size_t a = 0, b = 0, added = 0;
    while (a < currentCount || b < extraCount) {
        if (merged.size() / kEditRecordSize == kEditRecordCapacity) return {};
        const uint8_t *record;
        if (a == currentCount) { record = supplement + b++ * kEditRecordSize; ++added; }
        else if (b == extraCount) { record = current + a++ * kEditRecordSize; }
        else {
            const uint8_t *left = current + a * kEditRecordSize;
            const uint8_t *right = supplement + b * kEditRecordSize;
            const uint64_t leftKey = editRecordKey(left), rightKey = editRecordKey(right);
            if (leftKey <= rightKey) { record = left; ++a; if (leftKey == rightKey) ++b; }
            else { record = right; ++b; ++added; }
        }
        merged.insert(merged.end(), record, record + kEditRecordSize);
    }
    output.swap(merged);
    return {true, added};
}

} // namespace peerlink
