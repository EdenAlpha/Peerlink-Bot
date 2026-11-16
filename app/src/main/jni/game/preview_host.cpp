// Pinned 11.1.1 menu inputs, dispatched on the original menu actor's tick.
// The temporary slot is restored before calling the original tick. No engine
// readiness, network verdict or match-progress field is assigned here.
#include "preview_policy.h"
#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <unistd.h>
#include <sys/mman.h>

extern "C" long peerlink_original_preview_proceed_v271(uintptr_t);
extern "C" long peerlink_original_demo_proceed(uintptr_t);
extern "C" long peerlink_original_demo_finish(uintptr_t);
namespace {
using uptr = uintptr_t;
using Tick = void (*)(uptr, float);
uptr base_address = 0, target = 0;
uptr *slot = nullptr;
Tick original = nullptr;
std::atomic<int> active{0}, mode{0}, sent{0}, retry_count{0}, restored{1};
std::atomic<int> demo_sent{0};
std::atomic<int> demo_finished{0};
std::atomic<int> probe_done{0}, request_tid{0}, callback_tid{0};
std::atomic<int> last_flow{-1}, last_menu{-1}, last_requested{-1}, last_current{-1};
std::atomic<unsigned> last_busy{0};
std::atomic<uint64_t> idle_since{0}, started{0};

uint64_t now_ms() {
    timespec value{}; clock_gettime(CLOCK_MONOTONIC, &value);
    return static_cast<uint64_t>(value.tv_sec) * 1000 + value.tv_nsec / 1000000;
}
bool exact16(uptr object, const char *text) {
    auto *data = *reinterpret_cast<const uint16_t **>(object);
    unsigned length = *reinterpret_cast<const unsigned *>(object + 8);
    size_t expected = strlen(text);
    if (!data || length != expected + 1 || length > 64) return false;
    for (unsigned i = 0; i < length; ++i)
        if (data[i] != static_cast<unsigned char>(text[i])) return false;
    return true;
}
bool flow_leaf(uptr base, const char *name, unsigned &flow_state) {
    uptr flow = *reinterpret_cast<uptr *>(base + 0xa53b6e8);
    uptr task = flow ? *reinterpret_cast<uptr *>(flow + 0x190) : 0;
    if (!task) return false;
    flow_state = *reinterpret_cast<unsigned *>(flow + 0x120);
    uptr first = *reinterpret_cast<uptr *>(task + 0x70), end = *reinterpret_cast<uptr *>(task + 0x78);
    if (!first || end < first + 0xe0 || (end - first) % 0xe0 || end - first > 0x1c00) return false;
    auto *leaf = reinterpret_cast<const unsigned char *>(end - 0xe0);
    auto *words = reinterpret_cast<const uptr *>(leaf);
    size_t count = leaf[0] & 1 ? words[1] : leaf[0] >> 1;
    const char *text = leaf[0] & 1 ? reinterpret_cast<const char *>(words[2]) : reinterpret_cast<const char *>(leaf + 1);
    return count == strlen(name) && text && !memcmp(text, name, count);
}
bool preview_leaf(uptr base, unsigned &flow_state) {
    return flow_leaf(base, "Match/Settings/MatchPreview", flow_state);
}
bool writable(bool yes) {
    long size = sysconf(_SC_PAGESIZE);
    if (!slot || size < 4096 || size > 65536 || (size & (size - 1))) return false;
    return !mprotect(reinterpret_cast<void *>(reinterpret_cast<uptr>(slot) & ~(static_cast<uptr>(size) - 1)),
                     static_cast<size_t>(size), yes ? PROT_READ | PROT_WRITE : PROT_READ);
}
bool restore() {
    if (!slot || !original) return false;
    if (!writable(true)) return false;
    __atomic_store_n(slot, reinterpret_cast<uptr>(original), __ATOMIC_RELEASE);
    bool ok = writable(false);
    restored.store(ok ? 1 : 0, std::memory_order_release);
    return ok;
}
struct Lock {
    uptr manager;
    Lock() : manager(reinterpret_cast<uptr (*)(unsigned)>(base_address + 0x822933c)(1)) {
        if (manager) reinterpret_cast<void (*)(uptr)>(base_address + 0x82298c8)(manager);
    }
    ~Lock() { if (manager) reinterpret_cast<void (*)(uptr)>(base_address + 0x82298d0)(manager); }
};
void menu_tick(uptr actor, float delta) {
    Tick tick = original;
    if (actor != target || active.load(std::memory_order_acquire) != 1) { tick(actor, delta); return; }
    // Retain the executing state until the callback ends so the host cannot
    // install a second request while this callback is still using its inputs.
    active.store(2, std::memory_order_release);
    bool restore_ok = false;
    {
        // arm() is called under this same recursive lock. Serialize the two
        // protection changes so one thread cannot make the page read-only
        // while the other is restoring the pointer.
        Lock lock;
        restore_ok = lock.manager && restore();
    }
    if (!restore_ok) { active.store(-1, std::memory_order_release); tick(actor, delta); return; }
    tick(actor, delta);
    Lock lock;
    if (!lock.manager) { active.store(0, std::memory_order_release); return; }
    callback_tid.store(gettid(), std::memory_order_relaxed);
    unsigned flow_state = 0;
    if (mode.load() == 3) {
        bool demo = flow_leaf(base_address, "Match/Enter/MatchEnterDemoOnline", flow_state);
        if (demo && flow_state == 7) {
            if (!demo_sent.load() && peerlink_original_demo_proceed(base_address) == 1) {
                demo_sent.store(1, std::memory_order_release);
                dprintf(2, "HEADLESS_INTRO_PROCEED original_event=1 gameThreadTid=%d\n", gettid());
            }
            if (demo_sent.load() && peerlink_original_demo_finish(base_address) == 1) {
                demo_finished.store(1, std::memory_order_release);
                dprintf(2, "HEADLESS_INTRO_FINISHED original_callbacks=1 gameThreadTid=%d\n", gettid());
            }
        }
        active.store(0, std::memory_order_release); return;
    }
    bool leaf = preview_leaf(base_address, flow_state);
    uptr manager = *reinterpret_cast<uptr *>(actor + 0x290);
    unsigned state = *reinterpret_cast<unsigned *>(actor + 0x28c);
    bool requested = exact16(actor + 0x298, "Match/Settings/MatchPreview");
    bool current = exact16(actor + 0x2b0, "nil");
    unsigned busy = manager ?
        (*reinterpret_cast<unsigned *>(manager + 0x398) ? 1 : 0) |
        (*reinterpret_cast<unsigned *>(manager + 0x4d0) ? 2 : 0) |
        (*reinterpret_cast<unsigned *>(manager + 0x4e0) ? 4 : 0) : 8;
    last_flow.store(static_cast<int>(flow_state)); last_menu.store(static_cast<int>(state));
    last_requested.store(requested); last_current.store(current); last_busy.store(busy);
    if (mode.load() == 1) {
        probe_done.store(1, std::memory_order_release);
        active.store(0, std::memory_order_release); return;
    }
    if (!manager) { active.store(0, std::memory_order_release); return; }
    uint64_t now = now_ms();
    bool idle = leaf && flow_state == 7 && state == 0 && requested && current && !busy;
    uint64_t since = idle_since.load();
    if (!idle) idle_since.store(0);
    else if (!since) { since = now; idle_since.store(now); }
    auto action = peerlink_preview::decide({leaf, flow_state, state, requested, current,
        manager && !busy, retry_count.load() != 0, idle && now - since >= 2000});
    if (action == peerlink_preview::Action::RetryMenu) {
        // Same original close/request pair used by the successful QEMU host.
        uint16_t copy[64];
        auto *requested_text = *reinterpret_cast<const uint16_t **>(actor + 0x298);
        unsigned count = *reinterpret_cast<unsigned *>(actor + 0x2a0);
        for (unsigned i = 0; i < count; ++i) copy[i] = requested_text[i];
        struct { uint16_t *data; unsigned length, capacity; } saved{copy, count, count};
        unsigned first = *reinterpret_cast<unsigned char *>(actor + 0x2a8);
        unsigned second = *reinterpret_cast<unsigned char *>(actor + 0x2a9);
        reinterpret_cast<void (*)(uptr, unsigned)>(base_address + 0x3ad094c)(actor, first);
        reinterpret_cast<void (*)(uptr, void *, unsigned, unsigned)>(base_address + 0x3ad089c)(actor, &saved, first, second);
        retry_count.fetch_add(1);
        dprintf(2, "PREVIEW_MENU_RETRY original_api=1 gameThreadTid=%d\n", gettid());
    } else if (action == peerlink_preview::Action::Continue) {
        if (peerlink_original_preview_proceed_v271(base_address) == 1) {
            sent.store(1, std::memory_order_release);
            dprintf(2, "PREVIEW_CONTINUE_QUEUED menuState=6 flowState=7 gameThreadTid=%d\n", gettid());
        }
    }
    active.store(0, std::memory_order_release);
}
int arm(uptr base, int requested_mode) {
    int state = active.load(std::memory_order_acquire);
    if (state < 0) return -1;
    if (state) return 0;
    uptr holder = *reinterpret_cast<uptr *>(base + 0x99e6830);
    uptr actor = holder ? *reinterpret_cast<uptr *>(holder) : 0;
    if (!actor || (*reinterpret_cast<unsigned *>(actor + 8) & 0x30)) return 0;
    auto *candidate = reinterpret_cast<uptr *>(*reinterpret_cast<uptr *>(actor) + 0x460);
    if (__atomic_load_n(candidate, __ATOMIC_ACQUIRE) != base + 0x3ac97c8) return -1;
    base_address = base; target = actor; slot = candidate;
    original = reinterpret_cast<Tick>(base + 0x3ac97c8);
    mode.store(requested_mode); request_tid.store(gettid());
    if (!writable(true)) return -1;
    restored.store(0); active.store(1, std::memory_order_release);
    __atomic_store_n(slot, reinterpret_cast<uptr>(menu_tick), __ATOMIC_RELEASE);
    if (!writable(false)) { restore(); active.store(-1); return -1; }
    return 0;
}
}
extern "C" long peerlink_preview_drive(uintptr_t base) {
    if (!base) return -1;
    if (sent.load(std::memory_order_acquire)) return 1;
    unsigned flow = 0;
    if (!preview_leaf(base, flow)) return 0;
    if (!started.load()) started.store(now_ms());
    if (now_ms() - started.load() > 300000) return -2;
    return arm(base, 2);
}
extern "C" long peerlink_demo_drive(uintptr_t base) {
    if (!base) return -1;
    if (demo_finished.load(std::memory_order_acquire)) return 1;
    unsigned flow = 0;
    if (!flow_leaf(base, "Match/Enter/MatchEnterDemoOnline", flow) || flow != 7) return 0;
    if (demo_sent.load(std::memory_order_acquire)) {
        uptr match = *reinterpret_cast<uptr *>(base + 0xa533ec0);
        if (!match || *reinterpret_cast<unsigned char *>(match + 0x138) != 13) return 0;
    }
    return arm(base, 3);
}
extern "C" long peerlink_preview_probe(uintptr_t base) {
    if (probe_done.load(std::memory_order_acquire)) return 1;
    return base ? arm(base, 1) : -1;
}
extern "C" void peerlink_preview_stop() {
    if (!base_address) return;
    Lock lock;
    if (!lock.manager) return;
    if (active.load() == 1) { restore(); active.store(0); }
}
extern "C" void peerlink_preview_diagnostics(char *text, size_t size) {
    snprintf(text, size, "previewMenuState=%d previewMenuFlow=%d previewRequested=%d previewCurrentNil=%d previewManagerBusy=%u previewMenuRetries=%d previewContinueQueued=%d introProceedQueued=%d introFinished=%d previewCallbackActive=%d previewCallbackRestored=%d previewProbeDone=%d previewRequestTid=%d previewCallbackTid=%d",
        last_menu.load(), last_flow.load(), last_requested.load(), last_current.load(), last_busy.load(),
        retry_count.load(), sent.load(), demo_sent.load(), demo_finished.load(), active.load(), restored.load(), probe_done.load(), request_tid.load(), callback_tid.load());
}
