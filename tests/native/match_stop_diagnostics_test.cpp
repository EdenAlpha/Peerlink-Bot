// Exercise the production observer installation and native call ABI. The
// fixtures emulate both real stop paths and a normal frame, including errno
// and the input method's return value; an observer must preserve all of them.
#include "../../app/src/main/jni/game/match_stop_diagnostics.cpp"
#include <cassert>
#include <cstring>

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
    constexpr size_t mapping_size = 0x9b00000;
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
    assert(strstr(report, "commandFrame=7 commandMissingMask=2 commandAheadMask=0"));
    assert(strstr(report, "commandStopPending=1 commandStopped=1"));
    assert(first_request.state == 2 && first_local_stop.state == 2 && first_input_stop.state == 2);
    assert(request_calls == 1 && outgoing_calls == 2 && apply_calls == 3);
    char short_report[10];
    memset(short_report, 'x', sizeof(short_report));
    peerlink_match_stop_diagnostics(0, short_report, sizeof(short_report));
    assert(short_report[sizeof(short_report) - 1] == 0);
    munmap(mapping, mapping_size);
    puts("MATCH_STOP_OBSERVER_CALL_RETURN_ERRNO_AND_PATH_PRESERVATION_PASS");
}
