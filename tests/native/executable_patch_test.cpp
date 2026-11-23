#include "executable_patch_checks.h"
#include "executable_patch.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <sys/mman.h>
#include <unistd.h>

int main() {
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto *occupied = static_cast<unsigned char *>(mmap(nullptr, page * 3, PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    assert(occupied != MAP_FAILED);
    occupied[0] = 19;
    occupied[page * 2] = 73;
    const uintptr_t base = reinterpret_cast<uintptr_t>(occupied);
    assert(peerlink::reserve_writable_page(base, base + page * 3, page) == MAP_FAILED);
    assert(munmap(occupied + page, page) == 0);
    assert(peerlink::reserve_writable_page(base, base + page * 3, page) == occupied + page);
    assert(occupied[0] == 19 && occupied[page * 2] == 73);
    assert(munmap(occupied, page * 3) == 0);

    char path[] = "/tmp/peerlink-code-patch-XXXXXX";
    const int fd = mkstemp(path);
    if (fd < 0) return 1;
    close(fd);
    const std::string result = executable_patch_checks(path, false);
    unlink(path);
    puts(result.c_str());
    return result.rfind("OK ", 0) == 0 ? 0 : 1;
}
