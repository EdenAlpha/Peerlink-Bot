#pragma once
#include <cstddef>
bool peerlink_open_diagnostic_journal(const char *path);
void peerlink_journal(const char *format, ...) __attribute__((format(printf, 1, 2)));
bool peerlink_open_keeper_journal(const char *path);
bool peerlink_keeper_journal_active();
void peerlink_keeper_journal(const char *format, ...) __attribute__((format(printf, 1, 2)));
bool peerlink_open_restart_journal(const char *path);
bool peerlink_restart_journal_active();
void peerlink_restart_journal(const char *format, ...) __attribute__((format(printf, 1, 2)));
