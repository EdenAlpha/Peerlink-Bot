#include "../../app/src/main/jni/game/preview_policy.h"
#include <cassert>
#include <cstdio>

int main() {
    using namespace peerlink_preview;
    View v{true, 7, 0, true, true, true, false, false};
    assert(decide(v) == Action::Wait); // Phone flow leaf exists, menu not open.
    v.idle_settled = true;
    assert(decide(v) == Action::RetryMenu); // QEMU's recovered idle request.
    v.retried = true;
    assert(decide(v) == Action::Wait); // Never repeatedly close/reopen it.
    v.menu_state = 6;
    assert(decide(v) == Action::Continue); // Flow 7 normally awaits the user.
    for (unsigned loading = 1; loading < 6; ++loading) {
        v.menu_state = loading;
        assert(decide(v) == Action::Wait);
    }
    v.menu_state = 0; v.retried = false; v.manager_idle = false;
    assert(decide(v) == Action::Wait);
    v.menu_state = 6;
    const unsigned other_flows[] = {0U,1U,2U,3U,4U,5U,6U,8U};
    for (unsigned flow : other_flows) {
        v.flow_state = flow; assert(decide(v) == Action::Wait);
    }
    v.flow_state = 7; v.preview_leaf = false;
    assert(decide(v) == Action::Wait);
    v.preview_leaf = true; v.requested_preview = false;
    assert(decide(v) == Action::Wait);
    v.requested_preview = true; v.current_nil = false;
    assert(decide(v) == Action::Wait);
    puts("Preview lifecycle gates passed: loading waits, idle retry is bounded, ready Continue requires the original menu state");
}
