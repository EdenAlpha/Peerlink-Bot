#include "match_state_diagnostics.h"
#include "diagnostic_journal.h"
#include "executable_patch.h"

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
    constexpr uintptr_t range = 1U << 27;
    bool agreement_installed = false, decision_installed = false;
    peerlink::CodePatchFailure agreement_error{"instruction_check", 0, 0};
    peerlink::CodePatchFailure decision_error{"instruction_check", 0, 0};
    if (page_size >= 4096 && page_size <= 65536) {
        const uintptr_t last = base + agreement.back();
        void *page = peerlink::reserve_writable_page(last >= range ? last - range : 0x10000,
            base + decision.front() + range, static_cast<size_t>(page_size));
        if (page != MAP_FAILED) {
            const std::array<Thunk, 2> thunks{{
                {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_scene_agreement)},
                {0x58000050, 0xd61f0200, reinterpret_cast<uintptr_t>(&observe_scene_decision)},
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
            } else {
                agreement_error = decision_error = {"observer_execute", errno, static_cast<size_t>(page_size)};
            }
            if (!agreement_installed && !decision_installed) munmap(page, page_size);
        } else {
            agreement_error = decision_error = {"observer_mapping", errno, static_cast<size_t>(page_size)};
        }
    }
    peerlink_journal("MATCH_SCENE_OBSERVERS agreement=%u decision=%u agreementOperation=%s agreementErrno=%d decisionOperation=%s decisionErrno=%d",
        unsigned(agreement_installed), unsigned(decision_installed), agreement_error.operation, agreement_error.error,
        decision_error.operation, decision_error.error);
}
} // namespace
#endif

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
