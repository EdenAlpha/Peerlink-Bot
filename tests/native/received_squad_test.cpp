#include "../../app/src/main/jni/game/received_squad.h"
#include <cassert>
#include <cstring>

template<class T> static void put(peerlink::TeamMetadata &team, size_t at, T value) {
    std::memcpy(team.data() + at, &value, sizeof(value));
}
static peerlink::TeamMetadata team(unsigned first, unsigned count) {
    peerlink::TeamMetadata value{};
    put<uint32_t>(value, 0, 104U << 14);
    put<uint32_t>(value, 0x1ac, count);
    put<uint64_t>(value, 0x150, 500);
    put<uint64_t>(value, 0x168, 5000000001ULL);
    std::memcpy(value.data() + 4, "Received team", 14);
    std::memcpy(value.data() + 0x4a, "RCV", 4);
    for (unsigned i = 0; i < 40; ++i) value[0x110 + i] = i < count ? i : 255;
    for (unsigned i = 0; i < count && i < 40; ++i) {
        put<uint64_t>(value, 0x1b0 + i * 0xc8 + 8, first + i);
        put<uint64_t>(value, 0x1b0 + i * 0xc8 + 0x58, 6000000000ULL + first + i);
        put<uint16_t>(value, 0x1b0 + i * 0xc8 + 0x48, i + 1);
    }
    return value;
}
int main() {
    PeerlinkSquadHeader own{};
    own.account = 60;
    own.count = 23;
    std::array<PeerlinkSquadPlayer, 40> players{};
    for (unsigned i = 0; i < own.count; ++i) {
        players[i].id = 100 + i;
        players[i].serial = 6000000100ULL + i;
    }
    peerlink::SquadReaders readers;
    readers.role = [](const void *, uint32_t slot) { return slot % 13; };
    readers.position = [](const void *, uint32_t slot) {
        return std::array<uint8_t, 2>{static_cast<uint8_t>(slot + 20), 25};
    };
    readers.default_formation = [](const void *) { return true; };
    readers.parameters = [](uint32_t id, uint64_t serial, uint32_t *values) {
        assert(id >= 200 && serial == 6000000000ULL + id);
        for (unsigned j = 0; j < 147; ++j) values[j] = (id - 200) * 150 + j;
        return true;
    };
    for (unsigned own_slot : {0U, 1U}) {
        std::array<peerlink::TeamMetadata, 2> teams;
        teams[own_slot] = team(100, 23);
        teams[1 - own_slot] = team(200, 40);
        const auto before = teams;
        const auto capture = peerlink::captureReceivedSquad(teams, own, players.data(), 70, readers);
        assert(teams == before);
        assert(capture.envelope.status == peerlink::SquadCaptureStatus::Ready);
        assert(capture.envelope.team_slot == 1 - own_slot && capture.envelope.count == 40);
        assert(capture.squad.account == 70 && capture.squad.count == 40);
        assert(capture.squad.magic == 0x31515350 && capture.squad.coach_serial == 5000000001ULL);
        assert(capture.players[0].parameters[0] == 0);
        assert(capture.players[39].id == 239 && capture.players[39].parameters[146] == 39 * 150 + 146);
        assert(capture.bytes().size() == 48 + 864 + 40 * 608 + 0x2100);
    }
    std::array<peerlink::TeamMetadata, 2> teams{team(100, 23), team(200, 23)};
    readers.parameters = [](uint32_t id, uint64_t, uint32_t *values) {
        if (id == 202) return false;
        for (unsigned i = 0; i < 147; ++i) if (id != 201 || i != 45) values[i] = i;
        return true;
    };
    const auto partial = peerlink::captureReceivedSquad(teams, own, players.data(), 70, readers);
    assert(partial.envelope.status == peerlink::SquadCaptureStatus::AttributesIncomplete);
    assert(partial.envelope.missing_records == 1 && partial.envelope.missing_parameters == 148);
    assert(partial.players[1].parameters[45] == UINT32_MAX);
    assert(partial.players[1].parameters[0] == 0);
    teams[1] = teams[0];
    assert(peerlink::captureReceivedSquad(teams, own, players.data(), 70, readers).envelope.status ==
           peerlink::SquadCaptureStatus::PeerAmbiguous);
    teams[1] = team(200, 41);
    assert(peerlink::captureReceivedSquad(teams, own, players.data(), 70, readers).envelope.status ==
           peerlink::SquadCaptureStatus::InvalidMetadata);
}
