// Original build-B entry points, owned input DTOs, and real Android JNI input.
// No fake JavaVM or connection-state writes. Preview inputs temporarily wrap
// the menu tick and restore it before forwarding the original callback.
#include <jni.h>
#include <dlfcn.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <array>
#include <atomic>
#include <android/native_activity.h>
#include <android/native_window_jni.h>
#include <android/input.h>
#include <android/api-level.h>
#include <arpa/inet.h>
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <ctime>
#include <string>
#include <cmath>
#include <malloc.h>
#include <mutex>
#include <pthread.h>
#include <vector>
#include <utility>
#include <unistd.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include "process_memory.h"
#include "game/network_startup_diagnostics.h"
#include "game/match_lifecycle_diagnostics.h"
#include "game/match_stop_diagnostics.h"
#include "game/match_state_diagnostics.h"
#include "game/diagnostic_journal.h"
#include "game/input_readiness.h"
#include "game/edit_work_merge.h"
#include "game/match_pool_memory.h"
#include "game/received_squad.h"

extern "C" {
long peerlink_game_database_input(uintptr_t, const char *);
int peerlink_squad_import(uintptr_t, uint32_t, uint32_t, const char *);
int peerlink_player_cache_import(uintptr_t, uint32_t, const char *);
uint32_t peerlink_lab_uid_participants(uintptr_t, uint32_t, uint32_t, uint32_t);
uintptr_t peerlink_original_online_inputs_v224(uintptr_t, uint32_t, uint32_t);
long peerlink_live_match_context_input(uintptr_t, const char *);
long peerlink_room_rules_input(uintptr_t, const char *);
const char *peerlink_room_rules_diagnostics();
long peerlink_original_preview_proceed_v271(uintptr_t);
long peerlink_preview_drive(uintptr_t, bool);
long peerlink_demo_drive(uintptr_t);
long peerlink_visual_start_drive(uintptr_t);
long peerlink_reference_intro_drive(uintptr_t, bool);
long peerlink_preview_probe(uintptr_t);
void peerlink_preview_stop();
void peerlink_preview_diagnostics(char *, size_t);
int peerlink_game_thread_tid();
int peerlink_halftime_menu_state(uintptr_t);
bool peerlink_install_null_client_rhi(uintptr_t, bool);
bool peerlink_null_client_rhi_ready(uintptr_t);
bool peerlink_null_client_rhi_failed(uintptr_t);
const char *peerlink_null_client_rhi_diagnostics();
}

#define TAG "PeerlinkEngineHost"
namespace {
constexpr uint64_t kEngineSize = 161937720ULL;
uintptr_t g_base = 0;
uintptr_t g_client = 0;
uint32_t g_uid = 0;
bool g_core_ready = false;
bool g_saved_database_attempted = false;
bool g_saved_database_ready = false;
std::string g_squad_path;
std::string g_match_context_path;
std::string g_room_rules_path;
int g_match_stage = 0;
uintptr_t g_user_sync_task = 0;
bool g_user_sync_complete = false;
uint64_t g_user_sync_started_ms = 0;
uintptr_t g_settings_sync_task = 0;
bool g_settings_sync_complete = false;
std::atomic<uint32_t> g_settings_sync_state{0};
uint64_t g_settings_sync_started_ms = 0;
std::array<uintptr_t, 10> g_settings_sync_vtable{};
uint64_t g_match_reporting_started_ms = 0;
bool g_pre_menu_started = false;

void settings_sync_tick(uintptr_t task) {
    // Preserve the original update. Its owner can delete a completed child
    // before the host's next poll, so retain the result before returning to
    // that owner. No native state or completion decision is changed.
    reinterpret_cast<void (*)(uintptr_t)>(g_base + 0x7b3f0f8)(task);
    const uint32_t state = *reinterpret_cast<uint32_t *>(task + 0x70);
    g_settings_sync_state.store(state, std::memory_order_release);
    if (state >= 4)
        __atomic_store_n(reinterpret_cast<uintptr_t *>(task), g_base + 0x990c778, __ATOMIC_RELEASE);
}

bool observe_settings_sync(uintptr_t task) {
    const uintptr_t table = g_base + 0x990c778;
    if (!task || *reinterpret_cast<uintptr_t *>(task) != table ||
        *reinterpret_cast<uintptr_t *>(table) != g_base + 0x7b3f0f8) return false;
    // The pinned task has eight dispatch slots. Preserve its two ABI prefix
    // words and every original dispatch address except Update, slot zero.
    std::memcpy(g_settings_sync_vtable.data(),
        reinterpret_cast<void *>(table - 2 * sizeof(uintptr_t)), sizeof(g_settings_sync_vtable));
    g_settings_sync_vtable[2] = reinterpret_cast<uintptr_t>(&settings_sync_tick);
    __atomic_store_n(reinterpret_cast<uintptr_t *>(task),
        reinterpret_cast<uintptr_t>(g_settings_sync_vtable.data() + 2), __ATOMIC_RELEASE);
    return true;
}
bool g_preview_proceeded = false;
bool g_controller_scanned = false;
int g_eager_memory_release = 0;
uint32_t g_channel_peer = 0;
uint32_t g_channel_home = 0;
uint32_t g_introduction_peer = 0;
uint32_t g_introduction_start = 0;

struct EngineString { uint64_t words[3]; };
struct EnginePair { EngineString identity, channel; };
struct TurnServerRow {
    uint32_t zone, padding;
    EngineString address;
    uint32_t port, tls_port;
};
static_assert(sizeof(TurnServerRow) == 40, "build-B TURN response row ABI");
static_assert(sizeof(EngineString) == 24 && sizeof(EnginePair) == 48, "build-B libc++ ABI");
static_assert(offsetof(ANativeActivity, vm) == 8 && offsetof(ANativeActivity, clazz) == 24,
              "build-B ANativeActivity ABI requires LP64");

template <typename Fn> Fn entry(uint64_t offset) {
    return reinterpret_cast<Fn>(g_base + offset);
}
bool valid(jlong base) { return g_base && static_cast<uintptr_t>(base) == g_base; }
ANativeActivity *framework_activity() {
    return g_base ? entry<ANativeActivity *(*)()>(0x2f4ebcc)() : nullptr;
}
void destroy_string(EngineString &value) {
    if (value.words[0] & 1) entry<void (*)(void *)>(0x2f376d4)(reinterpret_cast<void *>(value.words[2]));
}
void append_identity(void *vector, uint32_t uid) {
    char text[32];
    int size = snprintf(text, sizeof(text), "%u", uid);
    EnginePair pair{};
    auto construct = entry<void (*)(void *, const char *, size_t)>(0x2f3877c);
    construct(&pair.identity, text, static_cast<size_t>(size));
    construct(&pair.channel, "0", 1);
    entry<void (*)(void *, const void *)>(0x7b37d48)(vector, &pair);
    destroy_string(pair.identity);
    destroy_string(pair.channel);
}
bool build_address(void *destination, int family, const char *address, int port) {
    if (!destination || !address || (family != 1 && family != 2) || port < 0 || port > 65535) return false;
    size_t size = strnlen(address, 64);
    unsigned char binary[16]{};
    if (size == 64 || (!size && port) ||
        (size && inet_pton(family == 1 ? AF_INET : AF_INET6, address, binary) != 1)) return false;
    EngineString input{};
    entry<void (*)(void *, const char *, size_t)>(0x2f3877c)(&input, address, size);
    memset(destination, 0, 0x80);
    // The builder consumes the engine's libc++ string and returns void.
    entry<void (*)(int, void *, const EngineString *, uint32_t)>(0x7e04330)(
            family, destination, &input, static_cast<uint32_t>(port));
    destroy_string(input);
    return *static_cast<uint32_t *>(destination) == static_cast<uint32_t>(family);
}
uintptr_t session_manager() {
    uintptr_t holder = *reinterpret_cast<uintptr_t *>(g_base + 0xa535738);
    uintptr_t provider = holder ? *reinterpret_cast<uintptr_t *>(holder) : 0;
    return provider ? *reinterpret_cast<uintptr_t *>(provider + 0x100) : 0;
}
uintptr_t turn_service() {
    uintptr_t services = entry<uintptr_t (*)()>(0x7e0afbc)();
    return services ? *reinterpret_cast<uintptr_t *>(services + 0x50) : 0;
}
bool begin_introduction(uint32_t peer, uint32_t connecting_start) {
    if (g_introduction_peer)
        return g_introduction_peer == peer && g_introduction_start == connecting_start;
    uintptr_t sdk = entry<uintptr_t (*)()>(0x7db9468)();
    if (!sdk) return false;
    entry<void (*)(uintptr_t, uint32_t, uint32_t, uint32_t)>(0x7db99ec)(sdk, connecting_start, 0, 1);
    g_introduction_peer = peer;
    g_introduction_start = connecting_start;
    return true;
}

// Bounded, owned IPC input. Credentials and certificates never enter logs.
struct TurnInputReader {
    const unsigned char *data;
    size_t size, position = 0;
    bool ok = true;
    uint32_t number() {
        if (position > size || size - position < 4) { ok = false; return 0; }
        const auto *p = data + position;
        position += 4;
        return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
    }
    std::string blob(size_t maximum, bool allow_empty = false) {
        uint32_t count = number();
        if (!ok || (!count && !allow_empty) || count > maximum || position > size || size - position < count) {
            ok = false; return {};
        }
        std::string value(reinterpret_cast<const char *>(data + position), count);
        position += count;
        return value;
    }
};

struct TaskLock {
    uintptr_t manager;
    TaskLock() : manager(entry<uintptr_t (*)(uint32_t)>(0x822933c)(1)) {
        if (manager) entry<void (*)(uintptr_t)>(0x82298c8)(manager);
    }
    ~TaskLock() { if (manager) entry<void (*)(uintptr_t)>(0x82298d0)(manager); }
};

// Compare the active 11.1.1 database with the saved pack after the engine
// finishes loading. The stock database has 24,203 records and different
// parameter values for player key 15320.
bool matches_saved_database(uintptr_t owner, uint32_t expected_players) {
    if (!owner || !expected_players || *reinterpret_cast<const uint32_t *>(owner + 0x30) != 4) return false;
    const auto work = *reinterpret_cast<const uintptr_t *>(owner + 0x40);
    const auto edit = *reinterpret_cast<const uintptr_t *>(owner + 0x50);
    const auto players = work ? *reinterpret_cast<const uint32_t *>(work + 0x2686098) : 0;
    const auto appearances = edit ? *reinterpret_cast<const uint32_t *>(edit + 0x66b0c4) : 0;
    bool parameters_match = false;
    if (players == expected_players && appearances == expected_players) {
        struct PlayerIdentity { uint32_t id, padding; uint64_t key; };
        const auto player = work + 29 * 0x1d0;
        const auto identity = entry<PlayerIdentity (*)(uintptr_t)>(0x68fe720)(player);
        const auto parameter = entry<uint32_t (*)(uintptr_t, uint32_t, uint32_t *)>(0x82a84d4);
        uint32_t value0 = 0, value61 = 0;
        parameters_match = identity.key == 15320 &&
            parameter(player, 0, &value0) && value0 == 8 &&
            parameter(player, 61, &value61) && value61 == 5;
    }
    peerlink_journal("SAVED_DATABASE_VERIFIED players=%u appearances=%u parametersMatch=%u expectedPlayers=%u",
                     players, appearances, unsigned(parameters_match), expected_players);
    return parameters_match;
}

// Called under the original TaskLock. A constructed pitch can still be at
// command frame zero waiting for its peer, so construction is not input-ready.
peerlink::InputReadiness input_readiness() {
    peerlink::InputReadiness state;
    uintptr_t service = *reinterpret_cast<uintptr_t *>(g_base + 0x9a6a020);
    if (!service || *reinterpret_cast<uintptr_t *>(service) != g_base + 0x98ae9e0) return state;
    uintptr_t inner = *reinterpret_cast<uintptr_t *>(service + 8);
    uintptr_t controller = inner ? *reinterpret_cast<uintptr_t *>(inner + 0xa0) : 0;
    if (!controller || *reinterpret_cast<uintptr_t *>(controller) != g_base + 0x98aedd0) return state;
    uint32_t reason = 0;
    state.valid = true;
    state.frame = *reinterpret_cast<uint32_t *>(controller + 0x30);
    state.waiting = entry<uint32_t (*)(uintptr_t, uint32_t *)>(0x701289c)(service, &reason);
    state.tick_gate = *reinterpret_cast<unsigned char *>(g_base + 0xa541870);
    state.pending_stop = *reinterpret_cast<unsigned char *>(controller + 0x45);
    state.stopped = *reinterpret_cast<unsigned char *>(controller + 0x49);
    return state;
}
uint64_t monotonic_ms() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return static_cast<uint64_t>(now.tv_sec) * 1000 + now.tv_nsec / 1000000;
}
// Task roots own their children. Find the task again under TaskLock before
// reading it, rather than dereferencing a pointer that may have been detached.
uintptr_t attached_task(uintptr_t manager, uintptr_t target, uintptr_t vtable) {
    if (!manager || !target) return 0;
    unsigned visited = 0;
    auto walk = [&](auto &&self, uintptr_t node, unsigned depth) -> uintptr_t {
        if (!node || depth > 16 || ++visited > 800) return 0;
        if (node == target && *reinterpret_cast<uintptr_t *>(node) == vtable) return node;
        uintptr_t first = *reinterpret_cast<uintptr_t *>(node + 0x40), child = first;
        if (!first) return 0;
        unsigned count = 0;
        do {
            uintptr_t next = *reinterpret_cast<uintptr_t *>(child + 0x50);
            if (uintptr_t found = self(self, child, depth + 1)) return found;
            child = next;
        } while (child && child != first && ++count < 150 && visited <= 800);
        return 0;
    };
    for (unsigned i = 0; i < 4; ++i)
        if (uintptr_t found = walk(walk, *reinterpret_cast<uintptr_t *>(manager + 0x118 + i * 8), 0)) return found;
    return 0;
}

bool queue_native_match_reporting(uintptr_t commissioner, uintptr_t game_info, uintptr_t task_manager) {
    if (!commissioner || !game_info ||
        *reinterpret_cast<uintptr_t *>(commissioner) != g_base + 0x990ba20) return false;

    auto &state = *reinterpret_cast<uint32_t *>(commissioner + 0xac);
    if (state != 0 || *reinterpret_cast<uintptr_t *>(commissioner + 0x80) ||
        *reinterpret_cast<uintptr_t *>(commissioner + 0x88) ||
        *reinterpret_cast<uintptr_t *>(commissioner + 0x90) ||
        *reinterpret_cast<uintptr_t *>(commissioner + 0x98) ||
        *reinterpret_cast<uintptr_t *>(commissioner + 0xa0)) return false;
    if (*reinterpret_cast<unsigned char *>(commissioner + 0xc1) ||
        *reinterpret_cast<unsigned char *>(commissioner + 0xc2)) return false;

    const auto attach = entry<void (*)(uintptr_t, uintptr_t)>(0x822b6d0);
    if (!entry<uintptr_t (*)()>(0x7e47780)()) {
        // The normal online initializer attaches the platform service to
        // task root 0 at 0x7e0662c. Do the same if it is not already present.
        const uintptr_t root = entry<uintptr_t (*)(uintptr_t, uint32_t)>(0x822972c)(task_manager, 0);
        if (!root) return false;
        const uintptr_t service = entry<uintptr_t (*)()>(0x7e4762c)();
        if (!service) return false;
        attach(root, service);
    }

    // The host already supplies the server game ID/context, room rules and
    // synchronized user data. Have the original coordinator tick run its
    // reporting setup case (0x7b2ae84). It creates its own children and
    // publishes state 8. Wait for that transition before starting the menu.
    state = 7;
    g_match_reporting_started_ms = monotonic_ms();
    peerlink_journal("ORIGINAL_MATCH_REPORTING queued=1 commissionerState=7");
    return true;
}

bool input_event(JNIEnv *env, jobject event, bool motion) {
    if (!event || env->ExceptionCheck()) return false;
    using Convert = const AInputEvent *(*)(JNIEnv *, jobject);
    static void *android = dlopen("libandroid.so", RTLD_NOW);
    static const auto from_motion = reinterpret_cast<Convert>(android ? dlsym(android, "AMotionEvent_fromJava") : nullptr);
    static const auto from_key = reinterpret_cast<Convert>(android ? dlsym(android, "AKeyEvent_fromJava") : nullptr);
    static const auto release = reinterpret_cast<void (*)(const AInputEvent *)>(
        android ? dlsym(android, "AInputEvent_release") : nullptr);
    const auto convert = motion ? from_motion : from_key;
    const AInputEvent *native = convert ? convert(env, event) : nullptr;
    bool ok = native && release && !env->ExceptionCheck();
    if (ok) { entry<void (*)(const AInputEvent *)>(0x677264c)(native); release(native); }
    env->DeleteLocalRef(event);
    return ok;
}
bool gamepad_key(JNIEnv *env, jint code, jint action) {
    jclass factory = env->FindClass("com/peerlink/app/bot/SoftwareGamepadEvents");
    jmethodID method = factory ? env->GetStaticMethodID(factory, "key", "(II)Landroid/view/KeyEvent;") : nullptr;
    jobject event = method ? env->CallStaticObjectMethod(factory, method, code, action) : nullptr;
    bool result = input_event(env, event, false);
    if (factory) env->DeleteLocalRef(factory);
    return result;
}
bool gamepad_motion(JNIEnv *env, jfloat x, jfloat y, jfloat dash) {
    jclass factory = env->FindClass("com/peerlink/app/bot/SoftwareGamepadEvents");
    jmethodID method = factory ? env->GetStaticMethodID(factory, "motion", "(FFF)Landroid/view/MotionEvent;") : nullptr;
    jobject event = method ? env->CallStaticObjectMethod(factory, method, x, y, dash) : nullptr;
    bool result = input_event(env, event, true);
    if (factory) env->DeleteLocalRef(factory);
    return result;
}
bool neutral_event(JNIEnv *env) { return gamepad_motion(env, 0, 0, 0); }
// The online model creates the original match-data connection and its channel
// handlers. These owned room/roster inputs must precede the channel-ready wait;
// the later game ID/start-request inputs are not available at this stage.
int prepare_channel_inputs(uint32_t peer, const char *squad_path, uint32_t home) {
    if (home != g_uid && home != peer) return -106;
    if (g_channel_peer) return g_channel_peer == peer && g_channel_home == home ? 1 : -104;
    uint32_t away = home == g_uid ? peer : g_uid;
    uintptr_t root = entry<uintptr_t (*)()>(0x7a87db8)();
    if (!root || !*reinterpret_cast<uintptr_t *>(root + 0x20)) return -100;
    int result = peerlink_squad_import(g_base, 0, g_uid, squad_path);
    if (result > 0 && !peerlink_lab_uid_participants(g_base, home, away, g_uid)) result = -101;
    if (result > 0) result = peerlink_player_cache_import(g_base, g_uid, squad_path);
    if (result > 0 && !peerlink_original_online_inputs_v224(g_base, home, away)) result = -102;
    if (result > 0) { g_channel_peer = peer; g_channel_home = home; g_squad_path = squad_path; }
    return result;
}
}

extern "C" {
JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeConfigureFiles(
        JNIEnv *env, jobject, jlong base, jstring assets) {
    if (!valid(base) || !assets) return JNI_FALSE;
    const char *directory = env->GetStringUTFChars(assets, nullptr);
    if (!directory) return JNI_FALSE;
    int result = chdir(directory);
    env->ReleaseStringUTFChars(assets, directory);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeReceivedSquad(
        JNIEnv *env, jobject, jlong base, jbyteArray own) {
    peerlink::ReceivedSquad waiting;
    waiting.envelope.account = g_channel_peer;
    std::vector<uint8_t> result;
    const jsize size = own ? env->GetArrayLength(own) : 0;
    if (valid(base) && g_channel_peer && g_user_sync_complete && size >= 864 && size <= 864 + 40 * 608) {
        std::vector<uint8_t> input(static_cast<size_t>(size));
        env->GetByteArrayRegion(own, 0, size, reinterpret_cast<jbyte *>(input.data()));
        if (env->ExceptionCheck()) return nullptr;
        TaskLock lock;
        if (lock.manager)
            result = peerlink::observeReceivedSquad(g_base, g_uid, g_channel_peer, input.data(), input.size());
    }
    if (result.empty()) result = waiting.bytes();
    jbyteArray output = env->NewByteArray(static_cast<jsize>(result.size()));
    if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(result.size()),
                                      reinterpret_cast<const jbyte *>(result.data()));
    return output;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeOpenJournal(
        JNIEnv *env, jobject, jstring path) {
    if (!path) return JNI_FALSE;
    const char *value = env->GetStringUTFChars(path, nullptr);
    if (!value) return JNI_FALSE;
    bool opened = peerlink_open_diagnostic_journal(value);
    env->ReleaseStringUTFChars(path, value);
    return opened ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeOpenKeeperJournal(
        JNIEnv *env, jobject, jstring path) {
    if (!path) return JNI_FALSE;
    const char *value = env->GetStringUTFChars(path, nullptr);
    if (!value) return JNI_FALSE;
    const bool opened = peerlink_open_keeper_journal(value);
    env->ReleaseStringUTFChars(path, value);
    return opened ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeJournal(
        JNIEnv *env, jobject, jstring line) {
    if (!line) return;
    const char *value = env->GetStringUTFChars(line, nullptr);
    if (!value) return;
    peerlink_journal("%s", value);
    env->ReleaseStringUTFChars(line, value);
}

JNIEXPORT void JNICALL Java_com_peerlink_app_bot_FrameworkActivityCalls_create(
        JNIEnv *env, jclass, jobject activity, jobject state) {
    jclass framework = env->FindClass("android/app/NativeActivity");
    jmethodID method = framework ? env->GetMethodID(framework, "onCreate", "(Landroid/os/Bundle;)V") : nullptr;
    if (method) env->CallNonvirtualVoidMethod(activity, framework, method, state);
    if (framework) env->DeleteLocalRef(framework);
}

JNIEXPORT void JNICALL Java_com_peerlink_app_bot_FrameworkActivityCalls_lifecycle(
        JNIEnv *env, jclass, jobject activity, jint event) {
    const char *names[] = {"onResume", "onPause", "onStop", "onDestroy", "onWindowFocusChanged", "onWindowFocusChanged"};
    if (event < 0 || event > 5) return;
    jclass framework = env->FindClass("android/app/NativeActivity");
    jmethodID method = framework ? env->GetMethodID(framework, names[event], event < 4 ? "()V" : "(Z)V") : nullptr;
    if (method) {
        if (event < 4) env->CallNonvirtualVoidMethod(activity, framework, method);
        else env->CallNonvirtualVoidMethod(activity, framework, method, event == 4 ? JNI_TRUE : JNI_FALSE);
    }
    if (framework) env->DeleteLocalRef(framework);
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeApplicationReady(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base)) return JNI_FALSE;
    ANativeActivity *activity = framework_activity();
    if (!activity || !activity->instance) return JNI_FALSE;
    uintptr_t owner = *reinterpret_cast<uintptr_t *>(g_base + 0xa5fb348);
    bool ready = *reinterpret_cast<uintptr_t *>(g_base + 0xa364cc0) &&
        *reinterpret_cast<unsigned char *>(g_base + 0xa53b850) >= 1 &&
        owner && *reinterpret_cast<uintptr_t *>(owner + 0x40) && session_manager();
    if (ready) g_core_ready = true;
    return ready ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeLoadDatabase(
        JNIEnv *env, jobject, jlong base, jstring path) {
    if (!valid(base) || !g_core_ready || !path) return JNI_FALSE;
    if (g_saved_database_attempted && !g_saved_database_ready) return JNI_FALSE;
    const char *directory = env->GetStringUTFChars(path, nullptr);
    if (!directory) return JNI_FALSE;
    long result = -6;
    for (unsigned attempt = 0; attempt < 3000; ++attempt) {
        {
            TaskLock lock;
            uintptr_t owner = *reinterpret_cast<uintptr_t *>(g_base + 0xa5fb348);
            // The application/session objects exist before the asynchronous
            // database callbacks finish. Importing here during stage 1 lets
            // the club-update callback see clubs before their stadium table,
            // causing a null lookup in the original engine. The original
            // loader at 0x8246b3c sets stage 4 only after those callbacks finish.
            // Observe its completion; never assign a native loading flag.
            if (owner && *reinterpret_cast<volatile uint32_t *>(owner + 0x30) == 4) {
                uintptr_t work = *reinterpret_cast<uintptr_t *>(owner + 0x40);
                result = work && *reinterpret_cast<uint32_t *>(work + 0x319db64) ? 44 :
                    peerlink_game_database_input(g_base, directory);
                uintptr_t services = entry<uintptr_t (*)()>(0x677cfa4)();
                uintptr_t data = services ? *reinterpret_cast<uintptr_t *>(services + 9 * 8) : 0;
                if (result == 44 && data && *reinterpret_cast<uintptr_t *>(data) == g_base + 0x988c270 &&
                    !*reinterpret_cast<uintptr_t *>(data + 0x80)) entry<void (*)(uintptr_t)>(0x68c3768)(data);
                break;
            }
        }
        // Let the original task manager and its workers make progress while
        // waiting. Holding TaskLock across this wait would block completion.
        usleep(10000);
    }
    env->ReleaseStringUTFChars(path, directory);
    __android_log_print(ANDROID_LOG_INFO, TAG, "database startup result=%ld", result);
    return result == 44 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeApplySavedDatabase(
        JNIEnv *, jobject, jlong base, jint expected_players) {
    if (!valid(base) || !g_core_ready || g_client || g_match_stage || expected_players <= 0) return -1;
    if (g_saved_database_ready) {
        TaskLock lock;
        return lock.manager && matches_saved_database(entry<uintptr_t (*)()>(0x82468d4)(), expected_players) ? 1 : -5;
    }
    if (g_saved_database_attempted) return -7;
    g_saved_database_attempted = true;

    // 11.1.1's live-data task registers the downloaded CPK (0x7a1a128),
    // loads its data (0x7a1b828/0x7a1b860), then asks the original database
    // owner to reload (0x7a1b890/0x7a1ba18). Use the same order before the
    // client starts. The engine owns all mount and database completion flags.
    constexpr uint32_t kLiveData = 1;
    constexpr unsigned kComplete = 2;
    constexpr uint32_t kDatabaseReady = 4;
    enum class Phase { Register, WaitForArchive, WaitForData, WaitForDatabase };
    Phase phase = Phase::Register;
    uint32_t binding = 0;
    const uint64_t deadline = monotonic_ms() + 45'000;
    while (monotonic_ms() < deadline) {
        {
            TaskLock lock;
            if (!lock.manager) return -1;
            const auto owner = entry<uintptr_t (*)()>(0x82468d4)();
            if (!owner) return -1;
            const auto stage = *reinterpret_cast<const uint32_t *>(owner + 0x30);
            switch (phase) {
                case Phase::Register: {
                    const auto binder = *reinterpret_cast<const uintptr_t *>(g_base + 0xa538680);
                    if (stage != kDatabaseReady || !binder) return -1;
                    binding = entry<uint32_t (*)(uintptr_t, const char *, const char *)>(0x8213804)(
                        binder, "dt870", "dt870_mobile_and.cpk");
                    peerlink_journal("SAVED_DATABASE_ARCHIVE binding=%u", binding);
                    if (!binding) return -2;
                    phase = Phase::WaitForArchive;
                    break;
                }
                case Phase::WaitForArchive: {
                    unsigned status = 0;
                    const int result = entry<int (*)(uint32_t, unsigned *)>(0x8c1ca10)(binding, &status);
                    if (result != 0 || status > kComplete) {
                        peerlink_journal("SAVED_DATABASE_ARCHIVE rejected=%d status=%u", result, status);
                        return -3;
                    }
                    if (status == kComplete) {
                        entry<void (*)(uint32_t)>(0x81f0d6c)(kLiveData);
                        entry<void (*)(uint32_t)>(0x81f0cb8)(kLiveData);
                        phase = Phase::WaitForData;
                    }
                    break;
                }
                case Phase::WaitForData:
                    if ((entry<uint32_t (*)(uint32_t)>(0x81f0e20)(kLiveData) & 1) != 0) {
                        if (stage != kDatabaseReady ||
                            (entry<uint32_t (*)(uintptr_t, uint32_t)>(0x82469e4)(owner, 0) & 1) == 0)
                            return -4;
                        peerlink_journal("SAVED_DATABASE_RELOAD accepted=1");
                        phase = Phase::WaitForDatabase;
                    }
                    break;
                case Phase::WaitForDatabase:
                    if (stage != kDatabaseReady) break;
                    if (!matches_saved_database(owner, expected_players)) return -5;
                    const auto consumer = entry<uintptr_t (*)()>(0x7b59768)();
                    if (consumer) entry<void (*)(uintptr_t)>(0x7b59808)(consumer);
                    g_saved_database_ready = true;
                    return 1;
            }
        }
        // The game and CRI workers may need the task lock to finish.
        usleep(10'000);
    }
    peerlink_journal("SAVED_DATABASE_TIMEOUT phase=%u", static_cast<unsigned>(phase));
    return -6;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeVerifySavedDatabase(
        JNIEnv *, jobject, jlong base, jint expected_players) {
    if (!valid(base) || !g_saved_database_ready || !g_client || g_match_stage || expected_players <= 0) return JNI_FALSE;
    TaskLock lock;
    return lock.manager && matches_saved_database(entry<uintptr_t (*)()>(0x82468d4)(), expected_players) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeMergeEditRecords(
        JNIEnv *env, jobject, jlong base, jstring path) {
    if (!valid(base) || !g_core_ready || g_client || g_match_stage || !path) return JNI_FALSE;
    const char *filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) return JNI_FALSE;
    FILE *file = fopen(filename, "rb");
    env->ReleaseStringUTFChars(path, filename);
    if (!file) return JNI_FALSE;
    constexpr size_t kSupplementSize = 23026 * peerlink::kEditRecordSize;
    std::vector<uint8_t> supplement(kSupplementSize);
    const bool read_ok = fread(supplement.data(), 1, supplement.size(), file) == supplement.size() &&
                         fgetc(file) == EOF && !ferror(file);
    fclose(file);
    if (!read_ok) return JNI_FALSE;
    TaskLock lock;
    if (!lock.manager) return JNI_FALSE;
    uintptr_t owner = *reinterpret_cast<uintptr_t *>(g_base + 0xa5fb348);
    if (!owner || *reinterpret_cast<uint32_t *>(owner + 0x30) != 4) return JNI_FALSE;
    uintptr_t work = *reinterpret_cast<uintptr_t *>(owner + 0x50);
    if (!work) return JNI_FALSE;
    auto *count = reinterpret_cast<uint32_t *>(work + 0x66b0c4);
    auto *records = reinterpret_cast<uint8_t *>(work + 0x80e80);
    if (!*count || *count > peerlink::kEditRecordCapacity) return JNI_FALSE;
    const size_t original_count = *count;
    std::vector<uint8_t> merged;
    const auto result = peerlink::mergeEditRecords(records, original_count * peerlink::kEditRecordSize,
                                                    supplement.data(), supplement.size(), merged);
    if (!result.ok) return JNI_FALSE;
    std::memcpy(records, merged.data(), merged.size());
    *count = static_cast<uint32_t>(merged.size() / peerlink::kEditRecordSize);
    peerlink_journal("EDIT_WORK_SUPPLEMENT before=%zu added=%zu after=%u", original_count, result.added, *count);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeStartupInfo(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base)) return env->NewStringUTF("startup: engine not loaded");
    ANativeActivity *activity = framework_activity();
    uintptr_t app = activity ? reinterpret_cast<uintptr_t>(activity->instance) : 0;
    uintptr_t owner = *reinterpret_cast<uintptr_t *>(g_base + 0xa5fb348);
    char text[256];
    snprintf(text, sizeof(text), "startup: engine=%u objects=%u kernel=%u running=%u activity=%u window=%u database=%u work=%u sessions=%u",
        *reinterpret_cast<uintptr_t *>(g_base + 0xa364cc0) != 0,
        *reinterpret_cast<unsigned char *>(g_base + 0x9fe6040),
        *reinterpret_cast<unsigned char *>(g_base + 0xa53b850),
        app ? *reinterpret_cast<uint32_t *>(app + 0x100) : 0,
        app ? *reinterpret_cast<uint32_t *>(app + 0x60) : 0,
        app && *reinterpret_cast<uintptr_t *>(app + 0x48), owner != 0,
        owner && *reinterpret_cast<uintptr_t *>(owner + 0x40), session_manager() != 0);
    return env->NewStringUTF(text);
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeConfigureServerSettings(
        JNIEnv *env, jobject, jlong base, jboolean revision_check, jstring protocol_options, jstring online_options,
        jintArray slowdown_thresholds) {
    if (!valid(base) || !g_client || !protocol_options || !online_options || !slowdown_thresholds) return JNI_FALSE;
    const jsize threshold_count = env->GetArrayLength(slowdown_thresholds);
    jint thresholds[3]{};
    if (threshold_count > 3) return JNI_FALSE;
    if (threshold_count) env->GetIntArrayRegion(slowdown_thresholds, 0, threshold_count, thresholds);
    if (env->ExceptionCheck()) return JNI_FALSE;

    TaskLock lock;
    if (!lock.manager || g_introduction_peer || *reinterpret_cast<uintptr_t *>(g_base + 0xa5bb188))
        return JNI_FALSE;
    const uintptr_t services = entry<uintptr_t (*)()>(0x7e0afbc)();
    if (!services) return JNI_FALSE;
    const uintptr_t config = *reinterpret_cast<uintptr_t *>(services + 0x10);
    const uintptr_t report_service = *reinterpret_cast<uintptr_t *>(services + 0x18);
    const uintptr_t game = entry<uintptr_t (*)(uint32_t)>(0x7a87db8)(1);
    const uintptr_t match_config = game ? *reinterpret_cast<uintptr_t *>(game + 0x10) : 0;
    if (!config || !report_service || !match_config) return JNI_FALSE;

    const char *protocol = env->GetStringUTFChars(protocol_options, nullptr);
    if (!protocol) return JNI_FALSE;
    const char *online = env->GetStringUTFChars(online_options, nullptr);
    if (!online) {
        env->ReleaseStringUTFChars(protocol_options, protocol);
        return JNI_FALSE;
    }
    const size_t protocol_size = strlen(protocol);
    const size_t online_size = strlen(online);
    EngineString protocol_input{}, online_input{};
    const auto construct = entry<void (*)(EngineString *, const char *, size_t)>(0x2f3877c);
    construct(&protocol_input, protocol, protocol_size);
    construct(&online_input, online, online_size);
    env->ReleaseStringUTFChars(online_options, online);
    env->ReleaseStringUTFChars(protocol_options, protocol);

    // CMD_GET_SERVER_ENV's success callback at 0x773d85c applies these
    // response fields in order. TaskAlignProgress reads the timeouts
    // parsed by 0x7e168a8 when it constructs the game's ready barrier.
    entry<void (*)(uintptr_t, const EngineString *)>(0x7e1d0fc)(report_service, &protocol_input);
    entry<void (*)(uintptr_t, const EngineString *)>(0x7e168a8)(config, &online_input);
    destroy_string(online_input);
    destroy_string(protocol_input);
    *reinterpret_cast<unsigned char *>(config + 0x4dc) = revision_check == JNI_TRUE;

    // Same GameInfo settings container and clear/append calls as the
    // original server-environment callback at 0x773dce0.
    const uintptr_t slowdown = entry<uintptr_t (*)(uintptr_t)>(0x7abd550)(match_config);
    entry<void (*)(uintptr_t)>(0x7abf708)(slowdown);
    for (jsize i = 0; i < threshold_count; ++i)
        entry<void (*)(uintptr_t, jint)>(0x7abf788)(slowdown, thresholds[i]);

    const auto *timeouts = entry<const uint32_t *(*)(uintptr_t)>(0x7e16e6c)(config);
    peerlink_journal("SERVER_ENV_APPLIED revisionCheck=%u protocolOptionsBytes=%zu onlineOptionsBytes=%zu alignAbnormalEndSec=%u alignReadySec=%u slowdownThresholdCount=%d",
        unsigned(revision_check == JNI_TRUE), protocol_size, online_size, timeouts[3], timeouts[4], threshold_count);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeDatabaseDiagnostics(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base)) return env->NewStringUTF("DATABASE_SIGNATURES unavailable=engine_not_loaded");
    TaskLock lock;
    uintptr_t model = *reinterpret_cast<uintptr_t *>(g_base + 0xa5bb188);
    if (!lock.manager || !model) return env->NewStringUTF("DATABASE_SIGNATURES unavailable=no_model");
    auto checksum = [](uintptr_t object) -> std::string {
        auto *bytes = reinterpret_cast<const unsigned char *>(object);
        auto *words = reinterpret_cast<const uintptr_t *>(object);
        size_t size = (bytes[0] & 1) ? words[1] : bytes[0] >> 1;
        if (!size) return "empty";
        if (size != 32) return "unavailable";
        const char *data = (bytes[0] & 1) ? reinterpret_cast<const char *>(words[2]) : reinterpret_cast<const char *>(bytes + 1);
        if (!data) return "unavailable";
        for (size_t i = 0; i < size; ++i)
            if (!((data[i] >= '0' && data[i] <= '9') || (data[i] >= 'A' && data[i] <= 'F') ||
                  (data[i] >= 'a' && data[i] <= 'f'))) return "unavailable";
        return std::string(data, size);
    };
    std::string text = "DATABASE_SIGNATURES localPlayer=" + checksum(model + 0x560 + 0x130) +
        " localCoach=" + checksum(model + 0x560 + 0x148);
    uintptr_t first = *reinterpret_cast<uintptr_t *>(model + 8), last = *reinterpret_cast<uintptr_t *>(model + 16);
    if (first && last >= first && (last - first) % 0x510 == 0 && (last - first) / 0x510 <= 24)
        for (uintptr_t row = first; row < last; row += 0x510)
            if (*reinterpret_cast<uint32_t *>(row + 0x410) != g_uid)
                text += " peerPlayer=" + checksum(row + 0x480) + " peerCoach=" + checksum(row + 0x498);
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jstring JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeNetworkStartupDiagnostics(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base)) return env->NewStringUTF("NETWORK_STARTUP unavailable=engine_not_loaded");
    char networking[1536];
    peerlink_network_startup_diagnostics(g_client, networking, sizeof(networking));
    const std::string text = std::string("NETWORK_STARTUP ") + networking;
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jstring JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeLoadingDiagnostics(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base)) return env->NewStringUTF("MATCH_LOADING unavailable=engine_not_loaded");
    TaskLock lock;
    if (!lock.manager) return env->NewStringUTF("MATCH_LOADING unavailable=no_task_manager");
    std::string text = "MATCH_LOADING hostStage=" + std::to_string(g_match_stage) +
        " previewEventSubmitted=" + std::to_string(g_preview_proceeded) +
        " userDataSyncComplete=" + std::to_string(g_user_sync_complete);
    char preview[768];
    peerlink_preview_diagnostics(preview, sizeof(preview));
    text += " "; text += preview;
    char networking[1536];
    peerlink_network_startup_diagnostics(g_client, networking, sizeof(networking));
    text += " "; text += networking;
    // The pitch/core can exist while the original match service still waits.
    // Query its own predicate (701289c -> 7012768), not an inferred loading
    // timeout. This getter and the scalar reads never open the simulation gate.
    uintptr_t simulation = *reinterpret_cast<uintptr_t *>(g_base + 0x9a6a020);
    if (simulation && *reinterpret_cast<uintptr_t *>(simulation) == g_base + 0x98ae9e0) {
        uintptr_t inner = *reinterpret_cast<uintptr_t *>(simulation + 8);
        uint32_t wait_reason = 0;
        uint32_t waiting = entry<uint32_t (*)(uintptr_t, uint32_t *)>(0x701289c)(simulation, &wait_reason);
        text += " simulationWait=" + std::to_string(waiting) +
            " simulationWaitReason=" + std::to_string(wait_reason) +
            " simulationFailureLatched=" + std::to_string(inner ? *reinterpret_cast<unsigned char *>(inner + 0x12) : -1) +
            " simulationTickGate=" + std::to_string(*reinterpret_cast<unsigned char *>(g_base + 0xa541870));
        char stop[1024];
        peerlink_match_stop_diagnostics(inner ? *reinterpret_cast<uintptr_t *>(inner + 0xa0) : 0,
                                        stop, sizeof(stop));
        text += " "; text += stop;
    } else text += " simulationWait=unavailable";
    uintptr_t user_sync = attached_task(lock.manager, g_user_sync_task, g_base + 0x990d9c8);
    if (user_sync) text += " userDataSyncState=" + std::to_string(*reinterpret_cast<uint32_t *>(user_sync + 0x70));
    uintptr_t root = entry<uintptr_t (*)()>(0x7a87db8)();
    uintptr_t info = root ? *reinterpret_cast<uintptr_t *>(root + 0x20) : 0;
    if (info) {
        uintptr_t context = entry<uintptr_t (*)(uintptr_t)>(0x7a58998)(info);
        if (context) text += " ownSide=" + std::to_string(*reinterpret_cast<uint32_t *>(context + 0x2c));
    }
    uintptr_t flow = *reinterpret_cast<uintptr_t *>(g_base + 0xa53b6e8);
    uintptr_t task = flow ? *reinterpret_cast<uintptr_t *>(flow + 0x190) : 0;
    if (flow) text += " flowState=" + std::to_string(*reinterpret_cast<uint32_t *>(flow + 0x120));
    if (task) {
        uintptr_t first = *reinterpret_cast<uintptr_t *>(task + 0x70), end = *reinterpret_cast<uintptr_t *>(task + 0x78);
        if (first && end > first && (end - first) % 0xe0 == 0 && (end - first) / 0xe0 <= 32) {
            auto *leaf = reinterpret_cast<const unsigned char *>(end - 0xe0);
            auto *words = reinterpret_cast<const uintptr_t *>(leaf);
            size_t count = (leaf[0] & 1) ? words[1] : leaf[0] >> 1;
            const char *name = (leaf[0] & 1) ? reinterpret_cast<const char *>(words[2]) : reinterpret_cast<const char *>(leaf + 1);
            if (name && count && count <= 160) {
                bool safe = true;
                for (size_t i = 0; i < count; ++i)
                    if (!((name[i] >= 'A' && name[i] <= 'Z') || (name[i] >= 'a' && name[i] <= 'z') ||
                          (name[i] >= '0' && name[i] <= '9') || name[i] == '/' || name[i] == '_')) safe = false;
                if (safe) text += " leaf=" + std::string(name, count);
            }
            // Original 6aa8fac sets this on the client's completion event.
            // It is not an indication that the process was never started.
            text += " leafCompletionSignal=" + std::to_string(leaf[0x50]);
        }
    }
    unsigned visited = 0;
    auto walk = [&](auto &&self, uintptr_t node, unsigned depth) -> void {
        if (!node || depth > 16 || ++visited > 800) return;
        const uintptr_t table = *reinterpret_cast<uintptr_t *>(node);
        if (table == g_base + 0x990ba20) {
            text += " phaseQueueAttached=" + std::to_string(*reinterpret_cast<uintptr_t *>(node + 0x80) != 0);
            text += " resultWatcherAttached=" + std::to_string(*reinterpret_cast<uintptr_t *>(node + 0x88) != 0);
        }
        if (table == g_base + 0x996fde8) {
            uintptr_t roster = *reinterpret_cast<uintptr_t *>(node + 0x90);
            uintptr_t secondary = *reinterpret_cast<uintptr_t *>(node + 0x98);
            text += " preMatchTaskState=" + std::to_string(*reinterpret_cast<uint32_t *>(node + 0xa0));
            if (roster) text += " squadTransferState=" + std::to_string(*reinterpret_cast<uint32_t *>(roster + 0x70));
            if (secondary) text += " secondarySyncState=" + std::to_string(*reinterpret_cast<uint32_t *>(secondary + 0x70));
        }
        uintptr_t first = *reinterpret_cast<uintptr_t *>(node + 0x40), child = first;
        if (!first) return;
        unsigned count = 0;
        do {
            uintptr_t next = *reinterpret_cast<uintptr_t *>(child + 0x50);
            self(self, child, depth + 1);
            child = next;
        } while (child && child != first && ++count < 150 && visited <= 800);
    };
    for (unsigned i = 0; i < 4; ++i) walk(walk, *reinterpret_cast<uintptr_t *>(lock.manager + 0x118 + i * 8), 0);
    text += "\nMATCH_SETUP_SYNC started=" + std::to_string(g_settings_sync_task != 0) +
        " complete=" + std::to_string(g_settings_sync_complete) +
        " state=" + std::to_string(g_settings_sync_state.load(std::memory_order_acquire));
    uintptr_t settings_task = *reinterpret_cast<uintptr_t *>(g_base + 0xa5c8310);
    text += " taskPresent=" + std::to_string(settings_task != 0);
    uintptr_t owner = *reinterpret_cast<uintptr_t *>(g_base + 0xa5fb348);
    uintptr_t work = owner ? *reinterpret_cast<uintptr_t *>(owner + 0x48) : 0;
    if (work) {
        uintptr_t source = work + 0x6c4c0;
        text += " activeTime=" + std::to_string(*reinterpret_cast<unsigned char *>(source + 0xb8)) +
            " activeStadium=" + std::to_string(*reinterpret_cast<uint16_t *>(source + 0x158));
    }
    text += "\n"; text += peerlink_room_rules_diagnostics();
    text += "\n"; text += peerlink_match_state_diagnostics();
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativePrepareChannels(
        JNIEnv *env, jobject, jlong base, jlong peer, jlong home, jstring squad) {
    if (!valid(base) || !g_client || !squad || peer <= 0 || peer > 0xffffffffLL || peer == g_uid)
        return JNI_FALSE;
    if (home != g_uid && home != peer) return JNI_FALSE;
    const char *path = env->GetStringUTFChars(squad, nullptr);
    if (!path) return JNI_FALSE;
    int result = -100;
    {
        TaskLock lock;
        uintptr_t manager = session_manager();
        if (lock.manager && manager) {
            entry<void (*)(uintptr_t)>(0x2f39738)(manager);
            uintptr_t session = *reinterpret_cast<uintptr_t *>(manager + 0x38);
            // GameDataConnection asks this session for its negotiated
            // configuration. Creating it while connecting yields an error
            // and the original model tears itself down on its next frame.
            result = session && entry<uint32_t (*)(uintptr_t)>(0x7cedee8)(session) == 4 ?
                prepare_channel_inputs(static_cast<uint32_t>(peer), path, static_cast<uint32_t>(home)) : -105;
            entry<void (*)(uintptr_t)>(0x8c1f0d0)(manager + 8);
        }
    }
    env->ReleaseStringUTFChars(squad, path);
    __android_log_print(ANDROID_LOG_INFO, TAG, "early channel input result=%d", result);
    return result > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeSetupMatch(
        JNIEnv *env, jobject, jlong base, jlong peer, jstring squad, jstring context, jstring room_rules) {
    if (!valid(base) || !g_client || peer <= 0 || peer > 0xffffffffLL || peer == g_uid ||
        !squad || !context || !room_rules || g_match_stage) return JNI_FALSE;
    const char *squad_path = env->GetStringUTFChars(squad, nullptr);
    if (!squad_path) return JNI_FALSE;
    const char *context_path = env->GetStringUTFChars(context, nullptr);
    if (!context_path) { env->ReleaseStringUTFChars(squad, squad_path); return JNI_FALSE; }
    const char *rules_path = env->GetStringUTFChars(room_rules, nullptr);
    if (!rules_path) {
        env->ReleaseStringUTFChars(squad, squad_path);
        env->ReleaseStringUTFChars(context, context_path);
        return JNI_FALSE;
    }
    int result = -100;
    {
        TaskLock lock;
        uintptr_t root = entry<uintptr_t (*)()>(0x7a87db8)();
        if (lock.manager && root && *reinterpret_cast<uintptr_t *>(root + 0x20)) {
            result = prepare_channel_inputs(static_cast<uint32_t>(peer), squad_path, g_channel_home ? g_channel_home : g_uid);
            if (result > 0) {
                uintptr_t task = entry<uintptr_t (*)(const char *)>(0x7b488c8)("TaskSyncUserData");
                uintptr_t task_root = *reinterpret_cast<uintptr_t *>(lock.manager + 0x118);
                if (!task || !task_root) result = -103;
                else {
                    entry<void (*)(uintptr_t, uintptr_t)>(0x822b6d0)(task_root, task);
                    g_user_sync_task = task;
                    g_user_sync_started_ms = monotonic_ms();
                }
            }
        }
        if (result > 0) {
            g_match_stage = 1;
            g_squad_path = squad_path;
            g_match_context_path = context_path;
            g_room_rules_path = rules_path;
        }
    }
    env->ReleaseStringUTFChars(squad, squad_path);
    env->ReleaseStringUTFChars(context, context_path);
    env->ReleaseStringUTFChars(room_rules, rules_path);
    __android_log_print(ANDROID_LOG_INFO, TAG, "match input result=%d", result);
    return result > 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeAdvanceMatch(
        JNIEnv *env, jobject, jlong base, jboolean controller_allowed) {
    if (!valid(base) || !g_match_stage) return -1;
    TaskLock lock;
    if (!lock.manager) return -2;
    uintptr_t model = *reinterpret_cast<uintptr_t *>(g_base + 0xa5bb188);
    if (model && *reinterpret_cast<unsigned char *>(model + 0x7f2)) return -201;
    if (g_match_stage == 1 && model && *reinterpret_cast<uint32_t *>(model + 0x7b8) == 5 &&
        *reinterpret_cast<unsigned char *>(model + 0x7f1)) {
        if (!g_user_sync_complete) {
            uintptr_t task = attached_task(lock.manager, g_user_sync_task, g_base + 0x990d9c8);
            if (!task) return -205;
            uint32_t state = *reinterpret_cast<uint32_t *>(task + 0x70);
            // Original 7b48d08 writes 4 only after both native synchronization
            // barriers and the participant/network-owner application. The
            // working full-app host observed this before initializing the
            // coordinator; phase-5 metadata alone is an earlier milestone.
            if (state > 4) return -206;
            if (state != 4) return monotonic_ms() - g_user_sync_started_ms >= 90000 ? -207 : 1;
            g_user_sync_complete = true;
            __android_log_print(ANDROID_LOG_INFO, TAG, "ORIGINAL_USER_SYNC_COMPLETED_BEFORE_PREMATCH state=4");
        }
        entry<void (*)()>(0x6a9e2bc)();
        if (!g_controller_scanned) {
            entry<void (*)()>(0x6772fbc)();
            if (env->ExceptionCheck()) return -3;
            if (!neutral_event(env)) return -4;
            g_controller_scanned = true;
        }
        long context_result = peerlink_live_match_context_input(g_base, g_match_context_path.c_str());
        if (context_result <= 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "original server match context rejected result=%ld", context_result);
            return -208;
        }
        entry<void (*)()>(0x7b2a060)();
        const uintptr_t commissioner = entry<uintptr_t (*)()>(0x7b2a3a8)();
        // The commissioner constructor resets CommonWork's match settings.
        // The normal lobby builder supplies its key/rules, then 7a589a0
        // applies them before the pre-match flow. Merely copying GameInfo
        // parameters leaves the running simulation on its native defaults.
        if (peerlink_room_rules_input(g_base, g_room_rules_path.c_str()) <= 0) return -215;
        if (peerlink_squad_import(g_base, 0, g_uid, g_squad_path.c_str()) <= 0) return -202;
        // Normal SyncData (8137434) creates this original commissioner child.
        // It exchanges/normalizes match setup through the native channel and
        // applies the result in 7b3ee8c. Two hosts omitting it can agree with
        // each other while never exercising the normal peer's setup path.
        g_settings_sync_task = entry<uintptr_t (*)()>(0x7b3e278)();
        if (!observe_settings_sync(g_settings_sync_task)) return -216;
        g_settings_sync_started_ms = monotonic_ms();
        uintptr_t online = entry<uintptr_t (*)()>(0x7a1d8d4)();
        if (!online) return -203;
        entry<void (*)(uintptr_t)>(0x7a2469c)(online);
        const uintptr_t game = entry<uintptr_t (*)(uint32_t)>(0x7a87db8)(1);
        const uintptr_t game_info = game ? *reinterpret_cast<uintptr_t *>(game + 0x20) : 0;
        if (!queue_native_match_reporting(commissioner, game_info, lock.manager)) return -219;
        g_match_stage = 2;
    }
    if (g_match_stage == 2 && !g_pre_menu_started) {
        const uintptr_t commissioner = entry<uintptr_t (*)()>(0x7b2a3a8)();
        if (!commissioner || *reinterpret_cast<uintptr_t *>(commissioner) != g_base + 0x990ba20) return -219;
        const uint32_t state = *reinterpret_cast<uint32_t *>(commissioner + 0xac);
        if (state == 7 && monotonic_ms() - g_match_reporting_started_ms < 30000) return 2;
        const bool result = *reinterpret_cast<uintptr_t *>(commissioner + 0x88) != 0;
        if (state != 8 || !*reinterpret_cast<uintptr_t *>(commissioner + 0x80) ||
            !*reinterpret_cast<uintptr_t *>(commissioner + 0x90) ||
            (*reinterpret_cast<unsigned char *>(commissioner + 0xa9) && !result)) return -219;
        peerlink_journal("ORIGINAL_MATCH_REPORTING commissionerState=8 phase_queue=1 result_watcher=%u platform_session=1 match_checks=%u match_advice=%u network_delay_task=%u",
            unsigned(result), unsigned(*reinterpret_cast<uintptr_t *>(commissioner + 0x98) != 0),
            unsigned(*reinterpret_cast<uintptr_t *>(commissioner + 0xa0) != 0),
            unsigned(entry<uintptr_t (*)()>(0x7e97e8c)() != 0));
        uintptr_t flow = *reinterpret_cast<uintptr_t *>(g_base + 0xa53b6e8);
        if (!flow) return -204;
        entry<void (*)(uintptr_t, const char *)>(0x6aa70a8)(flow + 0x118, "Online/Match/MatchProcessPreMenu");
        g_pre_menu_started = true;
    }
    if (g_match_stage == 2 && !g_settings_sync_complete) {
        const uint32_t state = g_settings_sync_state.load(std::memory_order_acquire);
        if (state > 4) return -217;
        if (state == 4) {
            g_settings_sync_complete = true;
            __android_log_print(ANDROID_LOG_INFO, TAG, "ORIGINAL_MATCH_SETUP_SYNC_COMPLETED state=4");
        } else {
            // The live global is cleared by the original task's destructor.
            // A terminal result is retained above; otherwise require a live
            // owned task without dereferencing a released saved pointer.
            uintptr_t task = *reinterpret_cast<uintptr_t *>(g_base + 0xa5c8310);
            if (task != g_settings_sync_task) return -216;
            const uintptr_t table = *reinterpret_cast<uintptr_t *>(task);
            if (table != g_base + 0x990c778 &&
                table != reinterpret_cast<uintptr_t>(g_settings_sync_vtable.data() + 2)) return -216;
            if (monotonic_ms() - g_settings_sync_started_ms >= 90000) return -218;
        }
    }
    const bool setup_synchronized = g_user_sync_complete && g_settings_sync_complete;
    if (g_match_stage == 2 && !g_preview_proceeded) {
        // The normal peer can enter its setup exchange after its preview
        // action. Keep that UI action available while the exchange waits.
        long preview = peerlink_preview_drive(g_base, g_user_sync_complete);
        if (preview == -2) { peerlink_preview_stop(); return -209; }
        if (preview < 0) { peerlink_preview_stop(); return -210; }
        if (preview == 1) g_preview_proceeded = true;
    }
    if (g_match_stage == 2) {
        long reference_intro = peerlink_reference_intro_drive(g_base, setup_synchronized);
        if (reference_intro < 0) { peerlink_preview_stop(); return -213; }
        uintptr_t rhi = *reinterpret_cast<uintptr_t *>(g_base + 0xa0111f8);
        if (rhi && *reinterpret_cast<uintptr_t *>(rhi) == g_base + 0x93b6e98) {
            long demo = peerlink_demo_drive(g_base);
            if (demo < 0) { peerlink_preview_stop(); return -211; }
            if (setup_synchronized && peerlink_null_client_rhi_ready(g_base)) {
                long visual = peerlink_visual_start_drive(g_base);
                if (visual < 0) { peerlink_preview_stop(); return -212; }
            }
        }
    }
    // Explicit controller configuration for the test, permitted only by the
    // current room's server response. These are original input setters, not
    // proof that the server field's full native model mapping has been ported.
    if (controller_allowed) {
        uintptr_t match = *reinterpret_cast<uintptr_t *>(g_base + 0xa533ec0);
        uintptr_t owner = *reinterpret_cast<uintptr_t *>(g_base + 0xa5fb348);
        uintptr_t work = owner ? *reinterpret_cast<uintptr_t *>(owner + 0x48) : 0;
        uintptr_t settings_root = *reinterpret_cast<uintptr_t *>(g_base + 0xa58de28);
        uintptr_t settings = settings_root ? *reinterpret_cast<uintptr_t *>(settings_root + 0x570) : 0;
        unsigned phase = match ? *reinterpret_cast<unsigned char *>(match + 0x138) : 0;
        if (work && phase >= 7 && phase <= 17 && phase != 14 && phase != 15) {
            uintptr_t source = work + 0x6c4c0;
            if (*reinterpret_cast<unsigned char *>(source + 0x102) != 1)
                entry<void (*)(uintptr_t, uint32_t)>(0x8293f7c)(source, 1);
            if (settings && *reinterpret_cast<unsigned char *>(settings + 0xd3) != 1)
                entry<void (*)(uintptr_t, uint32_t)>(0x72ea4dc)(settings, 1);
        }
    }
    return peerlink_match_check_failed() ? -214 : g_match_stage;
}

JNIEXPORT jlongArray JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeSnapshot(
        JNIEnv *env, jobject, jlong base, jboolean passive) {
    // Append-only IPC layout. Diagnostic fields are reads under the same
    // original task lock; they never change the engine's connection verdict.
    constexpr unsigned kStateSize = 35;
    jlong state[kStateSize]{};
    char stop[1024];
    bool journal_sample = false;
    bool setup_complete = false;
    if (valid(base)) {
        TaskLock lock;
        state[0] = *reinterpret_cast<unsigned char *>(g_base + 0xa53b850);
        // Read the application's original published frame counter. Never
        // advance its heartbeat or manually tick the engine from this host.
        state[20] = entry<uint32_t (*)()>(0x2f4b5bc)();
        // 7e013ac is an active SDK operation, not a status getter. Calling it
        // from a snapshot races the original application's network owner
        // (observed crash: 6922030 through 7e014e0 on engine-runtime).
        // Keep the append-only slot as unavailable; all connection predicates
        // below use the original session/channel/model states instead.
        state[1] = -1;
        uintptr_t manager = session_manager();
        if (manager) {
            entry<void (*)(uintptr_t)>(0x2f39738)(manager);
            uintptr_t session = *reinterpret_cast<uintptr_t *>(manager + 0x38);
            if (session) {
                state[2] = entry<uint32_t (*)(uintptr_t)>(0x7cedee8)(session);
                state[3] = entry<uint32_t (*)(uintptr_t, uint32_t)>(0x7cee08c)(session, 14);
                state[4] = entry<uint32_t (*)(uintptr_t, uint32_t)>(0x7cee08c)(session, 15);
                uintptr_t strategy = *reinterpret_cast<uintptr_t *>(session + 0x110);
                uintptr_t type = strategy ? *reinterpret_cast<uintptr_t *>(strategy) : 0;
                if (type == g_base + 0x992e570) state[21] = 1; // Original NTL fallback.
                else if (type == g_base + 0x992e858) state[21] = 2; // Original configured TURN transport.
            }
            entry<void (*)(uintptr_t)>(0x8c1f0d0)(manager + 8);
        }
        uintptr_t turn = turn_service();
        if (turn) state[22] = entry<uint32_t (*)(uintptr_t)>(0x7e236e0)(turn);
        uintptr_t model = *reinterpret_cast<uintptr_t *>(g_base + 0xa5bb188);
        state[23] = *reinterpret_cast<uint32_t *>(g_base + 0xa5bb198);
        if (model) {
            state[5] = *reinterpret_cast<uint32_t *>(model + 0x7b8);
            state[6] = *reinterpret_cast<unsigned char *>(model + 0x7f1);
            state[7] = *reinterpret_cast<unsigned char *>(model + 0x7f2);
            uintptr_t handle = *reinterpret_cast<uintptr_t *>(model + 0x848);
            state[19] = handle && *reinterpret_cast<uintptr_t *>(handle);
            state[24] = *reinterpret_cast<uint32_t *>(model + 0xa34);
            state[25] = *reinterpret_cast<unsigned char *>(model + 0xa30);
            state[26] = 1;
            state[27] = *reinterpret_cast<unsigned char *>(model + 0x7f0);
            uintptr_t first = *reinterpret_cast<uintptr_t *>(model + 8);
            uintptr_t last = *reinterpret_cast<uintptr_t *>(model + 16);
            if (last >= first && last - first <= 24 * 0x510 && (last - first) % 0x510 == 0)
                state[28] = (last - first) / 0x510;
        }
        uintptr_t match = *reinterpret_cast<uintptr_t *>(g_base + 0xa533ec0);
        uintptr_t core = match ? *reinterpret_cast<uintptr_t *>(match + 0x2f8) : 0;
        if (match) state[8] = *reinterpret_cast<unsigned char *>(match + 0x138);
        if (lock.manager && state[8] == 21) state[32] = peerlink_halftime_menu_state(g_base);
        if (core && *reinterpret_cast<uintptr_t *>(core) == g_base + 0x9899d50) {
            state[9] = *reinterpret_cast<uint32_t *>(core + 8);
            for (unsigned team = 0; team < 2; ++team) for (unsigned slot = 0; slot < 11; ++slot) {
                uintptr_t player = *reinterpret_cast<uintptr_t *>(core + 0x10 + team * 0x58 + slot * 8);
                if (player && *reinterpret_cast<uintptr_t *>(player) == g_base + 0x98b2338 &&
                    *reinterpret_cast<uint32_t *>(player + 0x18) == team * 11 + slot) ++state[10];
            }
            uintptr_t balls = *reinterpret_cast<uintptr_t *>(core + 0x3c98);
            if (balls && *reinterpret_cast<uintptr_t *>(balls) == g_base + 0x989a160) {
                uintptr_t ball = *reinterpret_cast<uintptr_t *>(balls + 0x30);
                unsigned index = *reinterpret_cast<uint32_t *>(balls + 0xc);
                if (ball && index < 8 && *reinterpret_cast<uintptr_t *>(balls + 0x38 + index * 8) == ball) {
                    const float *p = reinterpret_cast<const float *>(ball + 0x30);
                    const float *v = reinterpret_cast<const float *>(ball + 0x3c);
                    bool finite = std::isfinite(p[0]) && std::isfinite(p[1]) && std::isfinite(p[2]) &&
                        std::isfinite(v[0]) && std::isfinite(v[1]) && std::isfinite(v[2]);
                    state[11] = finite && std::abs(p[0]) <= 60 && std::abs(p[2]) <= 42;
                    if (finite) {
                        for (unsigned i = 0; i < 3; ++i) state[12 + i] = static_cast<jlong>(p[i] * 1000);
                        state[15] = static_cast<jlong>(std::sqrt(v[0]*v[0]+v[1]*v[1]+v[2]*v[2])*1000);
                    }
                }
            }
        }
        uintptr_t owner = *reinterpret_cast<uintptr_t *>(g_base + 0xa5fb348);
        uintptr_t work = owner ? *reinterpret_cast<uintptr_t *>(owner + 0x48) : 0;
        uintptr_t settings_root = *reinterpret_cast<uintptr_t *>(g_base + 0xa58de28);
        uintptr_t settings = settings_root ? *reinterpret_cast<uintptr_t *>(settings_root + 0x570) : 0;
        state[16] = work ? *reinterpret_cast<unsigned char *>(work + 0x6c4c0 + 0x102) : 0;
        // The original full-app match can run without this optional settings
        // object (also observed in the retained QEMU host). Preserve absence
        // separately from an existing settings object disabling controllers.
        state[17] = settings ? *reinterpret_cast<unsigned char *>(settings + 0xd3) : -1;
        const auto readiness = lock.manager ? input_readiness() : peerlink::InputReadiness{};
        state[29] = readiness.ready() ? 1 : 0;
        state[30] = readiness.frame;
        state[31] = g_channel_home && g_channel_home == g_uid ? 1 : 0;
        state[33] = peerlink_game_thread_tid();
        state[34] = peerlink_match_result_available();
        static uint64_t next_journal_sample = 0; // Runtime thread, under TaskLock.
        const uint64_t now = monotonic_ms();
        if (now >= next_journal_sample) {
            next_journal_sample = now + 1000;
            journal_sample = true;
            setup_complete = g_settings_sync_complete;
            uintptr_t service = *reinterpret_cast<uintptr_t *>(g_base + 0x9a6a020);
            uintptr_t inner = service && *reinterpret_cast<uintptr_t *>(service) == g_base + 0x98ae9e0
                ? *reinterpret_cast<uintptr_t *>(service + 8) : 0;
            peerlink_match_stop_diagnostics(inner ? *reinterpret_cast<uintptr_t *>(inner + 0xa0) : 0, stop, sizeof(stop));
        }
    }
    if (journal_sample) {
        peerlink_journal("SIMULATION_OBSERVATION engineFrame=%lld outer=%lld core=%lld players=%lld ballX=%lld ballY=%lld ballZ=%lld channel14=%lld channel15=%lld modelPhase=%lld modelError=%lld reasonCode=%lld setupComplete=%u inputReady=%u %s",
            static_cast<long long>(state[20]), static_cast<long long>(state[8]), static_cast<long long>(state[9]),
            static_cast<long long>(state[10]), static_cast<long long>(state[12]), static_cast<long long>(state[13]),
            static_cast<long long>(state[14]), static_cast<long long>(state[3]), static_cast<long long>(state[4]),
            static_cast<long long>(state[5]), static_cast<long long>(state[7]), static_cast<long long>(state[23]),
            setup_complete ? 1U : 0U, state[29] == 1 ? 1U : 0U, stop);
    }
    jlongArray result = env->NewLongArray(kStateSize);
    if (result) env->SetLongArrayRegion(result, 0, kStateSize, state);
    return result;
}

JNIEXPORT jintArray JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativePlayState(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base) || !g_match_stage || !g_settings_sync_complete) return nullptr;
    constexpr unsigned kPlayers = 22;
    constexpr unsigned kHeaderSize = 11;
    jint state[kHeaderSize + 2 * kPlayers]{};
    {
        TaskLock lock;
        const uintptr_t match = *reinterpret_cast<uintptr_t *>(g_base + 0xa533ec0);
        if (!lock.manager || !match || *reinterpret_cast<uint8_t *>(match + 0x138) != 17) return nullptr;
        const auto readiness = input_readiness();
        if (!readiness.ready()) return nullptr;
        auto reject = [&](const char *reason, unsigned detail = 0) -> jintArray {
            static uint64_t next_sample = 0; // EngineRuntime calls this on one thread.
            const uint64_t now = monotonic_ms();
            if (now >= next_sample) {
                next_sample = now + 3000;
                peerlink_journal("BOT_PLAY_READ_REJECT frame=%u reason=%s detail=%u", readiness.frame, reason, detail);
            }
            return nullptr;
        };
        const uintptr_t core = *reinterpret_cast<uintptr_t *>(match + 0x2f8);
        if (!core || *reinterpret_cast<uintptr_t *>(core) != g_base + 0x9899d50 ||
            *reinterpret_cast<uint32_t *>(core + 8) != 54) return reject("match_core");
        const uintptr_t balls = *reinterpret_cast<uintptr_t *>(core + 0x3c98);
        if (!balls || *reinterpret_cast<uintptr_t *>(balls) != g_base + 0x989a160) return reject("ball_manager");
        const uintptr_t ball = *reinterpret_cast<uintptr_t *>(balls + 0x30);
        const unsigned ball_index = *reinterpret_cast<uint32_t *>(balls + 0xc);
        if (!ball || ball_index >= 8 || *reinterpret_cast<uintptr_t *>(balls + 0x38 + ball_index * 8) != ball)
            return reject("ball_slot", ball_index);
        const auto *position = reinterpret_cast<const float *>(ball + 0x30);
        const auto *velocity = reinterpret_cast<const float *>(ball + 0x3c);
        for (unsigned i = 0; i < 3; ++i)
            if (!std::isfinite(position[i]) || !std::isfinite(velocity[i]) ||
                std::abs(position[i]) > 80 || std::abs(velocity[i]) > 140) return reject("ball_values", i);
        if (std::abs(position[0]) > 60 || std::abs(position[2]) > 42) return reject("ball_outside_play");

        const uintptr_t root = entry<uintptr_t (*)()>(0x7a87db8)();
        const uintptr_t info = root ? *reinterpret_cast<uintptr_t *>(root + 0x20) : 0;
        const uintptr_t context = info ? entry<uintptr_t (*)(uintptr_t)>(0x7a58998)(info) : 0;
        if (!context) return reject("participant_context");
        const unsigned side = *reinterpret_cast<uint32_t *>(context + 0x2c);
        if (side > 1) return reject("participant_side", side);
        const uintptr_t sim = entry<uintptr_t (*)()>(0x698f7e0)();
        if (!sim) return reject("simulation_root");

        // Same local pad -> live cursor -> selected player chain as the
        // original match input code at 0x6ba5ce8. The script-observation
        // table at 0x72c747c is a different view and can have no matching cursor.
        const uintptr_t cursors = entry<uintptr_t (*)(uintptr_t)>(0x698f7f0)(sim);
        int selected = -1;
        if (cursors) {
            const unsigned pad = entry<unsigned (*)()>(0x8223884)();
            const unsigned slot = entry<unsigned (*)(uintptr_t, unsigned)>(0x698f7f8)(cursors, pad);
            if (slot < 24) {
                const uintptr_t cursor = entry<uintptr_t (*)(uintptr_t, unsigned)>(0x698fac0)(cursors, slot);
                const unsigned player = entry<unsigned (*)(uintptr_t)>(0x6a4eb78)(cursor);
                if (player < kPlayers && player / 11 == side) selected = static_cast<int>(player);
            }
            if (selected < 0) {
                // The original direct local-pad lookup used by gameplay
                // rules. Accept it only when it resolves to our side.
                const unsigned player = entry<unsigned (*)()>(0x6b75188)();
                if (player < kPlayers && player / 11 == side) selected = static_cast<int>(player);
            }
        }

        // Read the same current global player index that the game queries
        // at 0x733d574. The possession-update routine at 0x6acbb10 writes
        // it via 0x6acd550 after resolving the history and roster itself.
        const uintptr_t ball_context = balls + 0x58;
        uintptr_t possession = entry<uintptr_t (*)(uintptr_t)>(0x72c3ddc)(ball_context);
        if (!possession) possession = entry<uintptr_t (*)(uintptr_t)>(0x72c745c)(sim);
        int owner = -2; // Unknown is distinct from a confirmed loose ball.
        if (possession) {
            const auto *index = entry<const uint32_t *(*)(uintptr_t)>(0x6b60a00)(possession);
            if (*index < kPlayers) owner = static_cast<int>(*index);
            else if (*index == 255) owner = -1;
        }
        const auto keeper = entry<unsigned (*)(uintptr_t, unsigned)>(0x733d0f4);
        const auto match_keeper = [&](unsigned team) {
            const unsigned live = keeper(ball_context, team);
            if (live < kPlayers && live / 11 == team) return live;
            if (!entry<uintptr_t (*)(uintptr_t)>(0x72c749c)(sim)) return 255U;
            return entry<unsigned (*)(uintptr_t, unsigned)>(0x733d078)(sim, team);
        };
        state[0] = static_cast<jint>(readiness.frame);
        state[1] = static_cast<jint>(side);
        state[2] = selected;
        state[3] = owner;
        state[4] = static_cast<jint>(match_keeper(side));
        state[5] = static_cast<jint>(match_keeper(1 - side));
        state[6] = static_cast<jint>(position[0] * 1000);
        state[7] = static_cast<jint>(position[2] * 1000);
        state[8] = static_cast<jint>(position[1] * 1000);
        state[9] = static_cast<jint>(velocity[0] * 1000);
        state[10] = static_cast<jint>(velocity[2] * 1000);
        for (unsigned player = 0; player < kPlayers; ++player) {
            const unsigned slot = kHeaderSize + 2 * player;
            state[slot] = INT32_MIN;
            state[slot + 1] = INT32_MIN;
            const uintptr_t object = *reinterpret_cast<uintptr_t *>(g_base + 0xa5909a0 + 0x170 + player * 8);
            if (!object) continue;
            // The canonical position used by 0x69c5228 -> 0x6b01114 -> 0x6a9d2ac.
            const auto *p = reinterpret_cast<const float *>(object + 0x5dc);
            if (!std::isfinite(p[0]) || !std::isfinite(p[2]) || std::abs(p[0]) > 65 || std::abs(p[2]) > 48)
                continue;
            state[slot] = static_cast<jint>(p[0] * 1000);
            state[slot + 1] = static_cast<jint>(p[2] * 1000);
        }
    }
    jintArray result = env->NewIntArray(kHeaderSize + 2 * kPlayers);
    if (result) env->SetIntArrayRegion(result, 0, kHeaderSize + 2 * kPlayers, state);
    return result;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeInputMotion(
        JNIEnv *env, jobject, jlong base, jfloat x, jfloat y, jfloat dash) {
    if (!valid(base) || !g_match_stage || !g_settings_sync_complete ||
        !std::isfinite(x) || std::abs(x) > 1 || !std::isfinite(y) || std::abs(y) > 1 ||
        !std::isfinite(dash) || dash < 0 || dash > 1) return JNI_FALSE;
    if (x || y || dash) {
        TaskLock lock;
        const uintptr_t match = *reinterpret_cast<uintptr_t *>(g_base + 0xa533ec0);
        if (!lock.manager || !match || *reinterpret_cast<uint8_t *>(match + 0x138) != 17 ||
            !input_readiness().ready()) return JNI_FALSE;
    }
    return gamepad_motion(env, x, y, dash) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeHalftimeConfirm(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base) || !g_settings_sync_complete) return JNI_FALSE;
    TaskLock lock;
    const uintptr_t match = *reinterpret_cast<const uintptr_t *>(g_base + 0xa533ec0);
    if (!lock.manager || !match || *reinterpret_cast<const uint8_t *>(match + 0x138) != 21) return JNI_FALSE;
    const int menu = peerlink_halftime_menu_state(g_base);
    if (menu != 3 && menu != 5) return JNI_FALSE;
    return gamepad_key(env, 96, 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeInputKey(
        JNIEnv *env, jobject, jlong base, jint code, jint action) {
    if (!valid(base) || !g_match_stage || !g_settings_sync_complete) return JNI_FALSE;
    peerlink::InputReadiness readiness;
    if (action == 0) {
        {
            TaskLock lock;
            const uintptr_t match = *reinterpret_cast<uintptr_t *>(g_base + 0xa533ec0);
            if (lock.manager && match && *reinterpret_cast<uint8_t *>(match + 0x138) == 17)
                readiness = input_readiness();
        }
        if (!readiness.ready()) {
            peerlink_journal("BOT_INPUT_NATIVE key=%d action=down accepted=0 commandFrame=%u waitMask=%u tickGate=%u pendingStop=%u stopped=%u",
                code, readiness.frame, readiness.waiting, readiness.tick_gate, readiness.pending_stop, readiness.stopped);
            return JNI_FALSE;
        }
    }
    bool result = gamepad_key(env, code, action);
    if (action == 0) peerlink_journal("BOT_INPUT_NATIVE key=%d action=down accepted=%u commandFrame=%u waitMask=%u tickGate=%u pendingStop=%u stopped=%u",
        code, result ? 1U : 0U, readiness.frame, readiness.waiting, readiness.tick_gate, readiness.pending_stop, readiness.stopped);
    return result ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeNeutralInput(
        JNIEnv *env, jobject self, jlong base) {
    if (!valid(base) || !g_match_stage) return;
    for (int code : {96, 97, 99, 100, 102, 103})
        Java_com_peerlink_app_bot_EngineRuntime_nativeInputKey(env, self, base, code, 1);
    neutral_event(env);
}
JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeProbeController(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base) || !g_core_ready) return JNI_FALSE;
    TaskLock lock;
    entry<void (*)()>(0x6772fbc)();
    if (env->ExceptionCheck() || !neutral_event(env)) return JNI_FALSE;
    return entry<unsigned char (*)(uint32_t)>(0x6773d1c)(0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativePreviewProbe(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base) || !g_core_ready || g_match_stage) return JNI_FALSE;
    TaskLock lock;
    return lock.manager && peerlink_preview_probe(g_base) >= 0 ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeStopPreview(
        JNIEnv *, jobject, jlong base) {
    if (valid(base)) peerlink_preview_stop();
}
JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeLoad(
        JNIEnv *env, jobject, jstring path) {
    if (g_base) return static_cast<jlong>(g_base);
    if (!path) return 0;
    // Bionic API 27+: return freed allocator pages immediately in this isolated
    // engine process. Live allocations and game resource contents are retained.
    g_eager_memory_release = mallopt(M_DECAY_TIME, 0);
    const char *text = env->GetStringUTFChars(path, nullptr);
    if (!text) return 0;
    std::string file(text);
    env->ReleaseStringUTFChars(path, text);
    size_t slash = file.find_last_of('/');
    if (slash != std::string::npos) {
        std::string directory = file.substr(0, slash + 1);
        for (const char *name : {"libgadsme.so", "libplaycore.so"}) {
            dlopen((directory + name).c_str(), RTLD_NOW | RTLD_GLOBAL);
        }
    }
    void *library = dlopen(file.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!library) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlopen failed: %s", dlerror());
        return 0;
    }
    Dl_info info{};
    void *symbol = dlsym(library, "JNI_OnLoad");
    if (!symbol || !dladdr(symbol, &info) || !info.dli_fbase) return 0;
    g_base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    // Loading here only pins and locates the verified binary. Its installed
    // Java class loader and framework NativeActivity perform JNI/lifecycle init.
    return static_cast<jlong>(g_base);
}

JNIEXPORT jint JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeReleaseUnusedMemory(
        JNIEnv *, jobject) {
    // M_PURGE_ALL is supported from API 34; API 31-33 use the normal purge.
    // These documented allocator operations only release pages no longer used.
    int option = M_PURGE;
#ifdef M_PURGE_ALL
    if (android_get_device_api_level() >= 34) option = M_PURGE_ALL;
#endif
    return (g_eager_memory_release ? 2 : 0) | (mallopt(option, 0) ? 1 : 0);
}

// A named sample for the optional profiler. Touch one byte and release it before room creation.
JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeHeapRecordingProbe(
        JNIEnv *, jobject, jint bytes) {
    if (bytes <= 0 || bytes > 8 * 1024 * 1024) return JNI_FALSE;
    auto *sample = static_cast<unsigned char *>(std::malloc(static_cast<size_t>(bytes)));
    if (!sample) return JNI_FALSE;
    *static_cast<volatile unsigned char *>(sample) = 1;
    std::free(sample);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeSetupMemoryGroups(
        JNIEnv *env, jobject, jboolean baseline, jboolean last) {
    static std::mutex mutex;
    std::lock_guard<std::mutex> lock(mutex);
    static peerlink_memory::Sample previous;
    if (baseline) previous = {};

    peerlink_memory::Sample sample;
    FILE *file = fopen("/proc/self/smaps", "re");
    if (!file) {
        previous = {};
        return env->NewStringUTF("status=unavailable");
    }
    const bool complete = peerlink_memory::readSmaps(file, sample);
    fclose(file);
    if (!complete) {
        previous = {};
        return env->NewStringUTF("status=incomplete");
    }
    std::string report = peerlink_memory::describe(sample);
    if (!sample.layoutComplete) report += " mapDeltaStatus=incomplete";
    else if (previous.mappings) report += peerlink_memory::describeMappingChanges(previous, sample);
    else report += baseline ? " mapDeltaStatus=baseline" : " mapDeltaStatus=noPreviousSample";

    if (last || !sample.layoutComplete) previous = {};
    else previous = std::move(sample);
    return env->NewStringUTF(report.c_str());
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeReleaseReadWindow(
        JNIEnv *, jobject, jint fd, jlong start, jlong length) {
    return fd >= 0 && start >= 0 && length > 0 &&
        posix_fadvise64(fd, start, length, POSIX_FADV_DONTNEED) == 0;
}

JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeReclaimMatchPool(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base)) return;
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize < 4096) return;

    // This build has a distinct 230 MiB resource pool. Use the same libc++
    // timed mutex as its allocator and skip the sample if the engine owns it.
    const auto slot = g_base + 0xa54abd0;
    if (*reinterpret_cast<const uintptr_t *>(slot + 8) != g_base + 0x8dadf78) {
        peerlink_journal("BOT_MATCH_POOL status=lock_unverified");
        return;
    }
    auto *mutex = reinterpret_cast<void *>(slot + 0x10);
    const auto tryLock = entry<bool (*)(void *)>(0x8c1ee40);
    const auto unlock = entry<void (*)(void *)>(0x8c1efb0);
    if (!tryLock(mutex)) {
        peerlink_journal("BOT_MATCH_POOL status=busy");
        return;
    }

    peerlink_match_pool::Pages free;
    const uint64_t started = monotonic_ms();
    const char *status = "unavailable";
    size_t before = 0, after = 0;
    int adviseError = 0;
    const auto pool = *reinterpret_cast<const uintptr_t *>(slot);
    if (pool) {
        const auto storage = *reinterpret_cast<const uintptr_t *>(pool);
        const auto size = *reinterpret_cast<const uint32_t *>(pool + 8);
        const auto tail = *reinterpret_cast<const uintptr_t *>(pool + 0x10);
        if (!peerlink_match_pool::freeTailPages(storage, size, tail, static_cast<size_t>(pageSize), free)) {
            status = "layout_unverified";
        } else if (!free.size) {
            status = "no_whole_free_pages";
        } else {
            // Remove the Android heap tag only for the syscalls. Memory reads
            // above use the allocator's original pointers.
            auto *address = reinterpret_cast<void *>(free.start & 0x00ffffffffffffffull);
            constexpr size_t maxPages = peerlink_match_pool::capacity / 4096;
            std::array<unsigned char, maxPages> residence;
            const size_t pageCount = free.size / static_cast<size_t>(pageSize);
            const auto residentBytes = [&] {
                size_t count = 0;
                for (size_t page = 0; page < pageCount; ++page) count += residence[page] & 1u;
                return count * static_cast<size_t>(pageSize);
            };
            if (mincore(address, free.size, residence.data()) != 0) {
                status = "residency_unavailable";
                adviseError = errno;
            } else {
                before = residentBytes();
                if (madvise(address, free.size, MADV_DONTNEED) != 0) {
                    status = "discard_failed";
                    adviseError = errno;
                } else if (mincore(address, free.size, residence.data()) != 0) {
                    status = "discarded_unmeasured";
                    adviseError = errno;
                } else {
                    status = "measured";
                    after = residentBytes();
                }
            }
        }
    }
    unlock(mutex);
    const bool measured = std::strcmp(status, "measured") == 0;
    peerlink_journal("BOT_MATCH_POOL status=%s freeTailKiB=%zu residentBeforeKiB=%zu residentAfterKiB=%zu "
                     "observedDropKiB=%zd durationMs=%llu errno=%d",
                     status, free.size / 1024, before / 1024, after / 1024,
                     measured ? static_cast<ssize_t>(before / 1024) - static_cast<ssize_t>(after / 1024) : -1,
                     static_cast<unsigned long long>(monotonic_ms() - started), adviseError);
}

JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeReleaseEngineCache(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base) || !g_core_ready) return -1;
    // Verified 11.1.1 FMallocBinned2. Other allocator configurations can
    // wrap GMalloc and must not be interpreted using this layout.
    const auto allocator = *reinterpret_cast<const uintptr_t *>(g_base + 0x9f9fd20);
    if (!allocator || *reinterpret_cast<const uintptr_t *>(allocator) != g_base + 0x93680e0 ||
        *reinterpret_cast<const uintptr_t *>(g_base + 0x9368130) != g_base + 0x42bfb50) return -1;

    // The final section of FMallocBinned2::Trim(false) takes this mutex and
    // returns only already-freed OS blocks. Skip this pass if the game is
    // using the allocator lock instead of waiting for it on the host thread.
    auto *mutex = reinterpret_cast<pthread_mutex_t *>(allocator + 0x888);
    if (pthread_mutex_trylock(mutex) != 0) return -2;
    auto *cached_bytes = reinterpret_cast<uint64_t *>(allocator + 0x878);
    auto *cached_blocks = reinterpret_cast<uint32_t *>(allocator + 0x880);
    const uint64_t before = *cached_bytes;
    constexpr uint64_t minimum_trim_bytes = 16 * 1024 * 1024;
    constexpr uint64_t maximum_cache_bytes = 64 * 1024 * 1024;
    const bool sane = before <= maximum_cache_bytes && *cached_blocks <= 64 &&
        (before == 0) == (*cached_blocks == 0);
    if (sane && before >= minimum_trim_bytes) {
        void *records = reinterpret_cast<void *>(allocator + 0x478);
        entry<void (*)(void *, void *, uint32_t *, uint64_t *)>(0x42a9318)
            (records, records, cached_blocks, cached_bytes);
    }
    const jlong dropped = sane ? static_cast<jlong>(before - *cached_bytes) : -1;
    pthread_mutex_unlock(mutex);
    return dropped;
}

JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeReleaseVerifiedFileCache(
        JNIEnv *env, jobject, jstring path) {
    const char *name = env->GetStringUTFChars(path, nullptr);
    if (!name) return 0;
    const int fd = open(name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    env->ReleaseStringUTFChars(path, name);
    if (fd < 0) return 0;
    struct stat metadata{};
    const bool accepted = fstat(fd, &metadata) == 0 && S_ISREG(metadata.st_mode) && metadata.st_size > 0 &&
        posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED) == 0;
    close(fd);
    // A successful hint records the eligible file size, not the bytes that
    // actually left RAM. The file and any active engine mappings stay valid.
    return accepted ? metadata.st_size : 0;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeEnableHeadless(
        JNIEnv *, jobject, jlong base, jboolean missing_match_resources) {
    return valid(base) && peerlink_install_null_client_rhi(g_base, missing_match_resources == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeHeadlessState(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base) || peerlink_null_client_rhi_failed(g_base)) return -1;
    return peerlink_null_client_rhi_ready(g_base) ? 1 : 0;
}

JNIEXPORT jstring JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeHeadlessDiagnostics(
        JNIEnv *env, jobject, jlong) {
    return env->NewStringUTF(peerlink_null_client_rhi_diagnostics());
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeConfigureLocalDiscovery(
        JNIEnv *env, jobject, jlong base, jstring input) {
    if (!valid(base) || !g_core_ready || g_client || !input) return JNI_FALSE;
    const char *address = env->GetStringUTFChars(input, nullptr);
    if (!address) return JNI_FALSE;
    const bool local = strcmp(address, "127.0.0.80") == 0;
    env->ReleaseStringUTFChars(input, address);
    if (!local) return JNI_FALSE;
    TaskLock lock;
    if (!lock.manager) return JNI_FALSE;
    // This is the SDK's owned STUN-host override string, not a connection or
    // socket state. Getter 7e45354 selects it instead of the default hostname;
    // 7dffa28 copies it into the original NTL configuration before discovery.
    // The SDK still sends real UDP requests and parses real STUN responses.
    auto *override = reinterpret_cast<EngineString *>(g_base + 0xa5da978);
    destroy_string(*override);
    memset(override, 0, sizeof(*override));
    entry<void (*)(void *, const char *, size_t)>(0x2f3877c)(override, "127.0.0.80", 10);
    EngineString selected{};
    entry<void (*)(void *)>(0x7e45354)(&selected);
    const unsigned char *bytes = reinterpret_cast<const unsigned char *>(&selected);
    const size_t count = bytes[0] & 1 ? selected.words[1] : bytes[0] >> 1;
    const char *value = bytes[0] & 1 ? reinterpret_cast<const char *>(selected.words[2]) :
        reinterpret_cast<const char *>(bytes + 1);
    const bool accepted = count == 10 && value && memcmp(value, "127.0.0.80", 10) == 0;
    destroy_string(selected);
    __android_log_print(ANDROID_LOG_INFO, TAG, "BOT_DISCOVERY_INPUT local=%u original_getter_verified=%u", accepted, accepted);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeStart(
        JNIEnv *, jobject, jlong base, jlong uid) {
    if (!valid(base) || !g_core_ready || uid <= 0 || uid > 0xffffffffLL) return 0;
    ANativeActivity *activity = framework_activity();
    if (!activity || !activity->vm || !activity->instance) return 0;
    if (g_client) return g_uid == static_cast<uint32_t>(uid) ? static_cast<jlong>(g_client) : 0;
    if (!entry<uintptr_t (*)()>(0x7db9294)() ||
        *reinterpret_cast<uint32_t *>(g_base + 0xa5d5c20) != 3) return 0;
    uintptr_t client = entry<uintptr_t (*)()>(0x7e02524)();
    if (!client) return 0;
    uintptr_t services = *reinterpret_cast<uintptr_t *>(g_base + 0xa5d9cd0);
    uintptr_t clock = services ? *reinterpret_cast<uintptr_t *>(services + 8) : 0;
    if (!clock) return 0;
    peerlink_observe_network_sockets(g_base);
    peerlink_observe_network_startup(g_base);
    peerlink_observe_match_lifecycle(g_base);
    peerlink_observe_match_stop(g_base);
    peerlink_observe_match_frames(g_base);
    entry<void (*)(uintptr_t, uint32_t)>(0x7e14a38)(clock, static_cast<uint32_t>(time(nullptr)));
    // Five real arguments; the old four-argument trampoline left the last
    // argument undefined. Let the engine choose its real local UDP port.
    if (!(entry<uint32_t (*)(uintptr_t, uint32_t, uint32_t, int, unsigned char)>(0x7e02878)(
            client, 1, 0, 0, 0) & 1)) return 0;
    entry<void (*)(uintptr_t, uint32_t)>(0x7db9730)(0, static_cast<uint32_t>(uid));
    if (*reinterpret_cast<uint32_t *>(client + 0x948) != static_cast<uint32_t>(uid)) return 0;
    g_client = client;
    g_uid = static_cast<uint32_t>(uid);
    return static_cast<jlong>(client);
}

JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeCall(
        JNIEnv *, jobject, jlong base, jlong offset, jlong a0, jlong a1, jlong a2, jlong a3) {
    if (!valid(base) || offset < 0 || static_cast<uint64_t>(offset) >= kEngineSize) return 0;
    return entry<jlong (*)(jlong, jlong, jlong, jlong)>(static_cast<uint64_t>(offset))(a0, a1, a2, a3);
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeSetPeers(
        JNIEnv *env, jobject, jlong base, jbyteArray bytes) {
    if (!valid(base) || !g_client || !bytes || env->GetArrayLength(bytes) != 0x218) return JNI_FALSE;
    auto *record = static_cast<unsigned char *>(calloc(1, 0x218));
    if (!record) return JNI_FALSE;
    env->GetByteArrayRegion(bytes, 0, 0x218, reinterpret_cast<jbyte *>(record));
    if (env->ExceptionCheck()) { free(record); return JNI_FALSE; }
    // This is an owned input interface object, not an internal connection slot.
    *reinterpret_cast<uintptr_t *>(record) = g_base + 0x98da618;
    uint32_t result = entry<uint32_t (*)(uintptr_t, const void *, uint32_t)>(0x7db96b4)(0, record, 1);
    free(record);
    return result & 1 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeTurnRequest(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base) || !g_client) return nullptr;
    TaskLock lock;
    uintptr_t turn = turn_service();
    if (!lock.manager || !turn) return nullptr;
    // The original request preparer at 0x7e90154 uses exactly these getters.
    EngineString parameter = entry<EngineString (*)(uintptr_t)>(0x7e23024)(turn);
    const auto *raw = reinterpret_cast<const unsigned char *>(&parameter);
    size_t size = (raw[0] & 1) ? parameter.words[1] : raw[0] >> 1;
    const auto *text = (raw[0] & 1) ? reinterpret_cast<const unsigned char *>(parameter.words[2]) : raw + 1;
    uint32_t limit = entry<uint32_t (*)(uintptr_t)>(0x7e23220)(turn);
    jbyteArray result = nullptr;
    if (text && size && size <= 1024) {
        std::vector<unsigned char> bytes(size + 4);
        for (unsigned i = 0; i < 4; ++i) bytes[i] = static_cast<unsigned char>(limit >> (24 - i * 8));
        memcpy(bytes.data() + 4, text, size);
        result = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte *>(bytes.data()));
    }
    destroy_string(parameter);
    return result;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeConfigureTurn(
        JNIEnv *env, jobject, jlong base, jbyteArray input) {
    if (!valid(base) || !g_client || !input) return JNI_FALSE;
    jsize count = env->GetArrayLength(input);
    if (count < 8 || count > 131072) return JNI_FALSE;
    std::vector<unsigned char> bytes(static_cast<size_t>(count));
    env->GetByteArrayRegion(input, 0, count, reinterpret_cast<jbyte *>(bytes.data()));
    if (env->ExceptionCheck() || memcmp(bytes.data(), "TRN1", 4)) return JNI_FALSE;
    TurnInputReader reader{bytes.data(), bytes.size(), 4};
    uint32_t servers = reader.number();
    if (servers < 1 || servers > 64) return JNI_FALSE;
    struct RowInput { uint32_t zone, port, tls_port; std::string address; };
    std::vector<RowInput> inputs;
    bool have_tls = false;
    for (uint32_t i = 0; i < servers && reader.ok; ++i) {
        RowInput row{reader.number(), reader.number(), reader.number(), reader.blob(253)};
        if (row.port > 65535 || row.tls_port > 65535) reader.ok = false;
        for (unsigned char c : row.address) if (c < 33 || c > 126) reader.ok = false;
        have_tls = have_tls || row.tls_port;
        inputs.push_back(std::move(row));
    }
    std::string id = reader.blob(1024), password = reader.blob(1024), origin = reader.blob(1024, true);
    std::string certificate = reader.blob(2048);
    if (!reader.ok || reader.position != reader.size || !have_tls ||
        id.find('\0') != std::string::npos || password.find('\0') != std::string::npos ||
        origin.find('\0') != std::string::npos) return JNI_FALSE;
    TaskLock lock;
    uintptr_t manager = session_manager(), turn = turn_service();
    if (!lock.manager || !turn || !manager || *reinterpret_cast<uintptr_t *>(manager + 0x38)) return JNI_FALSE;
    // The original response callback takes these owned parsed response DTOs.
    // It copies the account/certificate and selects the original TLS endpoints.
    alignas(8) unsigned char account[0x850]{};
    auto construct = entry<void (*)(void *, const char *, size_t)>(0x2f3877c);
    construct(account, id.data(), id.size());
    construct(account + 0x18, password.data(), password.size());
    construct(account + 0x30, origin.data(), origin.size());
    memcpy(account + 0x48, certificate.data(), certificate.size());
    *reinterpret_cast<uint32_t *>(account + 0x848) = 2048;
    *reinterpret_cast<uint32_t *>(account + 0x84c) = static_cast<uint32_t>(certificate.size());
    entry<void (*)(const void *)>(0x7e903dc)(account);
    for (size_t offset : {size_t(0), size_t(0x18), size_t(0x30)})
        destroy_string(*reinterpret_cast<EngineString *>(account + offset));
    std::vector<TurnServerRow> rows(inputs.size());
    for (size_t i = 0; i < inputs.size(); ++i) {
        rows[i].zone = inputs[i].zone;
        rows[i].port = inputs[i].port;
        rows[i].tls_port = inputs[i].tls_port;
        construct(&rows[i].address, inputs[i].address.data(), inputs[i].address.size());
    }
    TurnServerRow *begin = rows.data(), *end = begin + rows.size();
    entry<void (*)(TurnServerRow **, TurnServerRow **)>(0x7e902ec)(&begin, &end);
    for (auto &row : rows) destroy_string(row.address);
    uintptr_t list = entry<uintptr_t (*)(uintptr_t)>(0x7e23468)(turn);
    bool accepted = list && *reinterpret_cast<uintptr_t *>(list) != *reinterpret_cast<uintptr_t *>(list + 8);
    __android_log_print(ANDROID_LOG_INFO, TAG, "original TURN configuration accepted=%u", accepted);
    return accepted ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeTurnAddress(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base) || !g_client) return nullptr;
    TaskLock lock;
    uintptr_t turn = turn_service();
    if (!lock.manager || !turn) return nullptr;
    uint32_t count = entry<uint32_t (*)(uintptr_t)>(0x7e236e0)(turn);
    uintptr_t address = entry<uintptr_t (*)(uintptr_t)>(0x7e236ac)(turn);
    if (!address || !count || count > 2048) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(count));
    if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(count), reinterpret_cast<const jbyte *>(address));
    return result;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativePeerTurnAddress(
        JNIEnv *env, jobject, jlong base, jbyteArray input) {
    if (!valid(base) || !g_client || !input) return JNI_FALSE;
    jsize count = env->GetArrayLength(input);
    if (count < 1 || count > 2048) return JNI_FALSE;
    std::vector<unsigned char> bytes(static_cast<size_t>(count));
    env->GetByteArrayRegion(input, 0, count, reinterpret_cast<jbyte *>(bytes.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    TaskLock lock;
    uintptr_t turn = turn_service();
    if (!lock.manager || !turn) return JNI_FALSE;
    entry<void (*)(uintptr_t, const void *, uint32_t)>(0x7e23714)(turn, bytes.data(), static_cast<uint32_t>(count));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeBeginIntroduction(
        JNIEnv *, jobject, jlong base, jlong peer, jlong connecting_start) {
    if (!valid(base) || !g_client || peer <= 0 || peer > 0xffffffffLL || peer == g_uid ||
        connecting_start <= 0 || connecting_start > 0xffffffffLL) return JNI_FALSE;
    return begin_introduction(static_cast<uint32_t>(peer), static_cast<uint32_t>(connecting_start)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeTransportIoReady(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base) || !g_client || !g_introduction_peer) return -1;
    // Read the exact pointer chains used by original getters 7e02930/7e029c4.
    // SDK Start returns before these asynchronous network-I/O objects exist.
    // TaskMatchSession's P2P branch (config enum 1) keeps discovery running;
    // stopping it here destroys the I/O needed by the configured transport.
    for (uintptr_t offset : {uintptr_t(0x8f8), uintptr_t(0x900)}) {
        uintptr_t holder = *reinterpret_cast<uintptr_t *>(g_client + offset);
        uintptr_t io = holder ? *reinterpret_cast<uintptr_t *>(holder + 8) : 0;
        if (io && *reinterpret_cast<uintptr_t *>(io + 8)) return 1;
    }
    return 0;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeOpenSession(
        JNIEnv *, jobject, jlong base, jlong peer, jlong connecting_start) {
    if (!valid(base) || !g_client || peer <= 0 || peer > 0xffffffffLL || peer == g_uid ||
        connecting_start <= 0 || connecting_start > 0xffffffffLL) return JNI_FALSE;
    uintptr_t manager = session_manager();
    if (!manager || *reinterpret_cast<uintptr_t *>(manager + 0x38)) return JNI_FALSE;
    auto *request = static_cast<unsigned char *>(calloc(1, 0x110));
    if (!request) return JNI_FALSE;
    entry<void (*)(void *)>(0x7cebe78)(request);
    auto *config = request + 0x20;
    *reinterpret_cast<uint32_t *>(config) = 1; // Owned P2P input enum.
    append_identity(config + 0x10, g_uid);
    append_identity(config + 0x28, static_cast<uint32_t>(peer));
    uint32_t accepted = 0;
    if (entry<uint32_t (*)(const void *)>(0x7d52c14)(config) & 1) {
        if (begin_introduction(static_cast<uint32_t>(peer), static_cast<uint32_t>(connecting_start))) {
            uintptr_t holder = *reinterpret_cast<uintptr_t *>(g_base + 0xa535738);
            uintptr_t provider = holder ? *reinterpret_cast<uintptr_t *>(holder) : 0;
            // TaskMatchSession enters through OnlineSystemMultiplay, which
            // prepares its transport provider before creating the session.
            // Calling only the inner session factory bypasses that setup.
            if (provider) accepted = entry<uint32_t (*)(uintptr_t, const void *)>(0x7cc4608)(provider, config);
        }
    }
    entry<void (*)(void *)>(0x7b33bf8)(config);
    free(request);
    __android_log_print(ANDROID_LOG_INFO, TAG, "original provider session accepted=%u", accepted & 1);
    return accepted & 1 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeTick(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base) || !g_client) return;
    entry<void (*)()>(0x2f4b5ec)();
    uintptr_t manager = session_manager();
    if (manager) entry<void (*)(uintptr_t)>(0x7cf23ac)(manager);
}

JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeAlloc(JNIEnv *, jobject, jint size) {
    return size > 0 ? reinterpret_cast<jlong>(calloc(1, static_cast<size_t>(size))) : 0;
}
JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeFree(JNIEnv *, jobject, jlong addr) {
    free(reinterpret_cast<void *>(static_cast<uintptr_t>(addr)));
}
JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeWrite(
        JNIEnv *env, jobject, jlong addr, jbyteArray bytes) {
    if (addr && bytes) env->GetByteArrayRegion(bytes, 0, env->GetArrayLength(bytes), reinterpret_cast<jbyte *>(addr));
}
JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeRead(
        JNIEnv *env, jobject, jlong addr, jbyteArray bytes) {
    if (addr && bytes) env->SetByteArrayRegion(bytes, 0, env->GetArrayLength(bytes), reinterpret_cast<jbyte *>(addr));
}
JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeBuildBlock(
        JNIEnv *env, jobject, jlong base, jlong dst, jint family, jstring address, jint port) {
    if (!valid(base) || !dst || !address) return 0;
    const char *text = env->GetStringUTFChars(address, nullptr);
    if (!text) return 0;
    bool result = build_address(reinterpret_cast<void *>(static_cast<uintptr_t>(dst)), family, text, port);
    env->ReleaseStringUTFChars(address, text);
    return result ? 1 : 0;
}
}
