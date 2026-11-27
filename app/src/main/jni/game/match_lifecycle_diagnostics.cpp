#include "match_lifecycle_diagnostics.h"
#include "diagnostic_journal.h"

#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstring>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

namespace {

// Slots and original implementations from the pinned ARM64 11.1.1 ELF.
// Each observer returns the original result and leaves its errno intact.
constexpr uintptr_t kPhaseRequest = 0x76803e4;
constexpr uintptr_t kBinaryPhaseRequest = 0x7680c70;
constexpr uintptr_t kPhaseReply = 0x7683c38;
constexpr uintptr_t kBinaryPhaseReply = 0x7683ce0;
constexpr uintptr_t kStartGameRequest = 0x77cce98;
constexpr uintptr_t kBinaryStartGameRequest = 0x77cd1e8;
constexpr uintptr_t kStartGameReply = 0x77ce750;
constexpr uintptr_t kBinaryStartGameReply = 0x77cf3d0;
constexpr uintptr_t kMatchResult = 0x76e2ebc;
constexpr uintptr_t kBinaryMatchResult = 0x76e3020;
constexpr uintptr_t kConfigureResultPoll = 0x76e246c;
constexpr uintptr_t kResultPollComplete = 0x7514e10;
constexpr uintptr_t kScoreReply = 0x766fb6c;
constexpr uintptr_t kFoulReply = 0x766cd4c;
constexpr uintptr_t kPrepareSetResult = 0x7794de8;
constexpr uintptr_t kPrepareAlternateResult = 0x7757cf4;
constexpr uintptr_t kSetResultRequest = 0x7795314;
constexpr uintptr_t kBinarySetResultRequest = 0x7796fc4;
constexpr uintptr_t kSetResultReply = 0x77a10ac;
constexpr uintptr_t kBinarySetResultReply = 0x77a1154;
constexpr uintptr_t kResultWatcher = 0x7b4b21c;
constexpr uintptr_t kPlatformSessionUpdate = 0x7b2c94c;
constexpr uintptr_t kSessionState = 0x7d3aadc;
constexpr uintptr_t kChannelState = 0x7d3abf4;
constexpr uintptr_t kPeerUpdate = 0x7c8a280;
constexpr uintptr_t kStreamUpdate = 0x7c23884;
uintptr_t image_base = 0;
std::atomic<uintptr_t> observed_poll_completion{0};
std::atomic<bool> match_result_available{false};

template <typename Value>
Value field(uintptr_t object, size_t offset) {
    return *reinterpret_cast<const Value *>(object + offset);
}

uint64_t call(uintptr_t offset, uintptr_t object) {
    return reinterpret_cast<uint64_t (*)(uintptr_t)>(image_base + offset)(object);
}

// These fields contain phase or result enums. Reject anything that doesn't
// fit that format before handing it to the journal.
const char *enum_string(uintptr_t address, char (&out)[41]) {
    const uint8_t header = field<uint8_t>(address, 0);
    const bool long_string = (header & 1) != 0;
    const size_t size = long_string ? field<size_t>(address, 8) : header >> 1;
    if (!size) return "empty";
    if (size >= sizeof(out)) return "unrecognized";
    const char *data = long_string ? field<const char *>(address, 16) :
                                    reinterpret_cast<const char *>(address + 1);
    if (!data) return "unrecognized";
    for (size_t i = 0; i < size; ++i) {
        const char c = data[i];
        if (!(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9') && c != '_') return "unrecognized";
        out[i] = c;
    }
    out[size] = '\0';
    return out;
}

template <uintptr_t Offset>
uint64_t observe_phase_request(uintptr_t command) {
    char phase[41];
    peerlink_journal("MATCH_PHASE_REQUEST phase=%s gtimer=%u serializer=%s",
        enum_string(command + 0x1f0, phase), field<uint32_t>(command, 0x208),
        Offset == kPhaseRequest ? "json" : "binary");
    return call(Offset, command);
}

template <uintptr_t Offset>
uint64_t observe_phase_reply(uintptr_t command) {
    const uint64_t result = call(Offset, command);
    char status[41];
    peerlink_journal("MATCH_PHASE_REPLY parser=%s result=%s",
        Offset == kPhaseReply ? "json" : "binary", enum_string(command + 0x1f0, status));
    return result;
}

template <uintptr_t Offset>
uint64_t observe_start_game_request(uintptr_t command) {
    const uint64_t result = call(Offset, command);
    peerlink_journal("MATCH_START_REQUEST serializer=%s serialized=%u",
        Offset == kStartGameRequest ? "json" : "binary", unsigned(result & 1));
    return result;
}

template <uintptr_t Offset>
uint64_t observe_start_game_reply(uintptr_t command) {
    const uint64_t result = call(Offset, command);
    char status[41];
    peerlink_journal("MATCH_START_REPLY parser=%s parsed=%u result=%s",
        Offset == kStartGameReply ? "json" : "binary", unsigned(result & 1),
        (result & 1) ? enum_string(command + 0x1f0, status) : "unavailable");
    return result;
}

template <uintptr_t Offset>
uint64_t observe_match_result(uintptr_t command) {
    const uint64_t result = call(Offset, command);
    char status[41], match_result[41], end_reason[41], abnormal_reason[41];
    const char *availability = enum_string(command + 0x960, match_result);
    const bool ready = strcmp(availability, "YES") == 0;
    const char *reply_status = enum_string(command + 0x1f0, status);
    if ((result & 1) && ready && strcmp(reply_status, "NOERR") == 0)
        match_result_available.store(true, std::memory_order_relaxed);
    peerlink_journal("MATCH_RESULT_REPLY result=%s getMatchResult=%s endReason=%s abnormalReason=%s parser=%s parsed=%u",
        reply_status, availability,
        ready ? enum_string(command + 0x978, end_reason) : "unavailable",
        ready ? enum_string(command + 0x990, abnormal_reason) : "unavailable",
        Offset == kMatchResult ? "json" : "binary", unsigned(result & 1));
    return result;
}

uint64_t observe_configure_result_poll(uintptr_t task) {
    const uint64_t result = call(kConfigureResultPoll, task);
    observed_poll_completion.store(0, std::memory_order_relaxed);
    // At 0x76e247c the original obtains its request via task+0x318,
    // then writes the room's game ID there. Log presence only.
    const uintptr_t request = field<uintptr_t>(task, 0x318);
    peerlink_journal("MATCH_RESULT_POLL configured=%u hasGameId=%u",
        unsigned(result & 1), unsigned(request && field<uint64_t>(request, 0x1e8) != 0));
    return result;
}

uint64_t observe_result_poll_complete(uintptr_t task) {
    const uint64_t result = call(kResultPollComplete, task);
    if ((result & 1) && observed_poll_completion.exchange(task, std::memory_order_relaxed) != task) {
        // At 0x7b4b818 the result watcher checks this flag before arming
        // its command-error deadline. A failed poll may never reach a parser.
        peerlink_journal("MATCH_RESULT_POLL_COMPLETE watcherErrorFlag=%u", unsigned(field<uint8_t>(task, 0x88)));
    }
    return result;
}

template <uintptr_t Offset>
uint64_t observe_prepare_set_result(uintptr_t task) {
    const uint64_t result = call(Offset, task);
    if (result & 1) {
        const uintptr_t request = field<uintptr_t>(task, 0x318);
        if (request) {
            // The event at task+0x410 supplies the reason and problem flag
            // to the MatchResult builder. It may translate those values.
            char reason[41], problem[41];
            peerlink_journal("MATCH_REPORT_PREPARED task=%s eventReason=%u eventProblem=%u abnormalReason=%s isProblem=%s",
                Offset == kPrepareSetResult ? "set_game_result" : "alternate_result",
                field<uint32_t>(task, 0x490), unsigned(field<uint8_t>(task, 0x498)),
                enum_string(request + 0x1f0, reason), enum_string(request + 0x208, problem));
        }
    }
    return result;
}

template <uintptr_t Offset>
uint64_t observe_set_result_request(uintptr_t command) {
    const uint64_t result = call(Offset, command);
    if (result & 1) {
        // The JSON and binary serializers read these same request fields.
        // Record the match counters and enums without logging IDs or payloads.
        char reason[41], problem[41];
        peerlink_journal("MATCH_REPORT_SERIALIZED serializer=%s abnormalReason=%s isProblem=%s matchTimeFrame=%u matchTimeTick=%u comStopInfo=%u entryCheckData=%u",
            Offset == kSetResultRequest ? "json" : "binary",
            enum_string(command + 0x1f0, reason), enum_string(command + 0x208, problem),
            field<uint32_t>(command, 0x3e8), field<uint32_t>(command, 0x3ec),
            field<uint32_t>(command, 0x3f0), field<uint32_t>(command, 0x3f4));
    }
    return result;
}

template <uintptr_t Offset>
uint64_t observe_match_event_reply(uintptr_t command) {
    const uint64_t result = call(Offset, command);
    const char *event = Offset == kScoreReply ? "score" : "foul";
    char status[41];
    peerlink_journal("MATCH_EVENT_REPLY event=%s result=%s", event, enum_string(command + 0x1f0, status));
    return result;
}

template <uintptr_t Offset>
uint64_t observe_set_result_reply(uintptr_t command) {
    const uint64_t result = call(Offset, command);
    char status[41];
    peerlink_journal("MATCH_REPORT_REPLY parser=%s parsed=%u result=%s",
        Offset == kSetResultReply ? "json" : "binary", unsigned(result & 1),
        (result & 1) ? enum_string(command + 0x1f0, status) : "unavailable");
    return result;
}

uint64_t observe_result_watcher(uintptr_t task) {
    const auto before = field<uint32_t>(task, 0x88);
    const auto polling_before = field<uint8_t>(task, 0xb2);
    const auto poll_error_before = field<uint64_t>(task, 0x90);
    const auto end_wait_before = field<uint64_t>(task, 0x98);
    const uint64_t result = call(kResultWatcher, task);
    const auto after = field<uint32_t>(task, 0x88);
    const auto polling_after = field<uint8_t>(task, 0xb2);
    const auto poll_error_after = field<uint64_t>(task, 0x90);
    const auto end_wait_after = field<uint64_t>(task, 0x98);
    if (poll_error_before != poll_error_after || end_wait_before != end_wait_after ||
        (before == 1 && after == 2)) {
        // 0x2f595e8 reads CLOCK_BOOTTIME. Its millisecond deadlines at
        // 0x7b4b860 and 0x7b4b3f4 govern distinct routes into state 2.
        const int saved_errno = errno;
        timespec time{};
        const bool has_time = clock_gettime(CLOCK_BOOTTIME, &time) == 0;
        const auto now = static_cast<unsigned long long>(time.tv_sec) * 1000 + time.tv_nsec / 1000000;
        peerlink_journal("MATCH_RESULT_DEADLINES from=%u to=%u bootMs=%llu pollErrorBeforeMs=%llu pollErrorAfterMs=%llu endWaitBeforeMs=%llu endWaitAfterMs=%llu",
            before, after, has_time ? now : 0, static_cast<unsigned long long>(poll_error_before),
            static_cast<unsigned long long>(poll_error_after), static_cast<unsigned long long>(end_wait_before),
            static_cast<unsigned long long>(end_wait_after));
        errno = saved_errno;
    }
    if (before != after || polling_before != polling_after)
        peerlink_journal("MATCH_RESULT_WATCHER from=%u to=%u pollBefore=%u pollAfter=%u",
            before, after, unsigned(polling_before), unsigned(polling_after));
    return result;
}

uint64_t observe_platform_session_update(uintptr_t task) {
    const auto before = field<uint32_t>(task, 0x70);
    const uint64_t result = call(kPlatformSessionUpdate, task);
    const auto after = field<uint32_t>(task, 0x70);
    if (before != after)
        peerlink_journal("MATCH_PLATFORM_SESSION from=%u to=%u peerReady=%u",
            before, after, unsigned(before == 0 && (after == 1 || after == 3)));
    return result;
}

std::atomic<uint32_t> last_session{UINT32_MAX};
std::atomic<uint32_t> last_channel14{UINT32_MAX}, last_channel15{UINT32_MAX};

uint32_t observe_session(uintptr_t strategy, uintptr_t reference) {
    const uint32_t result = reinterpret_cast<uint32_t (*)(uintptr_t, uintptr_t)>(image_base + kSessionState)(strategy, reference);
    const uint32_t previous = last_session.exchange(result, std::memory_order_relaxed);
    if (previous != result) peerlink_journal("MATCH_TRANSPORT_SESSION from=%u to=%u", previous, result);
    return result;
}

uint32_t observe_channel(uintptr_t strategy, uintptr_t reference, uint32_t channel) {
    const uint32_t result = reinterpret_cast<uint32_t (*)(uintptr_t, uintptr_t, uint32_t)>(image_base + kChannelState)(strategy, reference, channel);
    if (channel == 14 || channel == 15) {
        auto &previous_state = channel == 14 ? last_channel14 : last_channel15;
        const uint32_t previous = previous_state.exchange(result, std::memory_order_relaxed);
        if (previous != result) peerlink_journal("MATCH_TRANSPORT_CHANNEL channel=%u from=%u to=%u", channel, previous, result);
    }
    return result;
}

template <uintptr_t Offset, size_t PeerCount>
uint64_t observe_peer_update(uintptr_t peer) {
    const auto before = field<uint32_t>(peer, 8);
    const auto count = field<size_t>(peer, PeerCount);
    const uint64_t result = call(Offset, peer);
    const auto after = field<uint32_t>(peer, 8);
    if (before != after) peerlink_journal("MATCH_TRANSPORT_PEER type=%s from=%u to=%u peersBefore=%zu peersAfter=%zu",
        Offset == kPeerUpdate ? "mesh" : "stream", before, after, count, field<size_t>(peer, PeerCount));
    return result;
}

bool install_slot(uintptr_t slot_offset, uintptr_t original, uintptr_t observer, size_t page_size) {
    auto *slot = reinterpret_cast<uintptr_t *>(image_base + slot_offset);
    const uintptr_t expected = image_base + original;
    if (__atomic_load_n(slot, __ATOMIC_ACQUIRE) != expected) return false;
    void *page = reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(slot) & ~(page_size - 1));
    if (mprotect(page, page_size, PROT_READ | PROT_WRITE) != 0) return false;
    __atomic_store_n(slot, observer, __ATOMIC_RELEASE);
    if (mprotect(page, page_size, PROT_READ) == 0) return true;
    __atomic_store_n(slot, expected, __ATOMIC_RELEASE);
    mprotect(page, page_size, PROT_READ);
    return false;
}

} // namespace

bool peerlink_match_result_available() {
    return match_result_available.load(std::memory_order_relaxed);
}

void peerlink_observe_match_lifecycle(uintptr_t base) {
    if (!base || image_base) return;
    const int saved_errno = errno;
    const long page_size = sysconf(_SC_PAGESIZE);
    if (page_size < 4096 || page_size > 65536 || (page_size & (page_size - 1))) {
        peerlink_journal("MATCH_LIFECYCLE_OBSERVERS unavailable=page_size");
        errno = saved_errno;
        return;
    }
    image_base = base;
    const struct { uintptr_t slot, original, observer; const char *name; } slots[] = {
        {0x98d7f28, kPhaseRequest, reinterpret_cast<uintptr_t>(&observe_phase_request<kPhaseRequest>), "phase_request_json"},
        {0x98d7f38, kBinaryPhaseRequest, reinterpret_cast<uintptr_t>(&observe_phase_request<kBinaryPhaseRequest>), "phase_request_binary"},
        {0x98d7fc8, kPhaseReply, reinterpret_cast<uintptr_t>(&observe_phase_reply<kPhaseReply>), "phase_json"},
        {0x98d7fd8, kBinaryPhaseReply, reinterpret_cast<uintptr_t>(&observe_phase_reply<kBinaryPhaseReply>), "phase_binary"},
        {0x98e6b98, kStartGameRequest, reinterpret_cast<uintptr_t>(&observe_start_game_request<kStartGameRequest>), "start_game_request_json"},
        {0x98e6ba8, kBinaryStartGameRequest, reinterpret_cast<uintptr_t>(&observe_start_game_request<kBinaryStartGameRequest>), "start_game_request_binary"},
        {0x98e6c38, kStartGameReply, reinterpret_cast<uintptr_t>(&observe_start_game_reply<kStartGameReply>), "start_game_reply_json"},
        {0x98e6c48, kBinaryStartGameReply, reinterpret_cast<uintptr_t>(&observe_start_game_reply<kBinaryStartGameReply>), "start_game_reply_binary"},
        {0x98da2c0, kResultPollComplete, reinterpret_cast<uintptr_t>(&observe_result_poll_complete), "result_poll_complete"},
        {0x98da2c8, kConfigureResultPoll, reinterpret_cast<uintptr_t>(&observe_configure_result_poll), "result_poll"},
        {0x98da458, kMatchResult, reinterpret_cast<uintptr_t>(&observe_match_result<kMatchResult>), "result_reply_json"},
        {0x98da468, kBinaryMatchResult, reinterpret_cast<uintptr_t>(&observe_match_result<kBinaryMatchResult>), "result_reply_binary"},
        {0x98d7128, kScoreReply, reinterpret_cast<uintptr_t>(&observe_match_event_reply<kScoreReply>), "score_reply"},
        {0x98d6eb8, kFoulReply, reinterpret_cast<uintptr_t>(&observe_match_event_reply<kFoulReply>), "foul_reply"},
        {0x98e4138, kPrepareSetResult, reinterpret_cast<uintptr_t>(&observe_prepare_set_result<kPrepareSetResult>), "prepare_set_result"},
        {0x98e11c0, kPrepareAlternateResult, reinterpret_cast<uintptr_t>(&observe_prepare_set_result<kPrepareAlternateResult>), "prepare_alternate_result"},
        {0x98e4228, kSetResultRequest, reinterpret_cast<uintptr_t>(&observe_set_result_request<kSetResultRequest>), "set_result_request_json"},
        {0x98e4238, kBinarySetResultRequest, reinterpret_cast<uintptr_t>(&observe_set_result_request<kBinarySetResultRequest>), "set_result_request_binary"},
        {0x98e42c8, kSetResultReply, reinterpret_cast<uintptr_t>(&observe_set_result_reply<kSetResultReply>), "set_result_reply_json"},
        {0x98e42d8, kBinarySetResultReply, reinterpret_cast<uintptr_t>(&observe_set_result_reply<kBinarySetResultReply>), "set_result_reply_binary"},
        {0x990da48, kResultWatcher, reinterpret_cast<uintptr_t>(&observe_result_watcher), "result_watcher"},
        {0x990ba68, kPlatformSessionUpdate, reinterpret_cast<uintptr_t>(&observe_platform_session_update), "platform_session"},
        {0x992e8a0, kSessionState, reinterpret_cast<uintptr_t>(&observe_session), "session_state"},
        {0x992e8a8, kChannelState, reinterpret_cast<uintptr_t>(&observe_channel), "channel_state"},
        {0x992bc28, kPeerUpdate, reinterpret_cast<uintptr_t>(&observe_peer_update<kPeerUpdate, 0xa0>), "mesh_update"},
        {0x992a248, kStreamUpdate, reinterpret_cast<uintptr_t>(&observe_peer_update<kStreamUpdate, 0x60>), "stream_update"},
    };
    unsigned count = 0;
    for (const auto &slot : slots) {
        if (install_slot(slot.slot, slot.original, slot.observer, size_t(page_size))) ++count;
        else peerlink_journal("MATCH_LIFECYCLE_OBSERVER unavailable=%s", slot.name);
    }
    peerlink_journal("MATCH_LIFECYCLE_OBSERVERS installed=%u total=%zu", count, sizeof(slots) / sizeof(slots[0]));
    errno = saved_errno;
}
