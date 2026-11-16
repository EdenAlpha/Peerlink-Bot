// Exercise the production observer installation and native call ABI. The
// fixtures emulate both real stop paths and a normal frame, including errno
// and the input method's return value; an observer must preserve all of them.
#include "../../app/src/main/jni/game/match_stop_diagnostics.cpp"
#include <cassert>
#include <cstring>
#include <sys/wait.h>

namespace {
uintptr_t expected_controller, expected_frame;
unsigned request_calls, outgoing_calls, apply_calls;
bool stop_on_outgoing, stop_on_apply;
void request_fixture(uintptr_t controller) {
    assert(controller == expected_controller);
    assert(errno == ENOTTY);
    ++request_calls;
    *reinterpret_cast<unsigned char *>(controller + 0x45) = 1;
    errno = EBUSY;
}
void outgoing_fixture(uintptr_t controller, uintptr_t frame) {
    assert(controller == expected_controller && frame == expected_frame);
    assert(errno == ENOTTY);
    ++outgoing_calls;
    if (stop_on_outgoing) *reinterpret_cast<unsigned char *>(controller + 0x49) = 1;
    errno = EIO;
}
uint32_t apply_fixture(uintptr_t controller, uintptr_t frame) {
    assert(controller == expected_controller && frame == expected_frame);
    assert(errno == ENOTTY);
    ++apply_calls;
    if (stop_on_apply) *reinterpret_cast<unsigned char *>(controller + 0x49) = 1;
    errno = EAGAIN;
    return 0x8abc1234;
}
}

int main() {
    constexpr size_t mapping_size = 0xa600000;
    void *mapping = mmap(nullptr, mapping_size, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(mapping != MAP_FAILED);
    const uintptr_t base = reinterpret_cast<uintptr_t>(mapping);
    for (size_t i = 0; i < 3; ++i)
        *reinterpret_cast<uintptr_t *>(base + kTable + kOffsets[i]) = base + kFunctions[i];
    auto *wrong_slot = reinterpret_cast<uintptr_t *>(base + kTable + kOffsets[1]);
    *wrong_slot += 4;
    peerlink_observe_match_stop(base);
    assert(installed == -1);
    assert(*reinterpret_cast<uintptr_t *>(base + kTable + kOffsets[0]) == base + kFunctions[0]);
    assert(*wrong_slot == base + kFunctions[1] + 4);
    installed = 0;
    *wrong_slot -= 4;
    peerlink_observe_match_stop(base);
    assert(installed == 1);
    assert(reinterpret_cast<uintptr_t>(original_request) == base + kFunctions[0]);
    assert(reinterpret_cast<uintptr_t>(original_outgoing) == base + kFunctions[1]);
    assert(reinterpret_cast<uintptr_t>(original_apply) == base + kFunctions[2]);
    auto request = *reinterpret_cast<Request *>(base + kTable + kOffsets[0]);
    auto outgoing = *reinterpret_cast<Outgoing *>(base + kTable + kOffsets[1]);
    auto apply = *reinterpret_cast<Apply *>(base + kTable + kOffsets[2]);
    assert(request == observe_request && outgoing == observe_outgoing && apply == observe_apply);
    original_request = request_fixture;
    original_outgoing = outgoing_fixture;
    original_apply = apply_fixture;
    alignas(uintptr_t) unsigned char controller[0x100]{};
    expected_controller = reinterpret_cast<uintptr_t>(controller);
    expected_frame = 0x12345678;
    *reinterpret_cast<uintptr_t *>(controller) = base + kTable;
    *reinterpret_cast<uint32_t *>(controller + 0x30) = 7;
    *reinterpret_cast<uint32_t *>(controller + 0x28) = 2;
    errno = ENOTTY;
    outgoing(expected_controller, expected_frame);
    assert(errno == EIO && !controller[0x49] && local_stops == 0);
    errno = ENOTTY;
    assert(apply(expected_controller, expected_frame) == 0x8abc1234);
    assert(errno == EAGAIN && !controller[0x49] && input_stops == 0);
    errno = ENOTTY;
    request(expected_controller);
    assert(errno == EBUSY && controller[0x45] == 1 && requests == 1);
    stop_on_outgoing = true;
    errno = ENOTTY;
    outgoing(expected_controller, expected_frame);
    assert(errno == EIO && controller[0x49] == 1 && local_stops == 1 && input_stops == 0);
    controller[0x49] = 0;
    stop_on_outgoing = false;
    stop_on_apply = true;
    errno = ENOTTY;
    assert(apply(expected_controller, expected_frame) == 0x8abc1234);
    assert(errno == EAGAIN && controller[0x49] == 1 && input_stops == 1);
    errno = ENOTTY;
    assert(apply(expected_controller, expected_frame) == 0x8abc1234);
    assert(errno == EAGAIN && input_stops == 1);
    char report[1024];
    peerlink_match_stop_diagnostics(expected_controller, report, sizeof(report));
    assert(strstr(report, "stopObserver=1 stopRequests=1 stopOutgoing=1 stopInput=1"));
    assert(strstr(report, "commandControllerValid=1 commandControllerVtable=0x98aedd0"));
    assert(strstr(report, "commandFrame=7 commandMissingMask=2 commandAheadMask=0"));
    assert(strstr(report, "commandStopPending=1 commandStopped=1"));
    assert(first_request.state == 2 && first_local_stop.state == 2 && first_input_stop.state == 2);
    peerlink_match_stop_diagnostics(0, report, sizeof(report));
    assert(strstr(report, "commandControllerValid=0 commandControllerVtable=0x0"));
    assert(request_calls == 1 && outgoing_calls == 2 && apply_calls == 3);
    char short_report[10];
    memset(short_report, 'x', sizeof(short_report));
    peerlink_match_stop_diagnostics(0, short_report, sizeof(short_report));
    assert(short_report[sizeof(short_report) - 1] == 0);
    // Preserve the actual local/remote meaning regardless of which side's
    // checksum arrives second. Each child begins with an empty first report.
    for (unsigned origin : {1U, 255U}) {
        const pid_t child = fork();
        assert(child >= 0);
        if (child == 0) {
            alignas(uintptr_t) unsigned char service[0x20]{}, inner[0xe0]{};
            alignas(uintptr_t) unsigned char checker[0x30]{}, context[0x20]{};
            auto set_pointer = [](unsigned char *at, uintptr_t value) { std::memcpy(at, &value, sizeof(value)); };
            auto set_word = [](unsigned char *at, uint32_t value) { std::memcpy(at, &value, sizeof(value)); };
            auto set_half = [](unsigned char *at, uint16_t value) { std::memcpy(at, &value, sizeof(value)); };
            *reinterpret_cast<uintptr_t *>(base + 0x9a6a020) = reinterpret_cast<uintptr_t>(service);
            set_pointer(service, base + 0x98ae9e0);
            set_pointer(service + 8, reinterpret_cast<uintptr_t>(inner));
            set_pointer(inner + 0xa0, expected_controller);
            set_pointer(inner + 0xd8, reinterpret_cast<uintptr_t>(checker));
            set_word(checker + 8, 2); set_word(checker + 12, 27);
            context[1] = origin;
            set_half(context + 2, origin == 255 ? 45678 : 12345);
            set_half(context + 4, origin == 255 ? 12345 : 45678);
            set_word(context + 8, 54); set_word(context + 12, 54); set_word(context + 16, 5);
            set_pointer(context + 24, expected_controller);
            set_word(controller + 0x30, 56);
            controller[0x45] = 0; controller[0x49] = 0;
            unsigned char before_controller[sizeof(controller)], before_checker[sizeof(checker)], before_context[sizeof(context)];
            std::memcpy(before_controller, controller, sizeof(controller));
            std::memcpy(before_checker, checker, sizeof(checker));
            std::memcpy(before_context, context, sizeof(context));
            const uintptr_t checker_address = reinterpret_cast<uintptr_t>(checker), context_address = reinterpret_cast<uintptr_t>(context);
            peerlink_match_check_request(expected_controller, checker_address, context_address, base + 0x70539e8);
            assert(!peerlink_match_check_failed());
            peerlink_match_check_request(expected_controller, checker_address + 8, context_address, base + 0x70539e4);
            assert(!peerlink_match_check_failed());
            set_word(context + 16, 2);
            peerlink_match_check_request(expected_controller, checker_address, context_address, base + 0x70539e4);
            assert(!peerlink_match_check_failed());
            set_word(context + 16, 5);
            errno = ENOTTY;
            peerlink_match_observe_request_impl(expected_controller, checker_address, context_address, base + 0x70539e4);
            assert(errno == EBUSY && peerlink_match_check_failed());
            before_controller[0x45] = 1; // The original stop setter's sole write.
            assert(!std::memcmp(controller, before_controller, sizeof(controller)));
            assert(!std::memcmp(checker, before_checker, sizeof(checker)));
            assert(!std::memcmp(context, before_context, sizeof(context)));
            const std::string details = peerlink_match_state_diagnostics();
            assert(details.find("compareFrame=54 storedFrame=54 localValue=12345 peerValue=45678") != std::string::npos);
            assert(details.find("mismatchCount=2 firstMismatchFrame=27 stopCommandFrame=56") != std::string::npos);
            assert(details.find("MATCH_STATE_INITIAL present=0") != std::string::npos);
            assert(details.find("MATCH_STATE_AT_STOP present=0") != std::string::npos);
            _exit(0);
        }
        int status = 0;
        assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    }
    munmap(mapping, mapping_size);
    puts("MATCH_STOP_OBSERVER_ABI_AND_ORIGINAL_CHECKSUM_CONTEXT_PRESERVATION_PASS");
}
