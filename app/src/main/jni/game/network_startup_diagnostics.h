#pragma once
#include <cstddef>
#include <cstdint>

// Observe the pinned SDK's original address resolver. Failure to install an
// observer must never prevent startup or change the networking inputs.
void peerlink_observe_network_startup(uintptr_t base);
void peerlink_network_startup_diagnostics(uintptr_t client, char *text, size_t size);
