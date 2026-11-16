#include "match_state_diagnostics.h"
#include <array>
#include <atomic>
#include <cstring>

namespace {
uintptr_t engine = 0;
uintptr_t ptr(uintptr_t at) { return *reinterpret_cast<const uintptr_t *>(at); }
uint32_t word(uintptr_t at) { return *reinterpret_cast<const uint32_t *>(at); }
uint16_t half(uintptr_t at) { return *reinterpret_cast<const uint16_t *>(at); }
unsigned byte(uintptr_t at) { return *reinterpret_cast<const unsigned char *>(at); }
template<class T> T fn(uintptr_t offset) { return reinterpret_cast<T>(engine + offset); }

struct Snapshot {
    std::atomic<int> published{0};
    uint32_t command_frame = 0, state_frame = 0, checksum = 0, terms = 0;
    std::array<uint32_t, 71> parts{}; // Match, ball, 3 objects, 2*11*3 player parts.
    std::array<uint32_t, 10> options{};
    std::array<unsigned, 28> flags{};
    std::array<uint32_t, 66> player_position_bits{};
    std::array<uint32_t, 6> ball_bits{};
    unsigned players = 0;
};
Snapshot initial, at_stop;
struct Difference {
    std::atomic<int> published{0};
    uint32_t frame = 0, stored_frame = 0, local = 0, peer = 0, origin = 0;
    uint32_t verdict = 0, count = 0, first_frame = 0, command_frame = 0;
};
Difference difference;

uintptr_t command_controller(uintptr_t *checker = nullptr) {
    if (!engine) return 0;
    uintptr_t service = ptr(engine + 0x9a6a020);
    if (!service || ptr(service) != engine + 0x98ae9e0) return 0;
    uintptr_t inner = ptr(service + 8);
    if (!inner) return 0;
    uintptr_t controller = ptr(inner + 0xa0);
    if (!controller || ptr(controller) != engine + 0x98aedd0) return 0;
    if (checker) *checker = ptr(inner + 0xd8); // 7011584 -> 705369c.
    return controller;
}

void capture(Snapshot &output, uintptr_t controller) {
    if (output.published.load(std::memory_order_acquire) || !engine) return;
    // 7053d8c loads the map's service handle; 6affecc dereferences that
    // handle once more before 6afff10/6afff20 receive the checksum state.
    uintptr_t handle = ptr(engine + 0xa5909a0);
    uintptr_t state = handle ? ptr(handle) : 0;
    if (!state) return;
    // These are the same read-only checksum contributors called by 6afff20.
    // Avoid 6afff10: it increments the checksum service's invocation counter.
    using Match = uint32_t (*)(uintptr_t, uint32_t *, uint32_t *, uint32_t *);
    using Part = void (*)(uintptr_t, uint32_t *, uint32_t *);
    using Object = void (*)(uintptr_t, uint32_t *, uint32_t *, uint32_t);
    using Player = void (*)(uintptr_t, uint32_t *, uint32_t *, uint32_t, uint32_t);
    uint32_t sum = 0, ordinal = 0, frame = 0;
    if (!(fn<Match>(0x6b00048)(state, &sum, &ordinal, &frame) & 1)) return;
    int expected = 0;
    if (!output.published.compare_exchange_strong(expected, 1, std::memory_order_acq_rel)) return;
    output.command_frame = word(controller + 0x30);
    output.state_frame = frame;
    size_t index = 0;
    output.parts[index++] = sum;
    uint32_t previous = sum;
    fn<Part>(0x6b00474)(state, &sum, &ordinal);
    output.parts[index++] = sum - previous;
    for (uint32_t object = 0; object < 3; ++object) {
        previous = sum;
        fn<Object>(0x6b0050c)(state, &sum, &ordinal, object);
        output.parts[index++] = sum - previous;
    }
    constexpr uintptr_t player_parts[] = {0x6b00650, 0x6b00794, 0x6b00908};
    for (uint32_t side = 0; side < 2; ++side) for (uint32_t slot = 0; slot < 11; ++slot)
        for (uintptr_t function : player_parts) {
            previous = sum;
            fn<Player>(function)(state, &sum, &ordinal, side, slot);
            output.parts[index++] = sum - previous;
        }
    output.checksum = (sum + (sum >> 16)) & 0xffff; // Original 6b0116c.
    output.terms = ordinal;
    // Only verified scalar options read by the original 6a5d5d4 settings copy.
    // Do not export pointer fields, account identifiers, or session material.
    uintptr_t owner = ptr(engine + 0xa5fb348), work = owner ? ptr(owner + 0x48) : 0;
    if (work) {
        uintptr_t source = work + 0x6c4c0;
        constexpr unsigned offsets[] = {0x10c,0x140,0x154,0xbc,0xc0,0xc4,0xc8,0xcc,0x5f0,0x5f4};
        constexpr unsigned flags[] = {0xb8,0xb9,0xba,0xda,0xdc,0xdb,0xd0,0xf7,0xf8,0xf9,
            0x102,0x103,0x138,0x139,0x13a,0x14d,0x13b,0x13c,0x144,0x146,0x147,0x148,
            0x149,0x14a,0x14e,0x151,0x14c,0x150};
        for (size_t i = 0; i < output.options.size(); ++i) output.options[i] = word(source + offsets[i]);
        for (size_t i = 0; i < output.flags.size(); ++i) output.flags[i] = byte(source + flags[i]);
    }
    // Original 69c5228 -> 6b01114 -> 6a9d2ac resolves the canonical player
    // position to map[0x170 + player*8] + 0x10 + 0xd8 + 0x4f4.
    for (unsigned player = 0; player < 22; ++player) {
        uintptr_t object = ptr(engine + 0xa5909a0 + 0x170 + player * 8);
        if (object) {
            std::memcpy(output.player_position_bits.data() + player * 3,
                reinterpret_cast<const void *>(object + 0x5dc), 12);
            ++output.players;
        }
    }
    uintptr_t match = ptr(engine + 0xa533ec0), core = match ? ptr(match + 0x2f8) : 0;
    if (core && ptr(core) == engine + 0x9899d50) {
        uintptr_t balls = ptr(core + 0x3c98);
        if (balls && ptr(balls) == engine + 0x989a160) {
            uintptr_t ball = ptr(balls + 0x30);
            if (ball) std::memcpy(output.ball_bits.data(), reinterpret_cast<const void *>(ball + 0x30), 24);
        }
    }
    output.published.store(2, std::memory_order_release);
}

template<class T, size_t N> void append_array(std::string &text, const std::array<T, N> &values) {
    text += '[';
    for (size_t i = 0; i < N; ++i) { if (i) text += ','; text += std::to_string(values[i]); }
    text += ']';
}
void append_snapshot(std::string &text, const char *name, const Snapshot &state) {
    text += '\n'; text += name;
    if (state.published.load(std::memory_order_acquire) != 2) { text += " present=0"; return; }
    text += " present=1 commandFrame=" + std::to_string(state.command_frame) +
        " stateFrame=" + std::to_string(state.state_frame) + " checkValue=" + std::to_string(state.checksum) +
        " terms=" + std::to_string(state.terms) + " parts=";
    append_array(text, state.parts);
    text += " sourceOptions="; append_array(text, state.options);
    text += " sourceFlags="; append_array(text, state.flags);
    text += " players=" + std::to_string(state.players) + " playerPositionBits=";
    append_array(text, state.player_position_bits);
    text += " ballBits="; append_array(text, state.ball_bits);
}
}

void peerlink_match_state_begin(uintptr_t base) { engine = base; }

void peerlink_match_state_sample_initial() {
    if (initial.published.load(std::memory_order_acquire)) return;
    uintptr_t controller = command_controller();
    if (!controller) return;
    uintptr_t match = ptr(engine + 0xa533ec0);
    if (!match || byte(match + 0x138) != 17) return;
    uintptr_t core = ptr(match + 0x2f8);
    if (!core || ptr(core) != engine + 0x9899d50 || word(core + 8) != 54) return;
    for (unsigned player = 0; player < 22; ++player)
        if (!ptr(engine + 0xa5909a0 + 0x170 + player * 8)) return;
    capture(initial, controller);
}

void peerlink_match_check_request(uintptr_t controller, uintptr_t checker,
                                 uintptr_t context, uintptr_t caller) {
    if (!engine || caller != engine + 0x70539e4 || !checker || !context) return;
    uintptr_t actual_checker = 0;
    if (command_controller(&actual_checker) != controller || actual_checker != checker ||
        ptr(context + 0x18) != controller || word(context + 0x10) < 3) return;
    int expected = 0;
    if (!difference.published.compare_exchange_strong(expected, 1, std::memory_order_acq_rel)) return;
    difference.origin = byte(context + 1);
    const uint32_t stored = half(context + 2), current = half(context + 4);
    difference.local = difference.origin == 255 ? current : stored;
    difference.peer = difference.origin == 255 ? stored : current;
    difference.stored_frame = word(context + 8);
    difference.frame = word(context + 12);
    difference.verdict = word(context + 16);
    difference.count = word(checker + 8);
    difference.first_frame = word(checker + 12);
    difference.command_frame = word(controller + 0x30);
    capture(at_stop, controller);
    difference.published.store(2, std::memory_order_release);
}

bool peerlink_match_check_failed() { return difference.published.load(std::memory_order_acquire) == 2; }

std::string peerlink_match_state_diagnostics() {
    std::string text = "MATCH_CHECK_MISMATCH";
    if (!peerlink_match_check_failed()) text += " present=0";
    else text += " present=1 compareFrame=" + std::to_string(difference.frame) +
        " storedFrame=" + std::to_string(difference.stored_frame) + " localValue=" + std::to_string(difference.local) +
        " peerValue=" + std::to_string(difference.peer) + " originUser=" + std::to_string(difference.origin) +
        " verdict=" + std::to_string(difference.verdict) + " mismatchCount=" + std::to_string(difference.count) +
        " firstMismatchFrame=" + std::to_string(difference.first_frame) +
        " stopCommandFrame=" + std::to_string(difference.command_frame);
    append_snapshot(text, "MATCH_STATE_INITIAL", initial);
    append_snapshot(text, "MATCH_STATE_AT_STOP", at_stop);
    return text;
}
