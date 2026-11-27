#include "received_squad.h"
#include <algorithm>
#include <cstring>

namespace peerlink {
namespace {
template<class T> T field(const uint8_t *data, size_t offset) {
    T value;
    std::memcpy(&value, data + offset, sizeof(value));
    return value;
}
bool sameRoster(const TeamMetadata &team, const PeerlinkSquadHeader &own,
                const PeerlinkSquadPlayer *players) {
    if (field<uint32_t>(team.data(), 0x1ac) != own.count) return false;
    for (uint32_t i = 0; i < own.count; ++i) {
        const auto *member = team.data() + 0x1b0 + i * 0xc8;
        const auto id = field<uint64_t>(member, 8);
        const auto serial = field<uint64_t>(member, 0x58);
        if (std::none_of(players, players + own.count, [&](const auto &p) {
                return p.id == id && p.serial == serial;
            })) return false;
        for (uint32_t j = 0; j < i; ++j) {
            const auto *previous = team.data() + 0x1b0 + j * 0xc8;
            if (field<uint64_t>(previous, 8) == id && field<uint64_t>(previous, 0x58) == serial)
                return false;
        }
    }
    return true;
}
}

std::vector<uint8_t> ReceivedSquad::bytes() const {
    const size_t squad_bytes = envelope.count ? sizeof(squad) + envelope.count * sizeof(players[0]) : 0;
    std::vector<uint8_t> output(sizeof(envelope) + squad_bytes + (squad_bytes ? metadata.size() : 0));
    std::memcpy(output.data(), &envelope, sizeof(envelope));
    if (squad_bytes) {
        auto *destination = output.data() + sizeof(envelope);
        std::memcpy(destination, &squad, sizeof(squad));
        std::memcpy(destination + sizeof(squad), players.data(), envelope.count * sizeof(players[0]));
        std::memcpy(destination + squad_bytes, metadata.data(), metadata.size());
    }
    return output;
}

ReceivedSquad captureReceivedSquad(const std::array<TeamMetadata, 2> &teams,
    const PeerlinkSquadHeader &own, const PeerlinkSquadPlayer *own_players,
    uint32_t peer, const SquadReaders &readers) {
    ReceivedSquad result;
    result.envelope.account = peer;
    if (!peer || !own.account || peer == own.account || !own_players || own.count < 11 || own.count > 40)
        return result;
    const bool first_own = sameRoster(teams[0], own, own_players);
    const bool second_own = sameRoster(teams[1], own, own_players);
    if (first_own == second_own) {
        result.envelope.status = SquadCaptureStatus::PeerAmbiguous;
        return result;
    }
    const unsigned slot = first_own ? 1 : 0;
    result.metadata = teams[slot];
    const auto *team = result.metadata.data();
    const auto count = field<uint32_t>(team, 0x1ac);
    if (count < 11 || count > 40) {
        result.envelope.status = SquadCaptureStatus::InvalidMetadata;
        return result;
    }
    result.envelope.count = count;
    result.envelope.team_slot = slot;
    auto &header = result.squad;
    header.magic = 0x31515350;
    header.version = 1;
    header.account = peer;
    header.base_team = field<uint32_t>(team, 0) >> 14;
    header.count = count;
    const auto coach = field<uint64_t>(team, 0x150);
    header.coach_id = coach <= UINT32_MAX ? static_cast<uint32_t>(coach) : 0;
    header.coach_serial = field<uint64_t>(team, 0x168);
    std::memcpy(header.name, team + 4, 70);
    std::memcpy(header.short_name, team + 0x4a, 4);
    for (unsigned i = 0; i < 40; ++i) header.order[i] = team[0x110 + i];
    header.captain = team[0x138];
    for (unsigned i = 0; i < 6; ++i) header.set_pieces[i] = team[0x139 + i];
    for (unsigned i = 0; i < 3; ++i) header.overlaps[i] = team[0x13f + i];
    for (unsigned i = 0; i < 6; ++i) header.extras[i] = team[0x142 + i];
    for (unsigned variant = 0; variant < 2; ++variant) {
        const auto *formation = team + 0x50 + variant * 0x60;
        if (readers.default_formation(formation)) result.envelope.default_formations |= 1U << variant;
        for (unsigned phase = 0; phase < 2; ++phase) {
            for (unsigned i = 0; i < 11; ++i) {
                const auto *positions = formation + phase * 0x22;
                const auto position = readers.position(positions, i);
                header.coordinates[variant][phase][i][0] = readers.role(positions, i);
                header.coordinates[variant][phase][i][1] = position[0];
                header.coordinates[variant][phase][i][2] = position[1];
            }
        }
    }
    for (unsigned i = 0; i < count; ++i) {
        const auto *member = team + 0x1b0 + i * 0xc8;
        auto &player = result.players[i];
        const auto id = field<uint64_t>(member, 8);
        player.id = id <= UINT32_MAX ? static_cast<uint32_t>(id) : 0;
        player.serial = field<uint64_t>(member, 0x58);
        player.uniform = field<uint16_t>(member, 0x48);
        std::fill(std::begin(player.parameters), std::end(player.parameters), UINT32_MAX);
        if (!player.id || !player.serial || !readers.parameters(player.id, player.serial, player.parameters))
            ++result.envelope.missing_records;
        for (uint32_t value : player.parameters)
            if (value > 65535) ++result.envelope.missing_parameters;
    }
    result.envelope.status = result.envelope.missing_parameters || result.envelope.missing_records ?
        SquadCaptureStatus::AttributesIncomplete : SquadCaptureStatus::Ready;
    return result;
}
}
