#include "match_state_diagnostics.h"
#include "diagnostic_journal.h"
#include "executable_patch.h"
#include "fallback_cut_clock.h"

#include <array>
#include <cerrno>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

#if defined(__aarch64__)
namespace {
constexpr uintptr_t kCompareCall = 0x70539c4;
constexpr uintptr_t kChecksumCall = 0x7053bb8;
constexpr uintptr_t kBallCall = 0x6af6d0c;
uintptr_t image_base = 0;
bool attempted = false;

struct SceneClockObservation {
    uintptr_t actor = 0;
    uint32_t scene_id = 0;
    unsigned observed_sources = 0;
};
thread_local SceneClockObservation scene_clock;

template <class T> T native(uintptr_t offset) { return reinterpret_cast<T>(image_base + offset); }

uint64_t observe_checksum(uintptr_t first, uintptr_t second, uint32_t *frame) {
    const auto original = native<uint64_t (*)(uintptr_t, uintptr_t, uint32_t *)>(0x7053d8c);
    const uint64_t result = original(first, second, frame);
    const int saved_errno = errno;
    if (frame && *frame) peerlink_record_check_frame(*frame, static_cast<uint16_t>(result));
    errno = saved_errno;
    return result;
}

uint64_t observe_comparison(uintptr_t checker, uintptr_t context) {
    const uint64_t result = native<uint64_t (*)(uintptr_t, uintptr_t)>(0x7053ea0)(checker, context);
    const int saved_errno = errno;
    peerlink_record_check_comparison(checker, context);
    errno = saved_errno;
    return result;
}

extern "C" __attribute__((used, noinline))
void peerlink_observe_ball_placement_impl(uintptr_t ball, uintptr_t position, uintptr_t orientation,
                                         uintptr_t group, uintptr_t transition, uintptr_t flow) {
    const int saved_errno = errno;
    if (ball && position && group && transition && flow)
        peerlink_record_ball_park(ball, position, group, transition, flow);
    errno = saved_errno;
    native<void (*)(uintptr_t, uintptr_t, uintptr_t)>(0x6f57860)(ball, position, orientation);
}

// At the pinned call site, the original routine holds the group and the two
// condition sources in x19, x27 and sp+0x20. Preserve its original x0-x2.
__attribute__((naked)) void observe_ball_placement() {
    __asm__ volatile("mov x3, x19\nmov x4, x27\nldr x5, [sp, #0x20]\nb peerlink_observe_ball_placement_impl");
}

// These call sites validate the final participant decision. Capture the
// reducer's own eligible slots before its stack goes away, including channel 4.
extern "C" __attribute__((used, noinline))
uint32_t peerlink_observe_scene_agreement_impl(uint32_t result, uintptr_t record, uintptr_t slots) {
    const uint32_t valid = native<uint32_t (*)(uint32_t)>(0x7320208)(result);
    if (valid) {
        const int saved = errno;
        peerlink_record_scene_agreement(record, slots, result);
        errno = saved;
    }
    return valid;
}

__attribute__((naked)) void observe_scene_agreement() {
    __asm__ volatile("mov x1, x19\nmov x2, x22\nb peerlink_observe_scene_agreement_impl");
}

extern "C" __attribute__((used, noinline))
uint32_t peerlink_observe_scene_decision_impl(uint32_t channel, uintptr_t scene) {
    const uint32_t result = native<uint32_t (*)(uint32_t)>(0x73203a8)(channel);
    const int saved = errno;
    peerlink_record_scene_decision(scene, channel, result);
    errno = saved;
    return result;
}

__attribute__((naked)) void observe_scene_decision() {
    __asm__ volatile("mov x1, x20\nb peerlink_observe_scene_decision_impl");
}

extern "C" __attribute__((used, noinline))
void peerlink_observe_scene_start_impl(uintptr_t actor, uintptr_t scene, uintptr_t transition) {
    const auto before = peerlink_sample_scene_start(scene, transition);
    const uint32_t family = before.scene_id >> 16;
    scene_clock = before.actor_verified && before.command_frame &&
        (family == 3 || (family == 2 && peerlink_keeper_journal_active()))
        ? SceneClockObservation{actor, before.scene_id} : SceneClockObservation{};
    native<void (*)(uintptr_t)>(0x6a46c84)(actor);
    const int saved_errno = errno;
    peerlink_record_scene_start("play", before, peerlink_sample_scene_start(scene, transition));
    errno = saved_errno;
}

extern "C" __attribute__((used, noinline))
void peerlink_observe_scene_targets_impl(uintptr_t context, uintptr_t transition, uintptr_t scene) {
    const auto before = peerlink_sample_scene_start(scene, transition);
    native<void (*)(uintptr_t, uintptr_t)>(0x6fe7494)(context, transition);
    const int saved_errno = errno;
    peerlink_record_scene_start("targets", before, peerlink_sample_scene_start(scene, transition));
    errno = saved_errno;
}

// At 6fe8610 the original actor is in x0, the scene in x20, and the
// transition in x19. At 6fe8630 the original context and transition are
// already in x0-x1. The actor's original virtual update runs between them.
__attribute__((naked)) void observe_scene_start() {
    __asm__ volatile("mov x1, x20\nmov x2, x19\nb peerlink_observe_scene_start_impl");
}

__attribute__((naked)) void observe_scene_targets() {
    __asm__ volatile("mov x2, x20\nb peerlink_observe_scene_targets_impl");
}

// 6fe6300 calls the scene dispatcher with these six pointers. Its caller
// consumes only bit zero of the result and restores its own working state.
uint32_t observe_keeper_scene(uintptr_t scene, uintptr_t context, uintptr_t transition,
                              uintptr_t resources, uintptr_t update, uintptr_t message) {
    const int before_errno = errno;
    const auto tick = peerlink_keeper_scene_enter(scene, context, transition);
    errno = before_errno;
    using Dispatch = uint32_t (*)(uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t, uintptr_t);
    const uint32_t result = native<Dispatch>(0x6fe83ec)(scene, context, transition, resources, update, message);
    const int after_errno = errno;
    peerlink_keeper_scene_leave(tick, scene, context, transition, (result & 1) != 0);
    errno = after_errno;
    return result;
}

uint32_t observe_scene_clock_source(uintptr_t timeline_slot) {
    // The original return value chooses the loaded timeline or its fallback.
    // The timer's owning scene actor is stored at +8, before this slot (+0x18).
    const uint32_t loaded = native<uint32_t (*)(uintptr_t)>(0x69f2b64)(timeline_slot);
    if (scene_clock.actor && *reinterpret_cast<const uintptr_t *>(timeline_slot - 0x10) == scene_clock.actor) {
        peerlink_keeper_clock_sample(timeline_slot - 0x18, loaded != 0);
        const unsigned source = loaded ? 2 : 1;
        if (!(scene_clock.observed_sources & source)) {
            scene_clock.observed_sources |= source;
            peerlink_record_scene_clock_source(scene_clock.scene_id, loaded != 0);
        }
    }
    return loaded;
}

struct Thunk {
    uint32_t load = 0x58000050;   // ldr x16, [pc, #8]
    uint32_t branch = 0xd61f0200; // br x16
    uintptr_t target;
};
static_assert(sizeof(Thunk) == 16);

uint32_t branch_link(uintptr_t from, uintptr_t to) {
    const auto distance = static_cast<intptr_t>(to) - static_cast<intptr_t>(from);
    constexpr intptr_t range = 1 << 27;
    if (distance % 4 || distance < -range || distance >= range) return 0;
    return 0x94000000 | (static_cast<uint32_t>(distance / 4) & 0x03ffffff);
}

template<class T> T &cut_field(uintptr_t cut, uintptr_t offset) {
    return *reinterpret_cast<T *>(cut + offset);
}

extern "C" __attribute__((used, noinline, visibility("hidden")))
void peerlink_apply_fallback_cut_clock(uintptr_t cut, uint32_t ready, float elapsed, float relative) {
    static thread_local peerlink::FallbackCutClocks clocks;
    float &position = cut_field<float>(cut, 0xb4);
    float &relative_position = cut_field<float>(cut, 0xd8);
    float &seek = cut_field<float>(cut, 0xc4);
    if (ready && cut_field<uint32_t>(cut, 0xa8) == 5 && !cut_field<uintptr_t>(cut, 0x18)) {
        const peerlink::CutClockSample sample{cut, position, relative_position, seek,
            cut_field<float>(cut, 0xb8), cut_field<float>(cut, 0xb08), cut_field<float>(cut, 0xb14)};
        float corrected;
        uint32_t ticks;
        if (clocks.advance(sample, corrected, ticks)) {
            if (sample.elapsed < sample.duration && corrected == sample.duration) {
                peerlink_journal("MATCH_FALLBACK_CLOCK_LIMIT ticks=%u tickRate=%.9g speed=%.9g original=%.9g corrected=%.9g duration=%.9g",
                    ticks, double(sample.tick_rate), double(sample.speed), double(elapsed),
                    double(corrected), double(sample.duration));
            }
            elapsed = relative = corrected;
        }
    } else {
        clocks.forget(cut);
    }
    position = elapsed;
    relative_position = relative;
    seek = 0;
}

// The pinned update block holds the cut in x19, the playback flag in w8,
// and the two original store values in s2 and s0 respectively.
__attribute__((naked)) void apply_fallback_cut_clock() {
    __asm__ volatile("mov x0, x19\nmov w1, w8\nfmov s1, s0\nfmov s0, s2\nb peerlink_apply_fallback_cut_clock");
}

bool patch_fallback_cut_clock(uintptr_t base, peerlink::CodePatchFailure &failure) {
    constexpr uintptr_t start = 0x69f4930;
    constexpr uintptr_t stores = 0x69f493c;
    constexpr std::array<uint32_t, 8> original{
        0xbd40da60, 0x1e212902, 0x1e202820, 0xbd00b662, 0xbd00da60, 0xb900c67f, 0x35fff6c8, 0x1400001f
    };
    if (std::memcmp(reinterpret_cast<const void *>(base + start), original.data(), sizeof(original))) {
        failure = {"instruction_check", 0, 0};
        return false;
    }
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size < 4096 || page_size > 65536 || (page_size & (page_size - 1))) {
        failure = {"page_size", EINVAL, 0};
        return false;
    }
    constexpr uintptr_t range = 1 << 27;
    const uintptr_t site = base + stores;
    void *page = peerlink::reserve_writable_page(site >= range ? site - range : 0x10000,
                                                site + range, page_size);
    if (page == MAP_FAILED) {
        failure = {"clock_mapping", errno, static_cast<size_t>(page_size)};
        return false;
    }
    const Thunk thunk{0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&apply_fallback_cut_clock)};
    std::memcpy(page, &thunk, sizeof(thunk));
    const uint32_t call = branch_link(site, reinterpret_cast<uintptr_t>(page));
    if (!call) {
        failure = {"clock_branch_range", 0, static_cast<size_t>(page_size)};
    } else if (mprotect(page, page_size, PROT_READ | PROT_EXEC) != 0) {
        failure = {"clock_execute", errno, static_cast<size_t>(page_size)};
    } else {
        __builtin___clear_cache(static_cast<char *>(page), static_cast<char *>(page) + sizeof(thunk));
        // The helper replaces the three stores. Reload the sole live volatile
        // register before the original branch and subsequent native calls.
        const std::array<uint32_t, 3> patch{call, 0xd503201f, 0x396cfa68}; // nop; ldrb w8, [x19, #0xb3e]
        if (peerlink::replace_executable_bytes(site, patch.data(), sizeof(patch), &failure)) return true;
    }
    munmap(page, page_size);
    return false;
}

bool original_call(uintptr_t at, uintptr_t target) {
    return *reinterpret_cast<const uint32_t *>(image_base + at) == branch_link(at, target);
}

// All calls in a group target one original function and occupy at most two
// neighboring pages. Replace the group's instruction span in one operation.
template <size_t Words, size_t N>
bool observe_scene_calls(const std::array<uintptr_t, N> &calls, uintptr_t original, uintptr_t thunk,
                         peerlink::CodePatchFailure &failure) {
    std::array<uint32_t, Words> words;
    if ((calls.back() - calls.front()) / sizeof(uint32_t) + 1 != Words) return false;
    const uintptr_t start = image_base + calls.front();
    std::memcpy(words.data(), reinterpret_cast<const void *>(start), sizeof(words));
    for (const auto call : calls) {
        uint32_t replacement = branch_link(image_base + call, thunk);
        if (!replacement || !original_call(call, original)) return false;
        words[(call - calls.front()) / sizeof(uint32_t)] = replacement;
    }
    return peerlink::replace_executable_bytes(start, words.data(), sizeof(words), &failure);
}

void install_scene_observers(uintptr_t base, long page_size) {
    constexpr std::array<uintptr_t, 5> agreement{0x7320ea8, 0x7320f44, 0x7320fe0, 0x732107c, 0x7321118};
    constexpr std::array<uintptr_t, 2> decision{0x6fe85b8, 0x6fe8804};
    constexpr std::array<uintptr_t, 1> start{0x6fe8610};
    constexpr std::array<uintptr_t, 1> targets{0x6fe8630};
    constexpr std::array<uintptr_t, 1> clock_source{0x69f475c};
    constexpr std::array<uintptr_t, 1> keeper_scene{0x6fe6300};
    constexpr uintptr_t range = 1U << 27;
    bool agreement_installed = false, decision_installed = false;
    bool start_installed = false, targets_installed = false;
    bool clock_source_installed = false;
    bool keeper_scene_installed = false;
    peerlink::CodePatchFailure agreement_error{"instruction_check", 0, 0};
    peerlink::CodePatchFailure decision_error{"instruction_check", 0, 0};
    peerlink::CodePatchFailure start_error{"instruction_check", 0, 0};
    peerlink::CodePatchFailure targets_error{"instruction_check", 0, 0};
    peerlink::CodePatchFailure clock_source_error{"instruction_check", 0, 0};
    peerlink::CodePatchFailure keeper_error{"instruction_check", 0, 0};
    if (page_size >= 4096 && page_size <= 65536) {
        const uintptr_t last = base + agreement.back();
        void *page = peerlink::reserve_writable_page(last >= range ? last - range : 0x10000,
            base + clock_source.front() + range, static_cast<size_t>(page_size));
        if (page != MAP_FAILED) {
            const std::array<Thunk, 6> thunks{{
                {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_scene_agreement)},
                {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_scene_decision)},
                {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_scene_start)},
                {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_scene_targets)},
                {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_scene_clock_source)},
                {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_keeper_scene)},
            }};
            std::memcpy(page, thunks.data(), sizeof(thunks));
            if (mprotect(page, page_size, PROT_READ | PROT_EXEC) == 0) {
                __builtin___clear_cache(static_cast<char *>(page), static_cast<char *>(page) + sizeof(thunks));
                const uintptr_t entry = reinterpret_cast<uintptr_t>(page);
                peerlink_match_state_begin(base);
                agreement_installed = observe_scene_calls<(agreement.back() - agreement.front()) / 4 + 1>(
                    agreement, 0x7320208, entry, agreement_error);
                decision_installed = observe_scene_calls<(decision.back() - decision.front()) / 4 + 1>(
                    decision, 0x73203a8, entry + sizeof(Thunk), decision_error);
                start_installed = observe_scene_calls<1>(start, 0x6a46c84, entry + 2 * sizeof(Thunk), start_error);
                targets_installed = observe_scene_calls<1>(targets, 0x6fe7494, entry + 3 * sizeof(Thunk), targets_error);
                clock_source_installed = observe_scene_calls<1>(clock_source, 0x69f2b64,
                    entry + 4 * sizeof(Thunk), clock_source_error);
                if (peerlink_keeper_journal_active())
                    keeper_scene_installed = observe_scene_calls<1>(keeper_scene, 0x6fe83ec,
                        entry + 5 * sizeof(Thunk), keeper_error);
                else keeper_error = {"journal_unavailable", 0, 0};
            } else {
                agreement_error = decision_error = start_error = targets_error = clock_source_error = keeper_error =
                    {"observer_execute", errno, static_cast<size_t>(page_size)};
            }
            if (!agreement_installed && !decision_installed && !start_installed && !targets_installed &&
                !clock_source_installed && !keeper_scene_installed)
                munmap(page, page_size);
        } else {
            agreement_error = decision_error = start_error = targets_error = clock_source_error = keeper_error =
                {"observer_mapping", errno, static_cast<size_t>(page_size)};
        }
    }
    peerlink_journal("MATCH_SCENE_OBSERVERS agreement=%u decision=%u agreementOperation=%s agreementErrno=%d decisionOperation=%s decisionErrno=%d",
        unsigned(agreement_installed), unsigned(decision_installed), agreement_error.operation, agreement_error.error,
        decision_error.operation, decision_error.error);
    peerlink_journal("MATCH_SCENE_START_OBSERVERS play=%u targets=%u playOperation=%s playErrno=%d targetsOperation=%s targetsErrno=%d",
        unsigned(start_installed), unsigned(targets_installed), start_error.operation, start_error.error,
        targets_error.operation, targets_error.error);
    peerlink_journal("MATCH_SCENE_CLOCK_OBSERVER installed=%u operation=%s errno=%d",
        unsigned(clock_source_installed), clock_source_error.operation, clock_source_error.error);
    peerlink_journal("KEEPER_SCENE_OBSERVER installed=%u operation=%s errno=%d",
        unsigned(keeper_scene_installed), keeper_error.operation, keeper_error.error);
    peerlink_keeper_journal("KEEPER_SCENE_OBSERVER schema=1 installed=%u clockObserver=%u operation=%s errno=%d",
        unsigned(keeper_scene_installed), unsigned(clock_source_installed), keeper_error.operation, keeper_error.error);
}
} // namespace
#endif

void peerlink::install_fallback_cut_clock(uintptr_t base) {
#if defined(__aarch64__)
    const int saved_errno = errno;
    CodePatchFailure failure;
    const bool installed = patch_fallback_cut_clock(base, failure);
    peerlink_journal("MATCH_FALLBACK_CLOCK installed=%u operation=%s errno=%d",
        unsigned(installed), failure.operation, failure.error);
    errno = saved_errno;
#else
    (void)base;
#endif
}

void peerlink_observe_match_frames(uintptr_t base) {
#if defined(__aarch64__)
    if (attempted || !base) return;
    attempted = true;
    image_base = base;
    const int saved_errno = errno;
    const long page_size = sysconf(_SC_PAGESIZE);
    const bool check_verified = original_call(kCompareCall, 0x7053ea0) && original_call(kChecksumCall, 0x7053d8c);
    const bool ball_verified = original_call(kBallCall, 0x6f57860);
    bool checks_installed = false, ball_installed = false;
    peerlink::CodePatchFailure failure{"instruction_check", 0, 0};
    const std::array<Thunk, 3> thunks{{
        {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_comparison)},
        {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_checksum)},
        {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_ball_placement)},
    }};
    if ((check_verified || ball_verified) && page_size >= 4096 && page_size <= 65536) {
        constexpr uintptr_t range = 1 << 27;
        const uintptr_t first = base + kBallCall;
        const uintptr_t last = base + kChecksumCall;
        const uintptr_t lower = last >= range ? last - range : 0x10000;
        void *page = peerlink::reserve_writable_page(lower, first + range, page_size);
        if (page == MAP_FAILED) {
            failure = {"observer_mapping", errno, static_cast<size_t>(page_size)};
        } else {
            const uintptr_t entry = reinterpret_cast<uintptr_t>(page);
            const uint32_t compare = branch_link(base + kCompareCall, entry);
            const uint32_t checksum = branch_link(base + kChecksumCall, entry + sizeof(Thunk));
            const uint32_t ball = branch_link(base + kBallCall, entry + 2 * sizeof(Thunk));
            const bool checks_in_range = check_verified && compare && checksum;
            const bool ball_in_range = ball_verified && ball;
            if (checks_in_range == check_verified && ball_in_range == ball_verified) {
                std::memcpy(page, thunks.data(), sizeof(thunks));
                if (mprotect(page, page_size, PROT_READ | PROT_EXEC) == 0) {
                    __builtin___clear_cache(static_cast<char *>(page), static_cast<char *>(page) + sizeof(thunks));
                    peerlink_match_state_begin(base);
                    if (checks_in_range) {
                        constexpr size_t count = (kChecksumCall - kCompareCall) / sizeof(uint32_t) + 1;
                        std::array<uint32_t, count> patch;
                        std::memcpy(patch.data(), reinterpret_cast<const void *>(base + kCompareCall), sizeof(patch));
                        patch.front() = compare;
                        patch.back() = checksum;
                        checks_installed = peerlink::replace_executable_bytes(base + kCompareCall, patch.data(),
                                                                             sizeof(patch), &failure);
                    }
                    if (ball_in_range) {
                        peerlink::CodePatchFailure ball_failure;
                        ball_installed = peerlink::replace_executable_bytes(base + kBallCall, &ball, sizeof(ball),
                                                                             &ball_failure);
                        if (!ball_installed || !check_verified) failure = ball_failure;
                    }
                } else {
                    failure = {"observer_execute", errno, static_cast<size_t>(page_size)};
                }
            } else {
                failure = {"observer_branch_range", 0, static_cast<size_t>(page_size)};
            }
            // Installed branches own the thunk mapping.
            if (!checks_installed && !ball_installed) munmap(page, page_size);
        }
    }
    peerlink_journal("MATCH_FRAME_OBSERVERS checksum=%u ballPark=%u checksumCallSitesVerified=%u ballCallSiteVerified=%u operation=%s errno=%d",
        unsigned(checks_installed), unsigned(ball_installed), unsigned(check_verified), unsigned(ball_verified),
        failure.operation, failure.error);
    install_scene_observers(base, page_size);
    errno = saved_errno;
#else
    (void)base;
#endif
}
