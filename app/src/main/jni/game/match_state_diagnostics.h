#pragma once
#include <cstdint>
#include <string>

void peerlink_match_state_begin(uintptr_t base);
void peerlink_observe_match_frames(uintptr_t base);
void peerlink_record_check_frame(uint32_t frame, uint32_t value);
void peerlink_record_check_comparison(uintptr_t checker, uintptr_t context);
void peerlink_record_ball_park(uintptr_t ball, uintptr_t position, uintptr_t group,
                              uintptr_t transition, uintptr_t flow);
void peerlink_match_state_sample_initial();
void peerlink_match_check_request(uintptr_t controller, uintptr_t checker,
                                 uintptr_t context, uintptr_t caller);
bool peerlink_match_check_failed();
std::string peerlink_match_state_diagnostics();
