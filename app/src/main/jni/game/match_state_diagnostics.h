#pragma once
#include <cstdint>
#include <string>

void peerlink_match_state_begin(uintptr_t base);
void peerlink_match_state_sample_initial();
void peerlink_match_check_request(uintptr_t controller, uintptr_t checker,
                                 uintptr_t context, uintptr_t caller);
bool peerlink_match_check_failed();
std::string peerlink_match_state_diagnostics();
