#include "restart_diagnostics.h"
#include "diagnostic_journal.h"

#include <array>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>

namespace {
constexpr size_t kHistoryFrames = 384; // Just over 14 seconds at 27 synchronized frames/s.
constexpr unsigned kPlayerCount = 22, kSeatCount = 24, kCommandsPerSeat = 11;
constexpr unsigned kSeatBytes = 0xb4, kCommandBytes = 0x10;
constexpr uint32_t kUnknown = 255;

struct Frame {
    uint32_t number = 0, missing = 0, ahead = 0, present = 0;
    uint32_t outer = 0, simulationFrame = 0, phase = kUnknown, restartKind = kUnknown;
    uint32_t firstKicker = kUnknown, secondKicker = kUnknown;
    uint32_t firstKickerFlag = 0, secondKickerFlag = 0;
    bool worldPresent = false, ballPresent = false;
    std::array<uint32_t, 6> ballBits{}; // Raw native position and velocity.
    std::array<uint32_t, kPlayerCount * 3> positionBits{};
    std::array<uint8_t, kSeatCount * kCommandsPerSeat> commands{};
};
static_assert(sizeof(Frame) * kHistoryFrames <= 256 * 1024);

std::mutex historyLock;
std::array<Frame, kHistoryFrames> history;
size_t nextFrame = 0, frameCount = 0;
uint32_t lastAgreement = 0, mismatchFrame = 0;
uint16_t localCheck = 0, peerCheck = 0;
// The first mismatch freezes the history. The host snapshot thread writes it.
std::atomic<bool> frozen{false};
std::atomic<bool> pending{false};

uintptr_t pointer(uintptr_t address) { return *reinterpret_cast<const uintptr_t *>(address); }
uint32_t word(uintptr_t address) { return *reinterpret_cast<const uint32_t *>(address); }
uint8_t byte(uintptr_t address) { return *reinterpret_cast<const uint8_t *>(address); }

uint32_t kicker(uintptr_t field) {
    const auto player = word(field);
    return player < kPlayerCount ? player : kUnknown;
}

template <size_t N>
void append_words(std::string &line, const std::array<uint32_t, N> &words) {
    line += '[';
    for (size_t i = 0; i < N; ++i) {
        if (i) line += ',';
        line += std::to_string(words[i]);
    }
    line += ']';
}

void append_commands(std::string &line, const Frame &frame) {
    line += '[';
    bool first = true;
    for (unsigned seat = 0; seat < kSeatCount; ++seat) {
        for (unsigned part = 0; part < kCommandsPerSeat; ++part) {
            const unsigned type = frame.commands[seat * kCommandsPerSeat + part];
            if (!type) continue;
            if (!first) line += ',';
            first = false;
            line += std::to_string(seat) + '/' + std::to_string(part) + '/' + std::to_string(type);
        }
    }
    line += ']';
}
} // namespace

void peerlink_record_restart_frame(uintptr_t base, uintptr_t controller, uintptr_t decoded_slots) {
    if (!base || !controller || !decoded_slots || !peerlink_restart_journal_active() ||
        frozen.load(std::memory_order_acquire) || pointer(controller) != base + 0x98aedd0) return;
    const uintptr_t match = pointer(base + 0xa533ec0);
    const uint32_t outer = match ? byte(match + 0x138) : 0;
    if (outer != 17 && outer != 20) return;
    const uintptr_t core = match ? pointer(match + 0x2f8) : 0;
    if (!core || pointer(core) != base + 0x9899d50 || word(core + 8) != 54) return;

    Frame frame;
    frame.number = word(controller + 0x30);
    if (!frame.number) return;
    frame.missing = word(controller + 0x28);
    frame.ahead = word(controller + 0x2c);
    frame.outer = outer;
    // The controller-mode selector (6fa40f8) reads this world through
    // 72c3e80. Log the two native kicker gates separately from ball ownership.
    const uintptr_t root = pointer(base + 0xa58de28);
    const uintptr_t world = root ? pointer(root + 0x4d0) : 0;
    if (world) {
        frame.worldPresent = true;
        frame.phase = word(world + 0x3314);
        frame.simulationFrame = word(world + 0x3324);
        frame.restartKind = word(world + 0x34ac);
        frame.firstKicker = kicker(world + 0x41e4);
        frame.secondKicker = kicker(world + 0x41e8);
        frame.firstKickerFlag = byte(world + 0x3350);
        frame.secondKickerFlag = byte(world + 0x3351);
    }
    const uintptr_t balls = pointer(core + 0x3c98);
    if (balls && pointer(balls) == base + 0x989a160) {
        const uintptr_t ball = pointer(balls + 0x30);
        const uint32_t slot = word(balls + 0xc);
        if (ball && slot < 8 && pointer(balls + 0x38 + slot * 8) == ball) {
            frame.ballPresent = true;
            std::memcpy(frame.ballBits.data(), reinterpret_cast<const void *>(ball + 0x30), sizeof(frame.ballBits));
        }
    }
    for (unsigned i = 0; i < kPlayerCount; ++i) {
        const uintptr_t player = pointer(base + 0xa5909a0 + 0x170 + i * 8);
        if (!player) continue;
        frame.present |= 1U << i;
        std::memcpy(frame.positionBits.data() + i * 3, reinterpret_cast<const void *>(player + 0x5dc), 12);
    }
    for (unsigned seat = 0; seat < kSeatCount; ++seat) {
        for (unsigned part = 0; part < kCommandsPerSeat; ++part) {
            const auto header = byte(decoded_slots + seat * kSeatBytes + part * kCommandBytes);
            // Same type decoding as 6b02fe0. Record only command kinds,
            // with seat and record indices, including the 0x4f check (79).
            frame.commands[seat * kCommandsPerSeat + part] = header < 0xf8 ? header & 0x7f : 0;
        }
    }

    std::lock_guard<std::mutex> lock(historyLock);
    if (frozen.load(std::memory_order_relaxed)) return;
    history[nextFrame] = frame;
    nextFrame = (nextFrame + 1) % kHistoryFrames;
    if (frameCount < kHistoryFrames) ++frameCount;
}

void peerlink_record_restart_comparison(uint32_t frame, uint16_t local, uint16_t peer) {
    if (!peerlink_restart_journal_active() || frozen.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> lock(historyLock);
    if (frozen.load(std::memory_order_relaxed)) return;
    if (local == peer) {
        if (frame > lastAgreement) lastAgreement = frame;
        return;
    }
    mismatchFrame = frame;
    localCheck = local;
    peerCheck = peer;
    frozen.store(true, std::memory_order_release);
    pending.store(true, std::memory_order_release);
}

void peerlink_flush_restart_trace() {
    if (!pending.exchange(false, std::memory_order_acquire)) return;
    // No producer writes after the first mismatch has frozen this buffer.
    const size_t oldest = (nextFrame + kHistoryFrames - frameCount) % kHistoryFrames;
    peerlink_restart_journal("RESTART_TRACE_FIRST_MISMATCH frame=%u lastAgreement=%u localValue=%u peerValue=%u "
        "capturedFrames=%zu firstCommandFrame=%u lastCommandFrame=%u stage=before_command_dispatch commandEncoding=seat/record/type",
        mismatchFrame, lastAgreement, unsigned(localCheck), unsigned(peerCheck), frameCount,
        frameCount ? history[oldest].number : 0, frameCount ? history[(nextFrame + kHistoryFrames - 1) % kHistoryFrames].number : 0);
    std::string details;
    details.reserve(2048);
    for (size_t i = 0; i < frameCount; ++i) {
        const Frame &frame = history[(oldest + i) % kHistoryFrames];
        details = "commands=";
        append_commands(details, frame);
        details += " ballBits=";
        append_words(details, frame.ballBits);
        details += " positionBits=";
        append_words(details, frame.positionBits);
        peerlink_restart_journal("RESTART_TRACE_FRAME commandFrame=%u outer=%u worldPresent=%u simFrame=%u "
            "phase=%u restartKind=%u kickers=%u,%u kickerFlags=%u,%u missingMask=%u aheadMask=%u "
            "ballPresent=%u presentMask=%u %s", frame.number, frame.outer, unsigned(frame.worldPresent),
            frame.simulationFrame, frame.phase, frame.restartKind, frame.firstKicker, frame.secondKicker,
            frame.firstKickerFlag, frame.secondKickerFlag, frame.missing, frame.ahead, unsigned(frame.ballPresent),
            frame.present, details.c_str());
    }
    peerlink_restart_journal("RESTART_TRACE_COMPLETE frames=%zu", frameCount);
}
