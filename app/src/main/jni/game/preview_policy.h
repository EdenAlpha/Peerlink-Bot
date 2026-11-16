#pragma once

namespace peerlink_preview {
enum class Action { Wait, RetryMenu, Continue, ContinueHeadless };
struct View {
    bool preview_leaf;
    unsigned flow_state;
    unsigned menu_state;
    bool requested_preview;
    bool current_nil;
    bool manager_idle;
    bool retried;
    bool idle_settled;
    bool headless_inputs_ready = false;
    unsigned manager_busy = 0;
    bool graphics_wait_settled = false;
};
inline Action decide(const View &v) {
    if (!v.preview_leaf || !v.requested_preview || !v.current_nil) return Action::Wait;
    // Flow state 7 waits for the current client to finish, including user
    // Continue. It is normal while a menu is open. Actor state 6 separately
    // identifies the completed menu-opening lifecycle (3ac97c8 cases 5/6).
    if (v.flow_state == 7 && v.menu_state == 6) return Action::Continue;
    // A no-render preview may keep loading its optional menu indefinitely.
    // The original flow can leave this same state on its countdown. Submit
    // its normal Continue event once the real match inputs are synchronized;
    // do not mark the menu, players or transport ready.
    if (v.flow_state == 7 && v.menu_state == 5 && v.headless_inputs_ready &&
        v.manager_busy == 1 && v.graphics_wait_settled) return Action::ContinueHeadless;
    if (v.flow_state == 7 && v.menu_state == 0 && v.manager_idle &&
        !v.retried && v.idle_settled) return Action::RetryMenu;
    return Action::Wait;
}

struct VisualStartView {
    bool intro_finished;
    bool intro_leaf_completed;
    unsigned match_phase;
    bool match_gate_open;
    bool original_ready;
    bool hud_complete;
    bool visual_idle;
    bool event_manager_present;
    bool settled;
};

// The original visual-start task posts this event when its animation ends.
// A headless match may only replace that visual completion after the game's
// own setup and HUD task are complete and the match is still waiting for it.
inline bool should_complete_visual_start(const VisualStartView &v) {
    return v.intro_finished && v.intro_leaf_completed && v.match_phase == 12 &&
        !v.match_gate_open && v.original_ready && v.hud_complete && v.visual_idle &&
        v.event_manager_present && v.settled;
}

struct RenderedStartView {
    bool rendered;
    bool synchronized;
    bool intro_leaf;
    unsigned flow_state;
    unsigned match_phase;
    bool match_gate_open;
    bool original_ready;
    bool hud_complete;
    unsigned visual_state;
    bool event_manager_present;
    bool stranded_menu;
    bool stop_requested;
    bool settled;
};

// A hosted rendered peer can finish its flow before its visual-start UI task
// is created. Reproduce that original task (33c8620/33c872c): request visual
// completion, await its real idle acknowledgement, then post its ready event.
inline bool should_drive_rendered_start(const RenderedStartView &v) {
    return v.rendered && v.synchronized && v.intro_leaf && v.flow_state == 0 &&
        v.match_phase == 12 && !v.match_gate_open && v.original_ready && v.hud_complete &&
        (v.visual_state == 0 || v.visual_state == 8) && v.event_manager_present &&
        (v.stop_requested || v.stranded_menu) && v.settled;
}
}
