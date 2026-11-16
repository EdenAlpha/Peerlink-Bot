#include "executable_patch_checks.h"
#include <cstdio>
#include <cstdlib>
#include <unistd.h>

int main() {
    char path[] = "/tmp/peerlink-code-patch-XXXXXX";
    const int fd = mkstemp(path);
    if (fd < 0) return 1;
    close(fd);
    const std::string result = executable_patch_checks(path, false);
    unlink(path);
    puts(result.c_str());
    return result.rfind("OK ", 0) == 0 ? 0 : 1;
}
