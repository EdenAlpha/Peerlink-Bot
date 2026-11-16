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

    // Physical phone and successful no-extra-file HOME both waited here.
    // Only the lab survived long enough for the later automatic transition.
    View no_graphics{true, 7, 5, true, true, false, true, false, true, 1, true};
    assert(decide(no_graphics) == Action::ContinueHeadless);
    no_graphics.headless_inputs_ready = false;
    assert(decide(no_graphics) == Action::Wait); // Normal renderer or incomplete real synchronization.
    no_graphics.headless_inputs_ready = true; no_graphics.graphics_wait_settled = false;
    assert(decide(no_graphics) == Action::Wait); // Let normal menu loading finish first.
    no_graphics.graphics_wait_settled = true;
    const unsigned other_busy[] = {0U, 2U, 3U, 4U, 5U, 6U, 7U, 8U};
    for (unsigned busy : other_busy) {
        no_graphics.manager_busy = busy;
        assert(decide(no_graphics) == Action::Wait); // Do not skip a different menu operation.
    }
    no_graphics.manager_busy = 1; no_graphics.flow_state = 8;
    assert(decide(no_graphics) == Action::Wait);
    no_graphics.flow_state = 7; no_graphics.preview_leaf = false;
    assert(decide(no_graphics) == Action::Wait);
    no_graphics.preview_leaf = true; no_graphics.requested_preview = false;
    assert(decide(no_graphics) == Action::Wait);
    no_graphics.requested_preview = true; no_graphics.current_nil = false;
    assert(decide(no_graphics) == Action::Wait);

    VisualStartView start{true, true, 12, false, true, true, true, true, true};
    assert(should_complete_visual_start(start));
    start.intro_finished = false; assert(!should_complete_visual_start(start)); start.intro_finished = true;
    start.intro_leaf_completed = false; assert(!should_complete_visual_start(start)); start.intro_leaf_completed = true;
    start.match_phase = 13; assert(!should_complete_visual_start(start)); start.match_phase = 12;
    start.match_gate_open = true; assert(!should_complete_visual_start(start)); start.match_gate_open = false;
    start.original_ready = false; assert(!should_complete_visual_start(start)); start.original_ready = true;
    start.hud_complete = false; assert(!should_complete_visual_start(start)); start.hud_complete = true;
    start.visual_idle = false; assert(!should_complete_visual_start(start)); start.visual_idle = true;
    start.event_manager_present = false; assert(!should_complete_visual_start(start)); start.event_manager_present = true;
    start.settled = false; assert(!should_complete_visual_start(start));

    RenderedStartView rendered{true,true,true,0,12,false,true,true,8,true,true,false,true};
    assert(should_drive_rendered_start(rendered));
    rendered.stranded_menu=false;
    assert(!should_drive_rendered_start(rendered)); // Do not interrupt an active UI.
    rendered.stop_requested=true;
    assert(should_drive_rendered_start(rendered)); // Continue observing its real acknowledgement.
    rendered.visual_state=0;
    assert(should_drive_rendered_start(rendered));
    rendered.visual_state=3;
    assert(!should_drive_rendered_start(rendered)); // Presentation still loading.
    rendered.visual_state=0;rendered.rendered=false;
    assert(!should_drive_rendered_start(rendered)); // Keep the separate verified Null driver path.
    rendered.rendered=true;rendered.synchronized=false;
    assert(!should_drive_rendered_start(rendered));
    rendered.synchronized=true;rendered.hud_complete=false;
    assert(!should_drive_rendered_start(rendered));
    rendered.hud_complete=true;rendered.match_gate_open=true;
    assert(!should_drive_rendered_start(rendered)); // The game has already accepted start.
    puts("Preview lifecycle gates passed: normal loading waits; verified headless inputs can Continue through the exact optional menu wait");
}
