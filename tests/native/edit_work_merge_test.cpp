#include "../../app/src/main/jni/game/edit_work_merge.h"
#include <cassert>
#include <cstdint>
#include <vector>

using namespace peerlink;

static void append(std::vector<uint8_t> &out, uint64_t key, uint8_t value) {
    size_t pos = out.size(); out.resize(pos + kEditRecordSize, value);
    for (unsigned i = 0; i < 8; ++i) out[pos+i] = uint8_t(key >> (i*8));
}

int main() {
    std::vector<uint8_t> current, supplement, out;
    append(current, 1, 0x11); append(current, 4, 0x44);
    append(supplement, 2, 0x22); append(supplement, 4, 0xff); append(supplement, 9, 0x99);
    auto result = mergeEditRecords(current.data(), current.size(), supplement.data(), supplement.size(), out);
    assert(result.ok && result.added == 2 && out.size() == 4*kEditRecordSize);
    assert(editRecordKey(out.data()) == 1 && editRecordKey(out.data()+kEditRecordSize) == 2);
    assert(editRecordKey(out.data()+2*kEditRecordSize) == 4 && out[2*kEditRecordSize+8] == 0x44);
    assert(editRecordKey(out.data()+3*kEditRecordSize) == 9);
    const auto saved = out;
    assert(!mergeEditRecords(current.data(), current.size()-1, supplement.data(), supplement.size(), out).ok);
    assert(out == saved);
    std::vector<uint8_t> unsorted; append(unsorted, 9, 0); append(unsorted, 3, 0);
    assert(!mergeEditRecords(unsorted.data(), unsorted.size(), nullptr, 0, out).ok && out == saved);
    std::vector<uint8_t> duplicates; append(duplicates, 2, 0); append(duplicates, 2, 0);
    assert(!mergeEditRecords(duplicates.data(), duplicates.size(), nullptr, 0, out).ok && out == saved);
    std::vector<uint8_t> full; full.reserve(kEditRecordCapacity*kEditRecordSize);
    for (size_t i = 0; i < kEditRecordCapacity; ++i) append(full, i*2, 0);
    std::vector<uint8_t> extra; append(extra, 1, 0);
    assert(!mergeEditRecords(full.data(), full.size(), extra.data(), extra.size(), out).ok && out == saved);
}
