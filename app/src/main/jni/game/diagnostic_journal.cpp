#include "diagnostic_journal.h"
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <fcntl.h>
#include <time.h>
#include <unistd.h>

namespace {
struct Journal {
    std::atomic<int> output{-1};
    std::atomic<size_t> written{0};
};
Journal events, keeper, restart;
constexpr size_t limit = 2 * 1024 * 1024;
struct PreserveErrno { int value = errno; ~PreserveErrno() { errno = value; } };

bool open_journal(Journal &journal, const char *path, void (*record)(const char *, ...)) {
    PreserveErrno guard;
    if (journal.output.load() >= 0) return true;
    int fd = open(path, O_WRONLY | O_CREAT | O_EXCL | O_APPEND | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return false;
    int expected = -1;
    if (journal.output.compare_exchange_strong(expected, fd)) record("PROCESS_START");
    else close(fd);
    return true;
}

void append(Journal &journal, const char *format, va_list args) {
    PreserveErrno guard;
    const int fd = journal.output.load(std::memory_order_acquire);
    if (fd < 0 || journal.written.load(std::memory_order_relaxed) >= limit) return;
    timespec wall{}, monotonic{};
    clock_gettime(CLOCK_REALTIME, &wall);
    clock_gettime(CLOCK_MONOTONIC, &monotonic);
    char line[16384];
    int prefix = snprintf(line, sizeof(line), "timeMs=%llu elapsedMs=%llu pid=%d ",
        static_cast<unsigned long long>(wall.tv_sec) * 1000 + wall.tv_nsec / 1000000,
        static_cast<unsigned long long>(monotonic.tv_sec) * 1000 + monotonic.tv_nsec / 1000000,
        getpid());
    if (prefix < 0 || static_cast<size_t>(prefix) >= sizeof(line) - 2) return;
    int result = vsnprintf(line + prefix, sizeof(line) - prefix - 1, format, args);
    if (result < 0) return;
    size_t count = static_cast<size_t>(prefix) + static_cast<size_t>(result);
    if (count > sizeof(line) - 2) count = sizeof(line) - 2;
    line[count++] = '\n';
    // One unbuffered append: the kernel retains this after process death.
    // No native pointers, account credentials, or session payloads are logged.
    ssize_t accepted;
    do { accepted = write(fd, line, count); } while (accepted < 0 && errno == EINTR);
    if (accepted > 0) journal.written.fetch_add(static_cast<size_t>(accepted), std::memory_order_relaxed);
}
}

bool peerlink_open_diagnostic_journal(const char *path) {
    return open_journal(events, path, peerlink_journal);
}

bool peerlink_open_keeper_journal(const char *path) {
    return open_journal(keeper, path, peerlink_keeper_journal);
}

bool peerlink_keeper_journal_active() {
    return keeper.output.load(std::memory_order_acquire) >= 0 &&
        keeper.written.load(std::memory_order_relaxed) < limit;
}

void peerlink_journal(const char *format, ...) {
    va_list args;
    va_start(args, format);
    append(events, format, args);
    va_end(args);
}

void peerlink_keeper_journal(const char *format, ...) {
    va_list args;
    va_start(args, format);
    append(keeper, format, args);
    va_end(args);
}

bool peerlink_open_restart_journal(const char *path) {
    return open_journal(restart, path, peerlink_restart_journal);
}

bool peerlink_restart_journal_active() {
    return restart.output.load(std::memory_order_acquire) >= 0 &&
        restart.written.load(std::memory_order_relaxed) < limit;
}

void peerlink_restart_journal(const char *format, ...) {
    va_list args;
    va_start(args, format);
    append(restart, format, args);
    va_end(args);
}
