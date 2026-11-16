#pragma once

#include <cstddef>
#include <cstdint>

namespace peerlink {

struct CodePatchFailure {
    const char *operation = "none";
    int error = 0;
    size_t page_size = 0;
};

// Called during engine startup, before the affected instructions are used.
// Prepare an anonymous copy and replace the complete page mapping atomically.
// A failed preparation/remap leaves the original executable mapping intact.
bool replace_executable_bytes(uintptr_t address, const void *bytes, size_t length,
                              CodePatchFailure *failure = nullptr);

} // namespace peerlink
