#include "diagnostic_journal.h"
#include "restart_diagnostics.h"

#include <array>
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <sys/mman.h>
#include <unistd.h>

namespace {
template <typename T>
void write_field(uintptr_t where, T value) {
    std::memcpy(reinterpret_cast<void *>(where), &value, sizeof(value));
}

std::string read_log(const char *name) {
    std::ifstream log(name);
    return {std::istreambuf_iterator<char>(log), std::istreambuf_iterator<char>()};
}
}

int main() {
    constexpr size_t size = 0xa600000;
    void *image = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(image != MAP_FAILED);
    const auto base = reinterpret_cast<uintptr_t>(image);
    const uintptr_t controller = base + 0x1000, core = base + 0x2000;
    const uintptr_t match = base + 0x7000, root = base + 0x8000, world = base + 0x9000;
    const uintptr_t balls = base + 0x10000, ball = base + 0x11000, player = base + 0x12000;
    write_field(base + 0xa533ec0, match);
    write_field(base + 0xa58de28, root);
    write_field(base + 0xa5909a0 + 0x170, player);
    write_field(controller, base + 0x98aedd0);
    write_field(core, base + 0x9899d50);
    write_field(core + 8, 54U);
    write_field(core + 0x3c98, balls);
    write_field(match + 0x138, uint8_t(17));
    write_field(match + 0x2f8, core);
    write_field(root + 0x4d0, world);
    write_field(world + 0x3314, 3U);
    write_field(world + 0x34ac, 5U);
    write_field(world + 0x41e4, 19U);
    write_field(world + 0x41e8, 255U);
    write_field(world + 0x3350, uint8_t(1));
    write_field(balls, base + 0x989a160);
    write_field(balls + 0x30, ball);
    write_field(balls + 0x38, ball);
    write_field(ball + 0x30, 123U);
    write_field(player + 0x5dc, 456U);
    std::array<uint8_t, 24 * 0xb4> slots{};
    slots[8 * 16] = 0xcf;
    slots[12 * 0xb4 + 3 * 16] = 2;
    slots[23 * 0xb4 + 8 * 16] = 0xf8;
    const auto decoded = reinterpret_cast<uintptr_t>(slots.data());

    char directory[] = "/tmp/peerlink-restart-XXXXXX";
    assert(mkdtemp(directory));
    const std::string log = std::string(directory) + "/state.log";
    assert(peerlink_open_restart_journal(log.c_str()));
    assert(peerlink_restart_journal_active());
    for (unsigned frame = 681; frame <= 1081; ++frame) {
        write_field(controller + 0x30, frame);
        write_field(world + 0x3324, frame - 1);
        peerlink_record_restart_frame(base, controller, decoded);
    }
    peerlink_record_restart_comparison(1053, 23468, 23468);
    peerlink_flush_restart_trace();
    assert(read_log(log.c_str()).find("RESTART_TRACE_FRAME") == std::string::npos);
    peerlink_record_restart_comparison(1080, 26861, 27220);
    write_field(controller + 0x30, 1082U);
    peerlink_record_restart_frame(base, controller, decoded);
    peerlink_record_restart_comparison(1107, 23603, 23284);
    peerlink_flush_restart_trace();
    const std::string output = read_log(log.c_str());
    assert(output.find("frame=1080 lastAgreement=1053 localValue=26861 peerValue=27220 capturedFrames=384 firstCommandFrame=698 lastCommandFrame=1081") != std::string::npos);
    assert(output.find("commandFrame=698 outer=17 worldPresent=1 simFrame=697 phase=3 restartKind=5 kickers=19,255 kickerFlags=1,0") != std::string::npos);
    assert(output.find("ballPresent=1 presentMask=1 commands=[0/8/79,12/3/2] ballBits=[123,0,0,0,0,0] positionBits=[456,0,0,") != std::string::npos);
    assert(output.find("commandFrame=1082") == std::string::npos);
    assert(output.find("RESTART_TRACE_COMPLETE frames=384") != std::string::npos);
    peerlink_flush_restart_trace();
    assert(output == read_log(log.c_str()));
    assert(unlink(log.c_str()) == 0 && rmdir(directory) == 0);
    assert(munmap(image, size) == 0);
}
