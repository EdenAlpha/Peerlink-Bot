#include "input_readiness.h"
#include <cassert>
#include <cstdio>

int main() {
    using peerlink::InputReadiness;
    assert(!InputReadiness{}.ready());
    // The exact waiting condition in the physical phone export, despite a
    // constructed match, ball at the centre and enabled controllers.
    InputReadiness waiting{true, 0, 2, 0, 0, 0};
    assert(!waiting.ready());
    InputReadiness running{true, 27, 0, 1, 0, 0};
    assert(running.ready());
    for (unsigned field = 0; field < 6; ++field) {
        auto changed = running;
        switch (field) {
            case 0: changed.valid = false; break;
            case 1: changed.frame = 0; break;
            case 2: changed.waiting = 2; break;
            case 3: changed.tick_gate = 0; break;
            case 4: changed.pending_stop = 1; break;
            case 5: changed.stopped = 1; break;
        }
        assert(!changed.ready());
    }
    puts("INPUT_GATE_REJECTS_RECORDED_FRAME_ZERO_WAIT_AND_STOP_PASS");
}
