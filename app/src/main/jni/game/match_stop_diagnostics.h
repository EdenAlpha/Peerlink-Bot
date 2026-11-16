#pragma once
#include <cstddef>
#include <cstdint>

// Build-B observers. Forward original calls unchanged and record the first
// command-controller stop transition; never request, clear, or bypass a stop.
void peerlink_observe_match_stop(uintptr_t base);
void peerlink_match_stop_diagnostics(uintptr_t controller, char *text, size_t size);
