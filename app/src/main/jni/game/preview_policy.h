#pragma once

namespace peerlink_preview {
enum class Action { Wait, RetryMenu, Continue };
struct View {
    bool preview_leaf;
    unsigned flow_state;
    unsigned menu_state;
    bool requested_preview;
    bool current_nil;
    bool manager_idle;
    bool retried;
    bool idle_settled;
};
inline Action decide(const View &v) {
    if (!v.preview_leaf || !v.requested_preview || !v.current_nil) return Action::Wait;
    // Flow state 7 waits for the current client to finish, including user
    // Continue. It is normal while a menu is open. Actor state 6 separately
    // identifies the completed menu-opening lifecycle (3ac97c8 cases 5/6).
    if (v.flow_state == 7 && v.menu_state == 6) return Action::Continue;
    if (v.flow_state == 7 && v.menu_state == 0 && v.manager_idle &&
        !v.retried && v.idle_settled) return Action::RetryMenu;
    return Action::Wait;
}
}
