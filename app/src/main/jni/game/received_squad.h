#pragma once
#include "squad_dto.h"
#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace peerlink {
constexpr size_t kTeamMetadataBytes = 0x2100;
using TeamMetadata = std::array<uint8_t, kTeamMetadataBytes>;

enum class SquadCaptureStatus : uint32_t {
    Ready, WaitingForSync, MemoryUnavailable, PeerAmbiguous, InvalidMetadata, AttributesIncomplete
};

struct SquadCaptureEnvelope {
    uint32_t magic = 0x31435352, version = 1;
    SquadCaptureStatus status = SquadCaptureStatus::WaitingForSync;
    uint32_t account = 0, team_slot = UINT32_MAX, count = 0;
    uint32_t missing_records = 0, missing_parameters = 0, default_formations = 0;
    uint32_t reserved[3]{};
};
static_assert(sizeof(SquadCaptureEnvelope) == 48, "received squad envelope");

struct ReceivedSquad {
    SquadCaptureEnvelope envelope;
    PeerlinkSquadHeader squad{};
    std::array<PeerlinkSquadPlayer, 40> players{};
    TeamMetadata metadata{};
    std::vector<uint8_t> bytes() const;
};

struct SquadReaders {
    std::function<uint32_t(const void *, uint32_t)> role;
    std::function<std::array<uint8_t, 2>(const void *, uint32_t)> position;
    std::function<bool(const void *)> default_formation;
    // Returns false for a missing keyed record. Missing fields retain UINT32_MAX.
    std::function<bool(uint32_t, uint64_t, uint32_t *)> parameters;
};

ReceivedSquad captureReceivedSquad(const std::array<TeamMetadata, 2> &teams,
    const PeerlinkSquadHeader &own, const PeerlinkSquadPlayer *own_players,
    uint32_t peer, const SquadReaders &readers);
std::vector<uint8_t> observeReceivedSquad(uintptr_t base, uint32_t self, uint32_t peer,
                                        const uint8_t *input, size_t size);
}
