#include "executable_patch_checks.h"
#include "executable_patch.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

namespace {
#if defined(__aarch64__)
const unsigned char original[] = {0xe0,0x00,0x80,0x52, 0xc0,0x03,0x5f,0xd6}; // return 7
const unsigned char changed[] = {0x40,0x05,0x80,0x52, 0xc0,0x03,0x5f,0xd6}; // return 42
#elif defined(__x86_64__)
const unsigned char original[] = {0xb8,0x07,0x00,0x00,0x00,0xc3}; // return 7
const unsigned char changed[] = {0xb8,0x2a,0x00,0x00,0x00,0xc3}; // return 42
#else
#error Unsupported test architecture
#endif
struct Mapping {
    void *address = MAP_FAILED;
    size_t size = 0;
    ~Mapping() { if (address != MAP_FAILED) munmap(address, size); }
};
struct FileDescriptor {
    int value;
    ~FileDescriptor() { if (value >= 0) close(value); }
};
bool executable_not_writable(void *address) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) return false;
    char line[1024], permissions[5];
    unsigned long first, last;
    bool matched = false;
    while (fgets(line, sizeof(line), maps)) {
        if (sscanf(line, "%lx-%lx %4s", &first, &last, permissions) == 3 &&
            first <= reinterpret_cast<uintptr_t>(address) && reinterpret_cast<uintptr_t>(address) < last) {
            matched = strcmp(permissions, "r-xp") == 0;
            break;
        }
    }
    fclose(maps);
    return matched;
}
}

std::string executable_patch_checks(const char *path, bool expect_legacy_denied) {
    const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    FileDescriptor fd{open(path, O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600)};
    if (fd.value < 0) return "FAIL open errno=" + std::to_string(errno);
    std::vector<unsigned char> content(page * 3);
    for (size_t i = 0; i < content.size(); ++i) content[i] = static_cast<unsigned char>((i * 17 + 3) % 251);
    memcpy(content.data() + 64, original, sizeof(original));
    const size_t crossing = page - 4;
    memcpy(content.data() + crossing, original, sizeof(original));
    if (write(fd.value, content.data(), content.size()) != static_cast<ssize_t>(content.size())) return "FAIL write";

    Mapping legacy{mmap(nullptr, page, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd.value, 0), page};
    if (legacy.address == MAP_FAILED) return "FAIL legacy_map errno=" + std::to_string(errno);
    if (mprotect(legacy.address, page, PROT_READ | PROT_WRITE)) return "FAIL legacy_write errno=" + std::to_string(errno);
    memcpy(static_cast<unsigned char *>(legacy.address) + 64, changed, sizeof(changed));
    const int legacy_result = mprotect(legacy.address, page, PROT_READ | PROT_EXEC);
    const int legacy_error = legacy_result == 0 ? 0 : errno;
    if (expect_legacy_denied && (legacy_result == 0 || (legacy_error != EACCES && legacy_error != EPERM)))
        return "FAIL expected_execmod_denial errno=" + std::to_string(legacy_error);

    Mapping code{mmap(nullptr, content.size(), PROT_READ | PROT_EXEC, MAP_PRIVATE, fd.value, 0), content.size()};
    if (code.address == MAP_FAILED) return "FAIL code_map errno=" + std::to_string(errno);
    auto *base = static_cast<unsigned char *>(code.address);
    auto function = reinterpret_cast<int (*)()>(base + 64);
    auto crossing_function = reinterpret_cast<int (*)()>(base + crossing);
    if (function() != 7 || crossing_function() != 7) return "FAIL original_execution";
    peerlink::CodePatchFailure failure;
    auto apply = [&](size_t offset, const void *bytes, size_t length) {
        return peerlink::replace_executable_bytes(reinterpret_cast<uintptr_t>(base + offset), bytes, length, &failure);
    };
    if (!apply(64, changed, sizeof(changed)))
        return "FAIL " + std::string(failure.operation) + " errno=" + std::to_string(failure.error);
    if (function() != 42 || !executable_not_writable(base + 64)) return "FAIL patched_execution_or_permissions";
    memcpy(content.data() + 64, changed, sizeof(changed));
    if (memcmp(base, content.data(), content.size())) return "FAIL neighboring_bytes_changed";
    if (!apply(crossing, changed, sizeof(changed))) return "FAIL crossing_patch";
    if (crossing_function() != 42 || !executable_not_writable(base) || !executable_not_writable(base + page))
        return "FAIL crossing_execution_or_permissions";
    memcpy(content.data() + crossing, changed, sizeof(changed));
    if (memcmp(base, content.data(), content.size())) return "FAIL crossing_neighbors_changed";
    if (!apply(64, original, sizeof(original)) || function() != 7) return "FAIL repeat_patch";
    unsigned char stored[sizeof(original)];
    if (pread(fd.value, stored, sizeof(stored), 64) != static_cast<ssize_t>(sizeof(stored)) ||
        memcmp(stored, original, sizeof(original))) return "FAIL original_file_changed";
    if (peerlink::replace_executable_bytes(0, changed, sizeof(changed), &failure) || failure.error != EINVAL ||
        peerlink::replace_executable_bytes(std::numeric_limits<uintptr_t>::max() - 1, changed, sizeof(changed), &failure) ||
        failure.error != EINVAL || function() != 7) return "FAIL invalid_arguments";
    return "OK legacy_errno=" + std::to_string(legacy_error) + " pageSize=" + std::to_string(page) +
        " executed=7,42,42,7 neighboring_bytes=unchanged source_file=unchanged permissions=rx";
}
