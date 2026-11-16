#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "executable_patch.h"

#include <cerrno>
#include <cstring>
#include <limits>
#include <sys/mman.h>
#include <unistd.h>

namespace peerlink {

bool replace_executable_bytes(uintptr_t address, const void *bytes, size_t length,
                              CodePatchFailure *failure) {
    const long page = sysconf(_SC_PAGESIZE);
    if (failure) *failure = {"none", 0, page > 0 ? static_cast<size_t>(page) : 0};
    auto reject = [failure](const char *operation, int error) {
        if (failure) { failure->operation = operation; failure->error = error; }
        return false;
    };
    if (page < 4096 || page > 65536 || (page & (page - 1)))
        return reject("page_size", EINVAL);
    const size_t page_size = static_cast<size_t>(page);
    if (!address || !bytes || !length || length > std::numeric_limits<uintptr_t>::max() - address)
        return reject("arguments", EINVAL);
    const uintptr_t start = address & ~(static_cast<uintptr_t>(page_size) - 1);
    const uintptr_t used = address - start + length;
    if (used > std::numeric_limits<size_t>::max() - (page_size - 1))
        return reject("range", EOVERFLOW);
    const size_t span = (used + page_size - 1) & ~(page_size - 1);
    if (span > std::numeric_limits<uintptr_t>::max() - start)
        return reject("range", EOVERFLOW);
    void *copy = mmap(nullptr, span, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (copy == MAP_FAILED) return reject("anonymous_copy", errno);
    memcpy(copy, reinterpret_cast<const void *>(start), span);
    memcpy(static_cast<unsigned char *>(copy) + (address - start), bytes, length);

    // Android permits application JIT/anonymous executable memory. It forbids
    // execmod: restoring execute permission to a modified file-backed page.
    // Never change the source file mapping's permissions or make any page RWX.
    if (mprotect(copy, span, PROT_READ | PROT_EXEC)) {
        const int error = errno;
        munmap(copy, span);
        return reject("anonymous_execute", error);
    }
    void *result = mremap(copy, span, span, MREMAP_MAYMOVE | MREMAP_FIXED,
                          reinterpret_cast<void *>(start));
    if (result == MAP_FAILED) {
        const int error = errno;
        munmap(copy, span);
        return reject("replace_mapping", error);
    }
    __builtin___clear_cache(reinterpret_cast<char *>(start), reinterpret_cast<char *>(start + span));
    return true;
}

} // namespace peerlink
