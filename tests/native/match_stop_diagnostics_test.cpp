// Exercise the production observer installation and native call ABI. The
// fixtures emulate both real stop paths and a normal frame, including errno
// and the input method's return value; an observer must preserve all of them.
#include "../../app/src/main/jni/game/match_stop_diagnostics.cpp"
#include <cassert>
#include <cstring>
#include <fstream>
#include <iterator>
#include <sys/wait.h>

namespace {
uintptr_t expected_controller, expected_frame;
unsigned request_calls, outgoing_calls, apply_calls;
bool stop_on_outgoing, stop_on_apply;
uintptr_t expected_checksum_root;
unsigned checksum_helper_calls;
uint32_t checksum_fixture_frame = 1;
uint32_t match_checksum_fixture(uintptr_t root, uint32_t *sum, uint32_t *ordinal, uint32_t *frame) {
    assert(root == expected_checksum_root);
    ++checksum_helper_calls;
    *sum += 100; ++*ordinal; *frame = checksum_fixture_frame;
    return 1;
}
void ball_checksum_fixture(uintptr_t root, uint32_t *sum, uint32_t *ordinal) {
    assert(root == expected_checksum_root);
    ++checksum_helper_calls; *sum += 2; ++*ordinal;
}
uintptr_t ball_history_fixture(uintptr_t record, uint32_t slot) {
    assert(slot == 0);
    return record;
}
void object_checksum_fixture(uintptr_t root, uint32_t *sum, uint32_t *ordinal, uint32_t index) {
    assert(root == expected_checksum_root && index < 3);
    ++checksum_helper_calls; *sum += 10 + index; ++*ordinal;
}
void player_checksum_fixture(uintptr_t root, uint32_t *sum, uint32_t *ordinal, uint32_t side, uint32_t slot) {
    assert(root == expected_checksum_root && side < 2 && slot < 11);
    ++checksum_helper_calls; *sum += 100 * side + slot; ++*ordinal;
}
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
#if defined(__x86_64__)
    // Callable fixture helpers at the pinned entry offsets exercise the real
    // capture function. In particular the original API receives **map, while
    // player entries are fields of map itself: those are different objects.
    const pid_t snapshot_child = fork();
    assert(snapshot_child >= 0);
    if (snapshot_child == 0) {
        auto set_pointer = [](unsigned char *at, uintptr_t value) { std::memcpy(at, &value, sizeof(value)); };
        auto set_word = [](unsigned char *at, uint32_t value) { std::memcpy(at, &value, sizeof(value)); };
        auto install_helper = [base](uintptr_t offset, uintptr_t target) {
            unsigned char trampoline[] = {0x48,0xb8,0,0,0,0,0,0,0,0,0xff,0xe0};
            std::memcpy(trampoline + 2, &target, sizeof(target));
            std::memcpy(reinterpret_cast<void *>(base + offset), trampoline, sizeof(trampoline));
        };
        install_helper(0x6b00048, reinterpret_cast<uintptr_t>(match_checksum_fixture));
        install_helper(0x6b00474, reinterpret_cast<uintptr_t>(ball_checksum_fixture));
        install_helper(0x6b0050c, reinterpret_cast<uintptr_t>(object_checksum_fixture));
        install_helper(0x72d9980, reinterpret_cast<uintptr_t>(ball_history_fixture));
        for (uintptr_t offset : {0x6b00650U,0x6b00794U,0x6b00908U})
            install_helper(offset, reinterpret_cast<uintptr_t>(player_checksum_fixture));
        const long page = sysconf(_SC_PAGESIZE);
        const uintptr_t page_start = (base + 0x6b00000) & ~(static_cast<uintptr_t>(page) - 1);
        assert(mprotect(reinterpret_cast<void *>(page_start), page, PROT_READ | PROT_EXEC) == 0);
        const uintptr_t ball_page = (base + 0x72d9980) & ~(static_cast<uintptr_t>(page) - 1);
        assert(mprotect(reinterpret_cast<void *>(ball_page), page, PROT_READ | PROT_EXEC) == 0);
        alignas(uintptr_t) unsigned char service[0x20]{}, inner[0xe0]{}, match[0x300]{}, core[0x3ca0]{};
        alignas(uintptr_t) unsigned char actors[22][0x650]{};
        uint32_t checksum_root[4]{55};
        uintptr_t checksum_handle = reinterpret_cast<uintptr_t>(checksum_root);
        expected_checksum_root = checksum_handle;
        *reinterpret_cast<uintptr_t *>(base + 0xa5909a0) = reinterpret_cast<uintptr_t>(&checksum_handle);
        *reinterpret_cast<uintptr_t *>(base + 0x9a6a020) = reinterpret_cast<uintptr_t>(service);
        *reinterpret_cast<uintptr_t *>(base + 0xa533ec0) = reinterpret_cast<uintptr_t>(match);
        set_pointer(service, base + 0x98ae9e0); set_pointer(service + 8, reinterpret_cast<uintptr_t>(inner));
        set_pointer(inner + 0xa0, expected_controller);
        set_pointer(match + 0x2f8, reinterpret_cast<uintptr_t>(core));
        set_pointer(core, base + 0x9899d50); set_word(core + 8, 54);
        set_word(controller + 0x30, 3);
        for (unsigned i = 0; i < 22; ++i) {
            *reinterpret_cast<uintptr_t *>(base + 0xa5909a0 + 0x170 + i * 8) = reinterpret_cast<uintptr_t>(actors[i]);
            set_word(actors[i] + 0x5dc, 100 + i);
        }
        // The first snapshot must come from a verified game checksum, not a
        // separate host poll. A wrong checksum or incomplete player set must
        // leave the snapshot unpublished and permit a later safe retry.
        peerlink_record_check_frame(1, 3766);
        assert(checksum_helper_calls == 71);
        assert(peerlink_match_state_diagnostics().find("MATCH_STATE_INITIAL present=0") != std::string::npos);
        match[0x138] = 17;
        *reinterpret_cast<uintptr_t *>(base + 0xa5909a0 + 0x170 + 21 * 8) = 0;
        peerlink_record_check_frame(1, 3765);
        assert(checksum_helper_calls == 142);
        assert(peerlink_match_state_diagnostics().find("MATCH_STATE_INITIAL present=0") != std::string::npos);
        *reinterpret_cast<uintptr_t *>(base + 0xa5909a0 + 0x170 + 21 * 8) = reinterpret_cast<uintptr_t>(actors[21]);
        peerlink_record_check_frame(1, 3765);
        assert(checksum_helper_calls == 213 && checksum_root[0] == 55);
        const std::string details = peerlink_match_state_diagnostics();
        assert(details.find("MATCH_STATE_INITIAL present=1 commandFrame=3 stateFrame=1") != std::string::npos);
        assert(details.find("terms=71 parts=[100,2,10,11,12,") != std::string::npos);
        assert(details.find("players=22 playerPositionBits=[100,0,0,101,0,0,") != std::string::npos);
        peerlink_record_check_frame(1, 3765);
        assert(checksum_helper_calls == 284 && checksum_root[0] == 55);
        assert(peerlink_match_state_diagnostics() == details);
        // Export the first compared frame while it is still live, even if
        // the command controller has already advanced beyond that frame.
        char directory[] = "/tmp/peerlink-match-frame-XXXXXX";
        assert(mkdtemp(directory));
        const std::string path = std::string(directory) + "/match.log";
        assert(peerlink_open_diagnostic_journal(path.c_str()));
        checksum_fixture_frame = 4212;
        set_word(controller + 0x30, 4213);
        alignas(uintptr_t) unsigned char history_owner[0x78]{};
        const uint32_t history_ball[7]{11, 12, 13, 21, 22, 23, 24};
        set_pointer(reinterpret_cast<unsigned char *>(base + 0xa58de28), reinterpret_cast<uintptr_t>(history_owner));
        set_pointer(history_owner + 0x70, reinterpret_cast<uintptr_t>(history_ball));
        peerlink_record_check_frame(4212, 3765);
        peerlink_record_check_frame(4239, 32452);
        assert(checksum_helper_calls == 426 && checksum_root[0] == 55);

        alignas(uintptr_t) unsigned char checker[0x30]{}, comparison[0x20]{};
        auto set_half = [](unsigned char *at, uint16_t value) { std::memcpy(at, &value, sizeof(value)); };
        set_word(comparison + 8, 4212); set_word(comparison + 12, 4212);
        set_half(comparison + 2, 3765); set_half(comparison + 4, 3780);
        set_word(checker + 8, 1); set_word(checker + 12, 4212);
        peerlink_record_check_comparison(reinterpret_cast<uintptr_t>(checker), reinterpret_cast<uintptr_t>(comparison));
        set_word(comparison + 12, 4239);
        peerlink_record_check_comparison(reinterpret_cast<uintptr_t>(checker), reinterpret_cast<uintptr_t>(comparison));

        alignas(uintptr_t) unsigned char ball[0x60]{}, group[0x48]{}, transition[0x230]{}, flow[0x33ac]{};
        const uint32_t target[] = {0, 0, 0x42c80000};
        const uintptr_t ball_address = reinterpret_cast<uintptr_t>(ball);
        const uintptr_t group_address = reinterpret_cast<uintptr_t>(group);
        const uintptr_t target_address = reinterpret_cast<uintptr_t>(target);
        const uintptr_t transition_address = reinterpret_cast<uintptr_t>(transition);
        const uintptr_t flow_address = reinterpret_cast<uintptr_t>(flow);
        set_word(controller + 0x30, 4203);
        alignas(uintptr_t) unsigned char scene[16]{};
        unsigned char eligible[24]{};
        constexpr uint32_t channel = 1;
        const auto agreement = base + 0xa590478 + channel * 0xd8;
        unsigned char *choices = reinterpret_cast<unsigned char *>(agreement + 0x10);
        set_word(choices, 1); set_word(choices + 4, 2); set_word(choices + 8, 2);
        eligible[0] = 1; eligible[1] = 1;
        peerlink_record_scene_agreement(agreement, reinterpret_cast<uintptr_t>(eligible), 2);
        eligible[1] = 0;
        peerlink_record_scene_agreement(agreement, reinterpret_cast<uintptr_t>(eligible), 1);
        peerlink_record_scene_agreement(agreement + 1, reinterpret_cast<uintptr_t>(eligible), 1);
        set_word(scene + 4, 4); set_word(scene + 8, 12);
        peerlink_record_scene_decision(reinterpret_cast<uintptr_t>(scene), channel, 2);
        peerlink_record_scene_decision(reinterpret_cast<uintptr_t>(scene), channel, 2);
        peerlink_record_scene_decision(reinterpret_cast<uintptr_t>(scene), channel, 0);
        peerlink_record_scene_decision(reinterpret_cast<uintptr_t>(scene), channel, 1);
        assert(choices[0] == 1 && choices[4] == 2 && choices[8] == 2 && eligible[1] == 0 && scene[4] == 4);
        transition[0x22e] = 1;
        flow[0x333c] = 1;
        peerlink_record_ball_park(ball_address, target_address, group_address, transition_address, flow_address);
        set_pointer(group + 0x30, ball_address);
        peerlink_record_ball_park(ball_address, target_address, group_address, transition_address, flow_address);
        set_word(ball + 0x38, 0x42c80000);
        peerlink_record_ball_park(ball_address, target_address, group_address, transition_address, flow_address);

        std::ifstream file(path);
        const std::string journal((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
        assert(journal.find("MATCH_CHECK_FRAME frame=4212 checkValue=3765 snapshotVerified=1 terms=71") != std::string::npos);
        assert(journal.find(" partTerms=[1,1,1,1,1,") != std::string::npos);
        assert(journal.find(" ballBits=[11,12,13,21,22,23,24]") != std::string::npos);
        const std::string agreement1 = "MATCH_SCENE_AGREEMENT commandFrame=4203 channel=1 result=1 eligibleMask=1 choiceOneMask=1 choiceTwoMask=0";
        assert(journal.find(agreement1) != std::string::npos && journal.find(agreement1) == journal.rfind(agreement1));
        assert(journal.find("channel=1 result=2 eligibleMask=3 choiceOneMask=1 choiceTwoMask=2") != std::string::npos);
        const std::string decision2 = "MATCH_SCENE_DECISION commandFrame=4203 sceneId=12 step=4 channel=1 result=2";
        assert(journal.find(decision2) != std::string::npos && journal.find(decision2) == journal.rfind(decision2));
        assert(journal.find("sceneId=12 step=4 channel=1 result=1") != std::string::npos);
        assert(journal.find("MATCH_CHECK_FRAME frame=4239 checkValue=32452 snapshotVerified=0 observedFrame=4212 observedValue=3765") != std::string::npos);
        assert(journal.find("MATCH_CHECK_COMPARE frame=4212 localValue=3765 peerValue=3780 equal=0") != std::string::npos);
        assert(journal.find("MATCH_CHECK_COMPARE frame=4239") == std::string::npos);
        const std::string park = "MATCH_ACTIVE_BALL_PARK commandFrame=4203 flow=1 transitionFlag=1 matchFlowFlag=0";
        assert(journal.find(park) != std::string::npos && journal.find(park) == journal.rfind(park));
        unlink(path.c_str());
        rmdir(directory);
        _exit(0);
    }
    int snapshot_status = 0;
    assert(waitpid(snapshot_child, &snapshot_status, 0) == snapshot_child &&
        WIFEXITED(snapshot_status) && WEXITSTATUS(snapshot_status) == 0);
#endif
    munmap(mapping, mapping_size);
    puts("MATCH_STOP_OBSERVER_ABI_AND_ORIGINAL_CHECKSUM_CONTEXT_PRESERVATION_PASS");
}
