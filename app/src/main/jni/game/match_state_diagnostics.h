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
void peerlink_record_ball_park(uintptr_t ball, uintptr_t position, uintptr_t group,
                              uintptr_t transition, uintptr_t flow);
void peerlink_match_state_sample_initial();
void peerlink_match_check_request(uintptr_t controller, uintptr_t checker,
                                 uintptr_t context, uintptr_t caller);
bool peerlink_match_check_failed();
std::string peerlink_match_state_diagnostics();
