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
    errno = saved_errno;
#else
    (void)base;
#endif
}
