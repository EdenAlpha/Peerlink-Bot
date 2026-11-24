#pragma once
#include <cstdint>
#include <string>

void peerlink_match_state_begin(uintptr_t base);
void peerlink_observe_match_frames(uintptr_t base);
void peerlink_record_check_frame(uint32_t frame, uint32_t value);
void peerlink_record_check_comparison(uintptr_t checker, uintptr_t context);
void peerlink_record_scene_agreement(uintptr_t record, uintptr_t eligible_slots, uint32_t result);
void peerlink_record_scene_decision(uintptr_t scene, uint32_t channel, uint32_t result);
struct PeerLinkSceneStartSample {
    uint32_t command_frame = 0;
    uint32_t scene_id = 0;
    uint32_t actor_mode = 0;
    bool actor_verified = false;
    bool start_requested = false;
    bool binding_present = false;
    bool interrupted = false;
    bool manager_present = false;
    bool manager_finishes_early = false;
    unsigned transition_flag = 0;
};
PeerLinkSceneStartSample peerlink_sample_scene_start(uintptr_t scene, uintptr_t transition);
void peerlink_record_scene_start(const char *stage, const PeerLinkSceneStartSample &before,
                                 const PeerLinkSceneStartSample &after);
void peerlink_record_scene_clock_source(uint32_t scene_id, bool timeline_loaded);
struct PeerLinkKeeperSceneTick {
    uint32_t number = 0;
    uint32_t frame = 0;
    uint32_t step = 0;
    bool sample = false;
};
PeerLinkKeeperSceneTick peerlink_keeper_scene_enter(uintptr_t scene, uintptr_t context, uintptr_t transition);
void peerlink_keeper_scene_leave(const PeerLinkKeeperSceneTick &tick, uintptr_t scene, uintptr_t context,
                                 uintptr_t transition, bool finished);
void peerlink_keeper_clock_sample(uintptr_t timeline_slot, bool timeline_loaded);
void peerlink_keeper_targets_enter(uintptr_t transition, const PeerLinkSceneStartSample &sample);
void peerlink_keeper_targets_leave();
void peerlink_record_keeper_mode_write(uintptr_t transition, uint32_t player, uint32_t mode, uintptr_t caller);
void peerlink_record_ball_park(uintptr_t ball, uintptr_t position, uintptr_t group,
                              uintptr_t transition, uintptr_t flow);
void peerlink_match_check_request(uintptr_t controller, uintptr_t checker,
                                 uintptr_t context, uintptr_t caller);
bool peerlink_match_check_failed();
std::string peerlink_match_state_diagnostics();
