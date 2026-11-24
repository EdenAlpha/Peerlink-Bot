#include "match_state_diagnostics.h"
#include "diagnostic_journal.h"
#include <array>
#include <atomic>
#include <cstring>
#include <mutex>
#include <unistd.h>

namespace {
uintptr_t engine = 0;
uintptr_t ptr(uintptr_t at) { return *reinterpret_cast<const uintptr_t *>(at); }
uint32_t word(uintptr_t at) { return *reinterpret_cast<const uint32_t *>(at); }
uint16_t half(uintptr_t at) { return *reinterpret_cast<const uint16_t *>(at); }
unsigned byte(uintptr_t at) { return *reinterpret_cast<const unsigned char *>(at); }
template<class T> T fn(uintptr_t offset) { return reinterpret_cast<T>(engine + offset); }

struct Checksum {
    uint32_t state_frame = 0, value = 0, terms = 0;
    std::array<uint32_t, 71> parts{}; // Match, ball, 3 objects, 2*11*3 player parts.
    std::array<uint32_t, 71> part_terms{};
};
struct Snapshot {
    std::atomic<int> published{0};
    uint32_t command_frame = 0;
    Checksum check;
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

constexpr unsigned kUnknownPlayer = 255;
constexpr uint32_t kDenseSceneFrames = 81;
constexpr uint32_t kLaterSceneStride = 9;
struct KeeperScene {
    uintptr_t scene = 0, controller = 0;
    uint32_t number = 0, id = 0, start_frame = 0, play_frame = 0;
    uint32_t last_frame = 0, last_logged = 0, last_step = 0, slots_frame = 0;
    bool complete = false;
    std::array<unsigned, 2> keepers{kUnknownPlayer, kUnknownPlayer};
};
// The engine dispatches successive command frames on different workers.
// Share scene identity and history. Keep clock reads scoped to the current
// original dispatcher call on its worker.
std::mutex keeper_scene_mutex;
KeeperScene keeper_scene;
struct KeeperClock {
    uintptr_t actor = 0;
    unsigned reads = 0;
    bool timeline_loaded = false;
    std::array<uint32_t, 4> bits{};
};
thread_local KeeperClock keeper_clock;
struct RecentPlayers {
    uint32_t frame = 0, present_mask = 0;
    std::array<uint32_t, 66> positions{};
};
// Retain a few verified samples in case the peer's check arrives later.
struct PlayerHistory {
    uintptr_t controller = 0;
    std::array<RecentPlayers, 8> entries{};
    size_t next = 0;
    uint32_t latest = 0, waiting_for_mismatch = 0;
};
std::mutex recent_players_mutex;
PlayerHistory recent_players;

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

uintptr_t checksum_state() {
    // 7053d8c loads the map's service handle; 6affecc dereferences that
    // handle once more before 6afff10/6afff20 receive the checksum state.
    uintptr_t handle = ptr(engine + 0xa5909a0);
    return handle ? ptr(handle) : 0;
}

bool read_checksum(Checksum &output, uintptr_t state, const char *kind = nullptr, uint32_t command_frame = 0) {
    if (!state) return false;
    // These are the same read-only checksum contributors called by 6afff20.
    // Avoid 6afff10: it increments the checksum service's invocation counter.
    using Match = uint32_t (*)(uintptr_t, uint32_t *, uint32_t *, uint32_t *);
    using Part = void (*)(uintptr_t, uint32_t *, uint32_t *);
    using Object = void (*)(uintptr_t, uint32_t *, uint32_t *, uint32_t);
    using Player = void (*)(uintptr_t, uint32_t *, uint32_t *, uint32_t, uint32_t);
    uint32_t sum = 0, ordinal = 0;
    if (!(fn<Match>(0x6b00048)(state, &sum, &ordinal, &output.state_frame) & 1)) return false;
    if (kind) peerlink_journal("MATCH_STATE_CAPTURE_BEGIN kind=%s commandFrame=%u stateFrame=%u",
        kind, command_frame, output.state_frame);
    size_t index = 0;
    uint32_t previous = 0, previous_ordinal = 0;
    auto record_part = [&] {
        output.parts[index] = sum - previous;
        output.part_terms[index++] = ordinal - previous_ordinal;
        previous = sum;
        previous_ordinal = ordinal;
    };
    record_part();
    fn<Part>(0x6b00474)(state, &sum, &ordinal);
    record_part();
    for (uint32_t object = 0; object < 3; ++object) {
        fn<Object>(0x6b0050c)(state, &sum, &ordinal, object);
        record_part();
    }
    constexpr uintptr_t player_parts[] = {0x6b00650, 0x6b00794, 0x6b00908};
    for (uint32_t side = 0; side < 2; ++side) for (uint32_t slot = 0; slot < 11; ++slot)
        for (uintptr_t function : player_parts) {
            fn<Player>(function)(state, &sum, &ordinal, side, slot);
            record_part();
        }
    output.value = (sum + (sum >> 16)) & 0xffff; // Original 6b0116c.
    output.terms = ordinal;
    return true;
}

void capture(Snapshot &output, uintptr_t controller, const Checksum *verified = nullptr) {
    if (output.published.load(std::memory_order_acquire) || !engine) return;
    int expected = 0;
    if (!output.published.compare_exchange_strong(expected, 1, std::memory_order_acq_rel)) return;
    const char *kind = &output == &initial ? "initial" : "at_stop";
    output.command_frame = word(controller + 0x30);
    if (verified) {
        // Reuse the snapshot already validated in the game's own checksum
        // callback. Do not call game checksum helpers again from a host poll.
        output.check = *verified;
        peerlink_journal("MATCH_STATE_CAPTURE_BEGIN kind=%s commandFrame=%u stateFrame=%u",
            kind, output.command_frame, output.check.state_frame);
    } else if (!read_checksum(output.check, checksum_state(), kind, output.command_frame)) {
        output.published.store(0, std::memory_order_release);
        return;
    }
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
    peerlink_journal("MATCH_STATE_CAPTURE_COMPLETE kind=%s commandFrame=%u stateFrame=%u checksum=%u",
        kind, output.command_frame, output.check.state_frame, output.check.value);
}

template<class T, size_t N> void append_array(std::string &text, const std::array<T, N> &values) {
    text += '[';
    for (size_t i = 0; i < N; ++i) { if (i) text += ','; text += std::to_string(values[i]); }
    text += ']';
}

template<size_t N> std::array<uint32_t, N> copy_words(uintptr_t source) {
    std::array<uint32_t, N> bits{};
    std::memcpy(bits.data(), reinterpret_cast<const void *>(source), sizeof(bits));
    return bits;
}

void record_first_differing_players(uintptr_t controller, uint32_t frame, bool recovered = false) {
    if (!peerlink_keeper_journal_active()) return;
    RecentPlayers current, previous;
    {
        std::lock_guard<std::mutex> lock(recent_players_mutex);
        auto &history = recent_players;
        // A comparison can arrive on a different worker before the local
        // checksum callback. Record it now and fill in the exact sample later.
        if (history.controller != controller) {
            history = {};
            history.controller = controller;
        }
        for (const auto &sample : history.entries) {
            if (sample.frame == frame) current = sample;
            if (sample.frame < frame && sample.frame > previous.frame) previous = sample;
        }
        if (!current.frame && !recovered) history.waiting_for_mismatch = frame;
    }
    std::string details;
    if (current.frame) {
        details += " presentMask=" + std::to_string(current.present_mask) + " positionBits=";
        append_array(details, current.positions);
    }
    if (previous.frame) {
        details += " previousFrame=" + std::to_string(previous.frame) +
            " previousPresentMask=" + std::to_string(previous.present_mask) + " previousPositionBits=";
        append_array(details, previous.positions);
    }
    if (recovered) details += " recovered=1";
    peerlink_keeper_journal("FIRST_MISMATCH_PLAYERS frame=%u exactSnapshot=%u%s", frame,
        unsigned(current.frame != 0), details.c_str());
}

void remember_players(uintptr_t controller, uint32_t frame) {
    if (!controller || !peerlink_keeper_journal_active()) return;
    RecentPlayers sample;
    sample.frame = frame;
    for (unsigned player = 0; player < 22; ++player) {
        const uintptr_t object = ptr(engine + 0xa5909a0 + 0x170 + player * 8);
        if (!object) continue;
        std::memcpy(sample.positions.data() + player * 3, reinterpret_cast<const void *>(object + 0x5dc), 12);
        sample.present_mask |= 1U << player;
    }
    bool recovered;
    {
        std::lock_guard<std::mutex> lock(recent_players_mutex);
        auto &history = recent_players;
        if (history.controller != controller || (frame == 27 && history.latest > 27 && word(controller + 0x30) <= 54)) {
            history = {};
            history.controller = controller;
        }
        for (const auto &entry : history.entries) if (entry.frame == frame) return;
        history.entries[history.next] = sample;
        history.next = (history.next + 1) % history.entries.size();
        if (frame > history.latest) history.latest = frame;
        recovered = frame == history.waiting_for_mismatch;
        if (recovered) history.waiting_for_mismatch = 0;
    }
    if (recovered) record_first_differing_players(controller, frame, true);
}

void append_keeper(std::string &text, unsigned side, unsigned player) {
    const char *prefix = side == 0 ? " k0" : " k1";
    auto field = [&](const char *name) -> std::string & { return text.append(prefix).append(name).append("="); };
    field("Player") += std::to_string(player);
    if (player >= 22 || player / 11 != side) return;
    const uintptr_t object = ptr(engine + 0xa5909a0 + 0x170 + player * 8);
    field("PlayerPresent") += std::to_string(object != 0);
    if (!object) return;
    // Use the same fields as the engine's player checksum contributors.
    // Keep their raw bit patterns so samples from different scenes can be compared exactly.
    append_array(field("PositionBits"), copy_words<3>(object + 0x5dc));
    append_array(field("Part2VectorBits"), copy_words<6>(object + 0x634));
    append_array(field("Part2ExtraVectorBits"), copy_words<3>(object + 0x6d4));
    append_array(field("Part2FloatBits"), std::array<uint32_t, 4>{
        word(object + 0x6ec), word(object + 0x6f0), word(object + 0x5d4), word(object + 0x5d8)});
    append_array(field("Part2State"), std::array<uint32_t, 3>{
        word(object + 0x650), word(object + 0x654), word(object + 0x754)});
    const uintptr_t controller = ptr(object + 0x7a8);
    field("ControllerPresent") += std::to_string(controller != 0);
    if (!controller) return;
    // 6b00908 reads these controller state fields and the track time via
    // 6ddc81c. Read in place within the original scene callback.
    const uintptr_t work = controller + 0xad0;
    append_array(field("Part3State"), std::array<uint32_t, 5>{
        half(work + 0x18), half(work + 0x1a), half(work + 0x1c), word(work + 0x10), word(work + 0x14)});
    const auto stream = copy_words<4>(controller + 0x3a14);
    field("StateStreamFold") += std::to_string(stream[0] ^ stream[1] ^ stream[2] ^ stream[3]);
    const uintptr_t track = ptr(controller + 0xa88);
    field("TrackPresent") += std::to_string(track != 0);
    if (track) {
        const unsigned enabled = byte(track + 0x1e2);
        field("TrackEnabled") += std::to_string(enabled);
        field("TrackTimeBits") += std::to_string(enabled ? word(track + 0x1e4) : 0xbf800000U);
    }
}

void record_keeper_sample(const KeeperScene &trace, const KeeperClock &clock, const char *stage,
                          uint32_t frame, uintptr_t context, uintptr_t transition, uint32_t step, bool finished) {
    const uintptr_t match = ptr(engine + 0xa533ec0);
    const unsigned outer = match ? byte(match + 0x138) : 0;
    const uintptr_t core = match ? ptr(match + 0x2f8) : 0;
    const bool core_valid = core && ptr(core) == engine + 0x9899d50;
    const unsigned core_state = core_valid ? word(core + 8) : 0;
    const uintptr_t actor = ptr(trace.scene + 0x280);
    const uintptr_t vtable = actor ? ptr(actor) : 0;
    const bool actor_valid = vtable >= engine + 0x8c5bab0 && vtable < engine + 0x9a16c50 - 0x28 &&
        ptr(vtable + 0x28) == engine + 0x69d25bc;
    const unsigned index = word(context + 8);
    const uintptr_t handler = index < 9 ? ptr(context + 0x30 + index * 8) : 0;
    const uintptr_t handler_table = handler ? ptr(handler) : 0;
    const uintptr_t handler_call = handler_table >= engine + 0x8c5bab0 && handler_table < engine + 0x9a16c50 - 0x38
        ? ptr(handler_table + 0x38) : 0;
    const uintptr_t ball_context = ptr(context + 0x680);
    unsigned owner = kUnknownPlayer;
    bool owner_present = false, same_ball_context = false;
    std::array<unsigned, 2> keepers{kUnknownPlayer, kUnknownPlayer};
    if (outer == 17 && core_state == 54) {
        // 733d0f4 -> 72c4028 -> 72c19c0 gives the side roster.
        // 733d148 returns the first zero role at +0x42b8 (keeper).
        const uintptr_t simulation = ptr(engine + 0xa58de28);
        if (simulation) for (unsigned side = 0; side < keepers.size(); ++side) {
            const uintptr_t roster = ptr(simulation + 0x3030 + side * 0x50);
            if (!roster) continue;
            for (unsigned slot = 0; slot < 11; ++slot) if (word(roster + 0x42b8 + slot * 4) == 0) {
                keepers[side] = side * 11 + slot;
                break;
            }
        }
        const uintptr_t balls = ptr(core + 0x3c98);
        if (balls && ptr(balls) == engine + 0x989a160) {
            // 72c3ddc and 6b60a00 used by the match's possession reader.
            same_ball_context = ball_context == balls + 0x58;
            const uintptr_t possession = ptr(balls + 0x68);
            if (possession) {
                owner = word(possession + 0x28780);
                owner_present = owner < 22 || owner == kUnknownPlayer;
            }
        }
    }

    std::string details;
    details.reserve(1000);
    details += "clockReads=" + std::to_string(clock.reads);
    if (clock.reads) {
        details += " timelineLoaded=" + std::to_string(clock.timeline_loaded) + " cutClockBits=";
        append_array(details, clock.bits);
    }
    for (unsigned side = 0; side < keepers.size(); ++side) {
        append_keeper(details, side, keepers[side]);
    }
    {
        std::lock_guard<std::mutex> lock(keeper_scene_mutex);
        if (keeper_scene.number == trace.number && frame >= keeper_scene.slots_frame && outer == 17 && core_state == 54) {
            keeper_scene.slots_frame = frame;
            keeper_scene.keepers = keepers;
        }
    }
    peerlink_keeper_journal("KEEPER_SCENE_STATE scene=%u sceneId=%u stage=%s commandFrame=%u startFrame=%u playFrame=%u step=%u "
        "finished=%u outer=%u core=%u transition=%u actorVerified=%u actorMode=%u binding=%u interrupted=%u "
        "handlerIndex=%u handlerOffset=0x%llx ballContext=%u sameBallContext=%u ballOwnerPresent=%u ballOwner=%u "
        "missingMask=%u aheadMask=%u stopPending=%u samplerTid=%d %s",
        trace.number, trace.id, stage, frame, trace.start_frame, trace.play_frame, step, unsigned(finished),
        outer, core_state, byte(transition + 0x22e), unsigned(actor_valid),
        actor_valid ? word(actor + 0x250) : 0, unsigned(actor_valid && ptr(actor + 0x228)),
        actor_valid ? byte(actor + 0x60) : 0, index,
        static_cast<unsigned long long>(handler_call >= engine + 0x2866500 && handler_call < engine + 0x8c57ab0
            ? handler_call - engine : 0), unsigned(ball_context != 0), unsigned(same_ball_context), unsigned(owner_present),
        owner_present ? owner : kUnknownPlayer, word(trace.controller + 0x28), word(trace.controller + 0x2c),
        byte(trace.controller + 0x45), gettid(), details.c_str());
}

KeeperScene keeper_scene_at(uintptr_t controller, uint32_t frame) {
    std::lock_guard<std::mutex> lock(keeper_scene_mutex);
    const auto &trace = keeper_scene;
    // The state checksum can refer to the command frame immediately before the scene.
    if (trace.scene && trace.controller == controller && frame && frame >= trace.start_frame - 1 &&
        (frame <= trace.last_frame || frame - trace.last_frame <= kDenseSceneFrames)) return trace;
    return {};
}

void append_snapshot(std::string &text, const char *name, const Snapshot &state) {
    text += '\n'; text += name;
    if (state.published.load(std::memory_order_acquire) != 2) { text += " present=0"; return; }
    text += " present=1 commandFrame=" + std::to_string(state.command_frame) +
        " stateFrame=" + std::to_string(state.check.state_frame) + " checkValue=" + std::to_string(state.check.value) +
        " terms=" + std::to_string(state.check.terms) + " parts=";
    append_array(text, state.check.parts);
    text += " sourceOptions="; append_array(text, state.options);
    text += " sourceFlags="; append_array(text, state.flags);
    text += " players=" + std::to_string(state.players) + " playerPositionBits=";
    append_array(text, state.player_position_bits);
    text += " ballBits="; append_array(text, state.ball_bits);
}
}

void peerlink_match_state_begin(uintptr_t base) { engine = base; }

PeerLinkKeeperSceneTick peerlink_keeper_scene_enter(uintptr_t scene, uintptr_t context, uintptr_t transition) {
    if (!engine || !context || scene != context + 0x3f0 || !transition || !peerlink_keeper_journal_active()) return {};
    const uint32_t id = word(scene + 8), step = word(scene + 4);
    const uint32_t family = id >> 16;
    if ((family != 2 && family != 3) || step == 0) return {};
    const uintptr_t controller = command_controller();
    const uint32_t frame = controller ? word(controller + 0x30) : 0;
    if (!frame) return {};

    KeeperScene before;
    bool sample, new_scene;
    {
        std::lock_guard<std::mutex> lock(keeper_scene_mutex);
        auto &trace = keeper_scene;
        if (trace.controller == controller && frame < trace.last_frame) return {};
        new_scene = trace.scene != scene || trace.id != id || trace.controller != controller ||
            (trace.complete && frame > trace.last_frame && step <= 2);
        if (new_scene) {
            const uint32_t number = trace.number + 1;
            trace = {};
            trace.scene = scene;
            trace.controller = controller;
            trace.id = id;
            trace.number = number;
            trace.start_frame = frame;
        }
        if (trace.complete) return {};
        if (step == 8 && !trace.play_frame) trace.play_frame = frame;
        trace.last_frame = frame;
        const uint32_t dense_from = trace.play_frame ? trace.play_frame : trace.start_frame;
        sample = new_scene || step != trace.last_step || (frame != trace.last_logged &&
            (frame - dense_from <= kDenseSceneFrames || frame - trace.last_logged >= kLaterSceneStride));
        before = trace;
    }
    keeper_clock = {};
    if (sample) keeper_clock.actor = ptr(scene + 0x280);
    if (sample && (new_scene || step == 8))
        record_keeper_sample(before, keeper_clock, "before", frame, context, transition, step, false);
    return {before.number, frame, step, sample};
}

void peerlink_keeper_scene_leave(const PeerLinkKeeperSceneTick &tick, uintptr_t scene, uintptr_t context,
                                 uintptr_t transition, bool finished) {
    if (!tick.number) return;
    const KeeperClock clock = keeper_clock;
    keeper_clock = {};
    const uint32_t step = word(scene + 4);
    const bool sample = tick.sample || step != tick.step || finished;
    KeeperScene after;
    {
        std::lock_guard<std::mutex> lock(keeper_scene_mutex);
        auto &trace = keeper_scene;
        if (tick.number != trace.number || trace.scene != scene || tick.frame < trace.last_frame) return;
        if (sample) trace.last_logged = tick.frame;
        trace.last_step = step;
        trace.complete = finished;
        after = trace;
    }
    if (sample) {
        // A stop or reset inside the original call can release the controller.
        // Keep the final marker even if this snapshot is no longer available.
        if (command_controller() == after.controller) {
            record_keeper_sample(after, clock, "after", tick.frame, context, transition, step, finished);
        } else {
            peerlink_keeper_journal("KEEPER_SCENE_STATE_LOST scene=%u sceneId=%u commandFrame=%u finished=%u",
                after.number, after.id, tick.frame, unsigned(finished));
        }
    }
}

void peerlink_keeper_clock_sample(uintptr_t timeline_slot, bool timeline_loaded) {
    auto &clock = keeper_clock;
    if (!clock.actor || !timeline_slot || ptr(timeline_slot - 0x10) != clock.actor) return;
    ++clock.reads;
    clock.timeline_loaded = timeline_loaded;
    // The timer's owner was matched to the current dispatcher call on this worker.
    const uintptr_t timer = timeline_slot - 0x18;
    clock.bits = {word(timer + 0xb4), word(timer + 0xd8), word(timer + 0xb8), word(timer + 0xa8)};
}

void peerlink_record_check_frame(uint32_t frame, uint32_t value) {
    if (!engine || !frame) return;
    Checksum check;
    if (!read_checksum(check, checksum_state()) || check.state_frame != frame || check.value != value) {
        peerlink_journal("MATCH_CHECK_FRAME frame=%u checkValue=%u snapshotVerified=0 observedFrame=%u observedValue=%u",
            frame, value, check.state_frame, check.value);
        return;
    }
    const uintptr_t controller = command_controller();
    remember_players(controller, frame);
    // The game's own checksum callback runs after its match state is ready.
    // Sampling from the host's startup poll can race initialization or
    // teardown and call a checksum helper with a missing simulation object.
    if (!initial.published.load(std::memory_order_acquire)) {
        const uintptr_t match_state = ptr(engine + 0xa533ec0);
        const uintptr_t core = match_state ? ptr(match_state + 0x2f8) : 0;
        bool players_ready = true;
        for (unsigned player = 0; player < 22; ++player)
            if (!ptr(engine + 0xa5909a0 + 0x170 + player * 8)) { players_ready = false; break; }
        if (controller && match_state && byte(match_state + 0x138) == 17 &&
            core && ptr(core) == engine + 0x9899d50 && word(core + 8) == 54 && players_ready) {
            capture(initial, controller, &check);
            if (initial.published.load(std::memory_order_acquire) == 2)
                peerlink_journal("%s", peerlink_match_state_diagnostics().c_str());
        }
    }
    // Native ball contributor 6b00474 -> 6b00d18 -> 72d9980. Its
    // history record is distinct from the ball object's live coordinates.
    const uintptr_t match = ptr(engine + 0xa58de28);
    const uint32_t selected = match ? word(match + 0x54) : 3;
    const uintptr_t history = selected < 3 ? ptr(match + 0x70 + selected * 0x50) : 0;
    const uintptr_t ball = history ? fn<uintptr_t (*)(uintptr_t, uint32_t)>(0x72d9980)(history, 0) : 0;
    std::string details;
    details.reserve(1200);
    details += "parts="; append_array(details, check.parts);
    details += " partTerms="; append_array(details, check.part_terms);
    if (ball) {
        // 6b00d20 reads XYZ. 6b00d80 hashes all four rotation components.
        // Keep the six-word live position/velocity snapshot in capture() separate.
        std::array<uint32_t, 7> bits{};
        std::memcpy(bits.data(), reinterpret_cast<const void *>(ball), sizeof(bits));
        details += " ballBits="; append_array(details, bits);
    }
    peerlink_journal("MATCH_CHECK_FRAME frame=%u checkValue=%u snapshotVerified=1 terms=%u %s",
        frame, value, check.terms, details.c_str());
    const auto trace = keeper_scene_at(controller, frame);
    if (trace.scene) {
        std::string players;
        for (unsigned side = 0; side < trace.keepers.size(); ++side) {
            const unsigned player = trace.keepers[side];
            if (player >= 22) continue;
            players += side == 0 ? " k0Parts=" : " k1Parts=";
            const size_t i = 5 + 3 * player;
            append_array(players, std::array<uint32_t, 3>{check.parts[i], check.parts[i + 1], check.parts[i + 2]});
        }
        peerlink_keeper_journal("KEEPER_SCENE_CHECK scene=%u sceneId=%u frame=%u localValue=%u samplerTid=%d%s",
            trace.number, trace.id, frame, value, gettid(), players.c_str());
    }
}

void peerlink_record_check_comparison(uintptr_t checker, uintptr_t context) {
    const uint32_t frame = word(context + 12);
    if (!frame || frame != word(context + 8)) return;
    const uint32_t stored = half(context + 2), current = half(context + 4);
    const bool local_second = byte(context + 1) == 255;
    const uint32_t first_mismatch = word(checker + 12);
    peerlink_journal("MATCH_CHECK_COMPARE frame=%u localValue=%u peerValue=%u equal=%u verdict=%u mismatchCount=%u firstMismatchFrame=%u",
        frame, local_second ? current : stored, local_second ? stored : current,
        unsigned(stored == current), word(context + 16), word(checker + 8), first_mismatch);
    const uintptr_t controller = command_controller();
    if (controller && stored != current && frame == first_mismatch) record_first_differing_players(controller, frame);
    const auto trace = keeper_scene_at(controller, frame);
    if (trace.scene) {
        peerlink_keeper_journal("KEEPER_SCENE_COMPARE scene=%u sceneId=%u frame=%u localValue=%u peerValue=%u "
            "equal=%u verdict=%u mismatchCount=%u firstMismatchFrame=%u samplerTid=%d",
            trace.number, trace.id, frame, local_second ? current : stored, local_second ? stored : current,
            unsigned(stored == current), word(context + 16), word(checker + 8), first_mismatch, gettid());
    }
}

void peerlink_record_scene_agreement(uintptr_t record, uintptr_t eligible_slots, uint32_t result) {
    if (!engine || !eligible_slots || (result != 1 && result != 2)) return;
    // 73202b8 selects one of five records. The reducer at 7320794 reads
    // these 24 eligibility bytes and the corresponding decoded input values.
    const uintptr_t first = engine + 0xa590478;
    constexpr uintptr_t stride = 0xd8;
    if (record < first || record - first >= 5 * stride || (record - first) % stride) return;
    const uintptr_t controller = command_controller();
    const uint32_t frame = controller ? word(controller + 0x30) : 0;
    if (!frame) return;
    uint32_t eligible = 0, one = 0, two = 0;
    for (unsigned slot = 0; slot < 24; ++slot) {
        if (!byte(eligible_slots + slot)) continue;
        const uint32_t bit = 1U << slot;
        eligible |= bit;
        const uint32_t choice = word(record + 0x10 + slot * 4);
        if (choice == 1) one |= bit;
        if (choice == 2) two |= bit;
    }
    peerlink_journal("MATCH_SCENE_AGREEMENT commandFrame=%u channel=%u result=%u eligibleMask=%u choiceOneMask=%u choiceTwoMask=%u",
        frame, unsigned((record - first) / stride), result, eligible, one, two);
}

void peerlink_record_scene_decision(uintptr_t scene, uint32_t channel, uint32_t result) {
    // Only the two decision reads in scene steps 3 and 4 call here. The same
    // result can persist on successive ticks while a presentation is pending.
    struct Seen { uintptr_t scene; uint32_t frame, step, channel, result; };
    static thread_local Seen last{};
    if (!scene || (result != 1 && result != 2)) { last = {}; return; }
    const uintptr_t controller = command_controller();
    const uint32_t frame = controller ? word(controller + 0x30) : 0;
    if (!frame) return;
    const uint32_t step = word(scene + 4);
    const bool repeated = last.scene == scene && last.step == step && last.channel == channel &&
        last.result == result && frame - last.frame <= 1;
    last = {scene, frame, step, channel, result};
    if (!repeated) peerlink_journal("MATCH_SCENE_DECISION commandFrame=%u sceneId=%u step=%u channel=%u result=%u",
        frame, word(scene + 8), step, channel, result);
}

PeerLinkSceneStartSample peerlink_sample_scene_start(uintptr_t scene, uintptr_t transition) {
    // Both probes run inside step 8 of the original scene dispatcher. The
    // stage advances to 10 on return, so the probes do no per-frame polling.
    if (!scene || !transition || word(scene + 4) != 8) return {};
    const uintptr_t controller = command_controller();
    if (!controller) return {};
    PeerLinkSceneStartSample sample;
    sample.command_frame = word(controller + 0x30);
    sample.scene_id = word(scene + 8);
    sample.transition_flag = byte(transition + 0x22e);
    const uintptr_t actor = ptr(scene + 0x280);
    const uintptr_t vtable = actor ? ptr(actor) : 0;
    sample.actor_verified = vtable && ptr(vtable + 0x28) == engine + 0x69d25bc;
    if (sample.actor_verified) {
        sample.actor_mode = word(actor + 0x250);
        sample.start_requested = byte(actor + 0x5f) != 0;
        sample.binding_present = ptr(actor + 0x228) != 0;
        sample.interrupted = byte(actor + 0x60) != 0;
        // Read the existing singleton from 68f9324. Calling its accessor
        // could create it before the actor does.
        const uintptr_t manager = ptr(engine + 0xa531eb0);
        sample.manager_present = manager != 0;
        if (manager) {
            sample.manager_finishes_early = byte(manager + 0x301e) != 0 ||
                (byte(manager + 0x301f) != 0 && word(manager + 0x3010) == 0);
        }
    }
    return sample;
}

void peerlink_record_scene_start(const char *stage, const PeerLinkSceneStartSample &before,
                                 const PeerLinkSceneStartSample &after) {
    if (!before.command_frame || after.command_frame != before.command_frame ||
        after.scene_id != before.scene_id) return;
    peerlink_journal("MATCH_SCENE_START stage=%s commandFrame=%u sceneId=%u "
        "actorVerifiedBefore=%u actorVerifiedAfter=%u actorModeBefore=%u actorModeAfter=%u "
        "startBefore=%u startAfter=%u bindingBefore=%u bindingAfter=%u transitionBefore=%u transitionAfter=%u "
        "interruptedBefore=%u interruptedAfter=%u managerPresentBefore=%u managerPresentAfter=%u "
        "managerFinishesEarlyBefore=%u managerFinishesEarlyAfter=%u",
        stage, before.command_frame, before.scene_id, unsigned(before.actor_verified),
        unsigned(after.actor_verified), before.actor_mode, after.actor_mode, unsigned(before.start_requested),
        unsigned(after.start_requested), unsigned(before.binding_present), unsigned(after.binding_present),
        before.transition_flag, after.transition_flag, unsigned(before.interrupted), unsigned(after.interrupted),
        unsigned(before.manager_present), unsigned(after.manager_present),
        unsigned(before.manager_finishes_early), unsigned(after.manager_finishes_early));
}

void peerlink_record_scene_clock_source(uint32_t scene_id, bool timeline_loaded) {
    const uintptr_t controller = command_controller();
    if (!controller) return;
    peerlink_journal("MATCH_SCENE_CLOCK_SOURCE commandFrame=%u sceneId=%u source=%s",
        word(controller + 0x30), scene_id, timeline_loaded ? "game_timeline" : "fallback_no_timeline");
}

void peerlink_record_ball_park(uintptr_t ball, uintptr_t position, uintptr_t group,
                              uintptr_t transition, uintptr_t flow) {
    if (ptr(group + 0x30) != ball || word(position) != 0 || word(position + 8) != 0x42c80000 ||
        (word(ball + 0x30) == 0 && word(ball + 0x38) == 0x42c80000)) return;
    const uintptr_t controller = command_controller();
    const uint32_t frame = controller ? word(controller + 0x30) : 0;
    if (!frame) return;
    peerlink_journal("MATCH_ACTIVE_BALL_PARK commandFrame=%u flow=%u transitionFlag=%u matchFlowFlag=%u previousXBits=%u previousYBits=%u previousZBits=%u",
        frame, byte(flow + 0x333c), byte(transition + 0x22e), byte(flow + 0x33a8),
        word(ball + 0x30), word(ball + 0x34), word(ball + 0x38));
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
    // Persist the actual comparison before gathering the larger diagnostic
    // snapshot: even a failure in that snapshot cannot erase the cause.
    peerlink_journal("MATCH_CHECK_MISMATCH present=1 compareFrame=%u storedFrame=%u localValue=%u peerValue=%u originUser=%u verdict=%u mismatchCount=%u firstMismatchFrame=%u stopCommandFrame=%u",
        difference.frame, difference.stored_frame, difference.local, difference.peer,
        difference.origin, difference.verdict, difference.count, difference.first_frame, difference.command_frame);
    capture(at_stop, controller);
    difference.published.store(2, std::memory_order_release);
    peerlink_journal("%s", peerlink_match_state_diagnostics().c_str());
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
