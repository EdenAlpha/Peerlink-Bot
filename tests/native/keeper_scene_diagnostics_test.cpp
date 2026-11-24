#include "diagnostic_journal.h"
#include "match_state_diagnostics.h"
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <sys/mman.h>
#include <unistd.h>

namespace {
template<class T> void put(uintptr_t address, T value) {
    std::memcpy(reinterpret_cast<void *>(address), &value, sizeof(value));
}

std::string find_line(const std::string &log, const std::string &match) {
    const auto start = log.find(match);
    assert(start != std::string::npos);
    return log.substr(start, log.find('\n', start) - start);
}
}

int main() {
    constexpr size_t image_size = 0xa600000;
    void *image = mmap(nullptr, image_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(image != MAP_FAILED);
    const uintptr_t base = reinterpret_cast<uintptr_t>(image);
    peerlink_match_state_begin(base);
    char directory[] = "/tmp/peerlink-keeper-scene-XXXXXX";
    assert(mkdtemp(directory));
    const std::string path = std::string(directory) + "/scene.log";
    assert(peerlink_open_keeper_journal(path.c_str()));

    // Sparse synthetic objects with the same fields as the pinned game.
    auto object = [base](unsigned index) { return base + index * 0x40000; };
    const uintptr_t service = object(1), inner = object(2), command = object(3), match = object(4);
    const uintptr_t core = object(5), simulation = object(6), roster0 = object(7), roster1 = object(8);
    const uintptr_t keeper0 = object(9), keeper1 = object(10), player_control = object(11), track = object(12);
    const uintptr_t context = object(13), scene = context + 0x3f0, actor = object(14), transition = object(15);
    const uintptr_t balls = object(16), possession = object(17), handler = object(18), timer = object(19);
    const uintptr_t checker = object(20), comparison = object(21);
    put(base + 0x9a6a020, service); put(service, base + 0x98ae9e0);
    put(service + 8, inner); put(inner + 0xa0, command); put(command, base + 0x98aedd0);
    put(base + 0xa533ec0, match); put(match + 0x138, uint8_t{17});
    put(match + 0x2f8, core); put(core, base + 0x9899d50); put(core + 8, 54U);
    put(core + 0x3c98, balls); put(balls, base + 0x989a160); put(balls + 0x68, possession);
    put(possession + 0x28780, 2U); put(context + 0x680, balls + 0x58);
    put(base + 0xa58de28, simulation); put(simulation + 0x3030, roster0); put(simulation + 0x3080, roster1);
    // Exercise a goalkeeper assigned to a different slot on each side.
    put(roster0 + 0x42b8 + 2 * 4, 1U); put(roster1 + 0x42b8 + 4 * 4, 1U);
    put(base + 0xa5909a0 + 0x170 + 2 * 8, keeper0); put(base + 0xa5909a0 + 0x170 + 15 * 8, keeper1);
    put(keeper0 + 0x5dc, 0xc2480000U); put(keeper0 + 0x7a8, player_control);
    put(player_control + 0xa88, track); put(track + 0x1e2, uint8_t{1}); put(track + 0x1e4, 0x3f800000U);
    put(keeper1 + 0x5dc, 0x42480000U);
    put(context + 0x30, handler); put(handler, base + 0x98aeb00); put(base + 0x98aeb38, base + 0x6fecb50);
    put(scene + 0x280, actor); put(actor, base + 0x98aeb40); put(base + 0x98aeb68, base + 0x69d25bc);
    put(actor + 0x250, 4U); put(actor + 0x228, object(22)); put(transition + 0x22e, uint8_t{1});

    put(scene + 8, 197064U); put(scene + 4, 8U); put(command + 0x30, 100U);
    assert(!peerlink_keeper_scene_enter(scene + 4, context, transition).number);
    auto tick = peerlink_keeper_scene_enter(scene, context, transition);
    assert(tick.number == 1 && tick.frame == 100 && tick.sample);
    // The original scene advances both the step and the local motion state.
    put(timer + 0xb4, 0x40000000U); peerlink_keeper_clock_sample(timer, true);
    put(scene + 4, 10U); put(actor + 0x250, 6U);
    put(keeper0 + 0x5dc, 0xc2440000U); put(track + 0x1e4, 0x40000000U);
    peerlink_keeper_scene_leave(tick, scene, context, transition, false);
    tick = peerlink_keeper_scene_enter(scene, context, transition);
    assert(tick.number == 1 && !tick.sample);
    peerlink_keeper_scene_leave(tick, scene, context, transition, false);
    put(command + 0x30, 101U);
    tick = peerlink_keeper_scene_enter(scene, context, transition);
    assert(tick.sample);
    peerlink_keeper_scene_leave(tick, scene, context, transition, false);
    put(command + 0x30, 192U);
    tick = peerlink_keeper_scene_enter(scene, context, transition);
    assert(tick.sample);
    peerlink_keeper_scene_leave(tick, scene, context, transition, false);
    put(command + 0x30, 193U);
    tick = peerlink_keeper_scene_enter(scene, context, transition);
    assert(!tick.sample);
    peerlink_keeper_scene_leave(tick, scene, context, transition, true);

    put(comparison + 8, 194U); put(comparison + 12, 194U);
    put(comparison + 2, uint16_t{20}); put(comparison + 4, uint16_t{20});
    peerlink_record_check_comparison(checker, comparison);

    // The same scene can run again. Keep the first attempt and the second
    // attempt's missing track, stalled keeper, and first mismatch.
    const uintptr_t second = object(23), second_scene = second + 0x3f0;
    put(second_scene + 8, 197064U); put(second_scene + 4, 8U); put(command + 0x30, 300U);
    put(keeper0 + 0x5dc, 0xc2480000U); put(player_control + 0xa88, uintptr_t{0});
    tick = peerlink_keeper_scene_enter(second_scene, second, transition);
    assert(tick.number == 2 && tick.sample);
    put(second_scene + 4, 10U);
    peerlink_keeper_scene_leave(tick, second_scene, second, transition, false);
    put(comparison + 8, 324U); put(comparison + 12, 324U);
    put(checker + 8, 1U); put(checker + 12, 324U);
    put(comparison + 2, uint16_t{30}); put(comparison + 4, uint16_t{40});
    peerlink_record_check_comparison(checker, comparison);
    // An unrelated disagreement also produces a marker when no exact player
    // snapshot is available. It must not be attached to this scene.
    put(comparison + 8, 900U); put(comparison + 12, 900U); put(checker + 12, 900U);
    peerlink_record_check_comparison(checker, comparison);

    std::ifstream input(path);
    const std::string log(std::istreambuf_iterator<char>(input), {});
    const auto before = find_line(log, "KEEPER_SCENE_STATE scene=1 sceneId=197064 stage=before commandFrame=100");
    assert(before.find("handlerOffset=0x6fecb50 ballContext=1 sameBallContext=1 ballOwnerPresent=1 ballOwner=2") != std::string::npos);
    const auto initial_position = "k0PositionBits=[" + std::to_string(0xc2480000U) + ",0,0]";
    assert(before.find("k0Player=2 k0PlayerPresent=1 " + initial_position) != std::string::npos);
    assert(before.find("k1Player=15 k1PlayerPresent=1") != std::string::npos);
    const auto after = find_line(log, "KEEPER_SCENE_STATE scene=1 sceneId=197064 stage=after commandFrame=100");
    assert(after.find("step=10 finished=0") != std::string::npos);
    assert(after.find("clockReads=1 timelineLoaded=1 cutClockBits=[1073741824,0,0,0]") != std::string::npos);
    assert(after.find("k0TrackTimeBits=1073741824") != std::string::npos);
    assert(after.find("k0PositionBits=[" + std::to_string(0xc2440000U) + ",0,0]") != std::string::npos);
    assert(find_line(log, "stage=after commandFrame=193").find("finished=1") != std::string::npos);
    assert(find_line(log, "KEEPER_SCENE_COMPARE scene=1 sceneId=197064 frame=194").find("equal=1") != std::string::npos);
    const auto failed = find_line(log, "KEEPER_SCENE_STATE scene=2 sceneId=197064 stage=after commandFrame=300");
    assert(failed.find("k0TrackPresent=0") != std::string::npos);
    assert(failed.find(initial_position) != std::string::npos);
    assert(find_line(log, "KEEPER_SCENE_COMPARE scene=2 sceneId=197064 frame=324").find("firstMismatchFrame=324") != std::string::npos);
    assert(log.find("FIRST_MISMATCH_PLAYERS frame=900 exactSnapshot=0") != std::string::npos);
    assert(log.find("scene=2 sceneId=197064 frame=900") == std::string::npos);
    unlink(path.c_str());
    rmdir(directory);
    munmap(image, image_size);
    puts("KEEPER_SCENE_COMPLETION_AND_FIRST_MISMATCH_TRACES_PASS");
}
