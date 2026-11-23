#include <cassert>
#include <cstdint>
#include <cstring>
#include <sys/mman.h>

extern "C" int peerlink_halftime_menu_state(uintptr_t base);

int main() {
    constexpr size_t size = 0xa600000;
    auto *image = static_cast<char *>(mmap(nullptr, size, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(image != MAP_FAILED);
    const auto base = reinterpret_cast<uintptr_t>(image);
    alignas(uintptr_t) char flow[0x200]{}, task[0x100]{}, leaf[0xe0]{}, actor[0x300]{};
    uintptr_t holder = reinterpret_cast<uintptr_t>(actor);
    auto pointer = [](char *field, const void *value) { std::memcpy(field, &value, sizeof(value)); };
    auto word = [](char *field, uint32_t value) { std::memcpy(field, &value, sizeof(value)); };
    auto scene = [&](const char *name) {
        leaf[0] = 1;
        const size_t length = std::strlen(name);
        std::memcpy(leaf + 8, &length, sizeof(length));
        pointer(leaf + 16, name);
    };
    pointer(image + 0xa53b6e8, flow);
    pointer(image + 0x99e6830, &holder);
    pointer(flow + 0x190, task);
    pointer(task + 0x70, leaf);
    pointer(task + 0x78, leaf + sizeof(leaf));

    // The first break snapshot on the Infinix still had MatchIdle in state 2.
    scene("Match/MatchIdle");
    word(flow + 0x120, 2);
    assert(peerlink_halftime_menu_state(base) == 1);
    constexpr char16_t menu[] = u"Match/HalfTime/MatchHalfTime";
    pointer(actor + 0x2b0, menu);
    word(actor + 0x2b8, sizeof(menu) / sizeof(char16_t));
    word(actor + 0x28c, 6);
    assert(peerlink_halftime_menu_state(base) == 5);
    word(actor + 0x28c, 0);
    scene("Match/HalfTime/MatchHalfTime");
    assert(peerlink_halftime_menu_state(base) == 2);
    word(flow + 0x120, 7);
    assert(peerlink_halftime_menu_state(base) == 3);
    leaf[0x50] = 1;
    assert(peerlink_halftime_menu_state(base) == 4);
    scene("Match/HalfTime/MatchHalfTimePost");
    assert(peerlink_halftime_menu_state(base) == 4);
    // Observed sequence on Android stayed in the first-half presentation
    // before returning to idle. A held menu actor cannot authorize a press.
    leaf[0x50] = 0;
    word(actor + 0x28c, 6);
    word(flow + 0x120, 7);
    scene("Match/HalfTime/MatchHalfTimeTimeUpDemo");
    assert(peerlink_halftime_menu_state(base) == 6);
    word(flow + 0x120, 0);
    scene("Match/HalfTime/MatchHalfTimePost");
    assert(peerlink_halftime_menu_state(base) == 4);
    word(actor + 0x28c, 0);
    scene("Match/MatchIdle");
    assert(peerlink_halftime_menu_state(base) == 1);
    munmap(image, size);
}
