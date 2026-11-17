#pragma once
#include <cstdint>

namespace peerlink {
// Original command-controller state; defaults deny a button press while the
// controller is absent or its readiness cannot be established.
struct InputReadiness {
    bool valid = false;
    uint32_t frame = 0;
    uint32_t waiting = UINT32_MAX;
    unsigned tick_gate = 0, pending_stop = 1, stopped = 1;
    bool ready() const {
        return valid && frame > 0 && waiting == 0 && tick_gate == 1 &&
            pending_stop == 0 && stopped == 0;
    }
};
}
