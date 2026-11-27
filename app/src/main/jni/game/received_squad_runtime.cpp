#include "received_squad.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <utility>

namespace peerlink {
namespace {
class ReadableMemory {
    std::vector<std::pair<uintptr_t, uintptr_t>> ranges;
public:
    ReadableMemory() {
        FILE *maps = std::fopen("/proc/self/maps", "r");
        if (!maps) return;
        char *line = nullptr;
        size_t length = 0;
        while (getline(&line, &length, maps) != -1) {
            unsigned long begin = 0, end = 0;
            char permissions[5]{};
            if (std::sscanf(line, "%lx-%lx %4s", &begin, &end, permissions) == 3 &&
                permissions[0] == 'r' && end > begin)
                ranges.emplace_back(begin, end);
        }
        free(line);
        std::fclose(maps);
    }
    bool contains(uintptr_t address, size_t size) const {
        if (!address || size > UINTPTR_MAX - address) return false;
        uintptr_t cursor = address;
        for (const auto &range : ranges) {
            if (range.second <= cursor) continue;
            if (range.first > cursor) return false;
            if (range.second >= address + size) return true;
            cursor = range.second;
        }
        return false;
    }
    template<class T> bool read(uintptr_t address, T &value) const {
        if (!contains(address, sizeof(T))) return false;
        std::memcpy(&value, reinterpret_cast<const void *>(address), sizeof(T));
        return true;
    }
};
}

// The caller holds the original task lock. Only getters and copies run here.
std::vector<uint8_t> observeReceivedSquad(uintptr_t base, uint32_t self, uint32_t peer,
                                         const uint8_t *input, size_t size) {
    ReceivedSquad unavailable;
    unavailable.envelope.account = peer;
    if (!input || size < sizeof(PeerlinkSquadHeader)) return unavailable.bytes();
    PeerlinkSquadHeader own{};
    std::memcpy(&own, input, sizeof(own));
    if (own.magic != 0x31515350 || own.version != 1 || own.account != self ||
        own.count < 11 || own.count > 40 || size != sizeof(own) + own.count * sizeof(PeerlinkSquadPlayer))
        return unavailable.bytes();
    std::array<PeerlinkSquadPlayer, 40> own_players{};
    std::memcpy(own_players.data(), input + sizeof(own), own.count * sizeof(own_players[0]));
    const ReadableMemory memory;
    using Get = uintptr_t (*)(void);
    const uintptr_t owner = reinterpret_cast<Get>(base + 0x82468d4)();
    uintptr_t work = 0;
    std::array<TeamMetadata, 2> teams{};
    unavailable.envelope.status = SquadCaptureStatus::MemoryUnavailable;
    if (!owner || !memory.read(owner + 0x40, work) || !work ||
        !memory.read(work + 0x318e790, teams)) return unavailable.bytes();

    const uintptr_t root = reinterpret_cast<Get>(base + 0x7a87db8)();
    uintptr_t data = 0, cache = 0;
    if (root && memory.read(root + 0x28, data) && memory.contains(data, sizeof(uintptr_t)))
        cache = reinterpret_cast<uintptr_t (*)(uintptr_t)>(base + 0x7a729d8)(data);
    if (!memory.contains(cache, 0x338)) cache = 0;
    std::array<uint8_t, 0x60> defaults{};
    reinterpret_cast<void (*)(void *)>(base + 0x697b6b8)(defaults.data());
    SquadReaders readers;
    readers.role = [base](const void *formation, uint32_t slot) {
        return reinterpret_cast<uint32_t (*)(const void *, uint32_t)>(base + 0x82b5f10)(formation, slot);
    };
    readers.position = [base](const void *formation, uint32_t slot) {
        const auto *position = reinterpret_cast<const uint8_t *(*)(const void *, uint32_t)>(
            base + 0x82b5f30)(formation, slot);
        return std::array<uint8_t, 2>{position[0], position[1]};
    };
    readers.default_formation = [&defaults](const void *formation) {
        const auto *options = static_cast<const uint8_t *>(formation);
        // The last byte of each aligned group is padding in the pinned layout.
        return std::memcmp(options + 0x44, defaults.data() + 0x44, 0x13) == 0 &&
            std::memcmp(options + 0x58, defaults.data() + 0x58, 7) == 0;
    };
    readers.parameters = [base, cache, &memory](uint32_t id, uint64_t serial, uint32_t *parameters) {
        if (!cache) return false;
        struct Key { uint32_t id, padding; uint64_t serial, extra; };
        struct Identity { uint32_t id, padding; uint64_t serial; };
        const Key key{id, 0, serial, 0};
        const uintptr_t record = reinterpret_cast<uintptr_t (*)(uintptr_t, const Key *)>(
            base + 0x7a7be44)(cache, &key);
        if (record == cache + 0x330 || !memory.contains(record, 0x1d0)) return false;
        const Identity identity = reinterpret_cast<Identity (*)(const void *)>(base + 0x68fe720)(
            reinterpret_cast<const void *>(record));
        if (identity.id != id || identity.serial != serial) return false;
        const auto get = reinterpret_cast<uint32_t (*)(const void *, uint32_t, uint32_t *)>(base + 0x82a84d4);
        for (unsigned i = 0; i < 147; ++i) {
            uint32_t value = 0;
            if (get(reinterpret_cast<const void *>(record), i, &value)) parameters[i] = value;
        }
        return true;
    };
    return captureReceivedSquad(teams, own, own_players.data(), peer, readers).bytes();
}
}
