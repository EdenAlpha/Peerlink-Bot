#pragma once
#include <cstdint>

void peerlink_record_restart_frame(uintptr_t base, uintptr_t controller, uintptr_t decoded_slots);
void peerlink_record_restart_comparison(uint32_t frame, uint16_t local, uint16_t peer);
void peerlink_flush_restart_trace();
