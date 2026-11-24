#include "diagnostic_journal.h"
#include <cassert>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

int main() {
    char directory[] = "/tmp/peerlink-journal-XXXXXX";
    assert(mkdtemp(directory));
    const std::string path = std::string(directory) + "/events.log";
    const std::string keeper_path = std::string(directory) + "/keeper.log";
    pid_t child = fork();
    assert(child >= 0);
    if (!child) {
        errno = ENOTTY;
        assert(peerlink_open_diagnostic_journal(path.c_str()));
        assert(peerlink_open_keeper_journal(keeper_path.c_str()));
        assert(peerlink_keeper_journal_active());
        assert(errno == ENOTTY);
        peerlink_journal("MATCH_CHECK_MISMATCH compareFrame=%u localValue=%u peerValue=%u", 54U, 28620U, 28948U);
        // Exhaust the keeper log's own quota. The next regular event must survive.
        for (unsigned i = 0; i < 512 && peerlink_keeper_journal_active(); ++i)
            peerlink_keeper_journal("KEEPER_SCENE_STATE commandFrame=%u %08000d", i, 0);
        assert(!peerlink_keeper_journal_active());
        peerlink_journal("MATCH_STOP_EVENT path=local_request");
        assert(errno == ENOTTY);
        // No Java/Binder reply, destructor, close, or stdio flush is possible.
        kill(getpid(), SIGKILL);
        _exit(99);
    }
    int status = 0;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFSIGNALED(status) && WTERMSIG(status) == SIGKILL);
    std::ifstream input(path);
    std::string text{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
    assert(text.find("PROCESS_START") != std::string::npos);
    assert(text.find("pid=" + std::to_string(child)) != std::string::npos);
    assert(text.find("MATCH_CHECK_MISMATCH compareFrame=54 localValue=28620 peerValue=28948") != std::string::npos);
    assert(text.find("MATCH_STOP_EVENT path=local_request") != std::string::npos);
    assert(text.find("KEEPER_SCENE_STATE") == std::string::npos);
    std::ifstream keeper(keeper_path);
    std::string sample;
    std::getline(keeper, sample);
    assert(sample.find("PROCESS_START") != std::string::npos);
    std::getline(keeper, sample);
    assert(sample.find("KEEPER_SCENE_STATE commandFrame=0 ") != std::string::npos);
    struct stat info{};
    assert(stat(path.c_str(), &info) == 0 && (info.st_mode & 0777) == 0600);
    assert(stat(keeper_path.c_str(), &info) == 0 && (info.st_mode & 0777) == 0600);
    unlink(path.c_str());
    unlink(keeper_path.c_str());
    rmdir(directory);
    puts("NATIVE_FAILURE_JOURNAL_SURVIVES_SIGKILL_AND_PRESERVES_ERRNO_PASS");
}
