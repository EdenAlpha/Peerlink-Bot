#pragma once
#include <cstddef>
bool peerlink_open_diagnostic_journal(const char *path);
void peerlink_journal(const char *format, ...) __attribute__((format(printf, 1, 2)));
