// Original build-B entry points, owned input DTOs, and real Android JNI input.
// No fake JavaVM or connection-state writes. Preview inputs temporarily wrap
// the menu tick and restore it before forwarding the original callback.
#include <jni.h>
#include <dlfcn.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
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
#include <ctime>
#include <string>
#include <cmath>
#include <vector>
#include <utility>
#include <unistd.h>

extern "C" {
long peerlink_game_database_input(uintptr_t, const char *);
int peerlink_squad_import(uintptr_t, uint32_t, uint32_t, const char *);
int peerlink_player_cache_import(uintptr_t, uint32_t, const char *);
uint32_t peerlink_lab_uid_participants(uintptr_t, uint32_t, uint32_t, uint32_t);
uintptr_t peerlink_original_online_inputs_v224(uintptr_t, uint32_t, uint32_t);
long peerlink_live_match_context_input(uintptr_t, const char *);
long peerlink_original_preview_proceed_v271(uintptr_t);
long peerlink_preview_drive(uintptr_t);
long peerlink_preview_probe(uintptr_t);
void peerlink_preview_stop();
void peerlink_preview_diagnostics(char *, size_t);
}

#define TAG "PeerlinkEngineHost"
namespace {
constexpr uint64_t kEngineSize = 161937720ULL;
uintptr_t g_base = 0;
uintptr_t g_client = 0;
uint32_t g_uid = 0;
bool g_core_ready = false;
std::string g_squad_path;
std::string g_match_context_path;
int g_match_stage = 0;
uintptr_t g_user_sync_task = 0;
bool g_user_sync_complete = false;
uint64_t g_user_sync_started_ms = 0;
bool g_preview_proceeded = false;
bool g_controller_scanned = false;
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
bool input_event(JNIEnv *env, jobject event, bool motion) {
    if (!event || env->ExceptionCheck()) return false;
    void *android = dlopen("libandroid.so", RTLD_NOW);
    auto convert = reinterpret_cast<const AInputEvent *(*)(JNIEnv *, jobject)>(
        android ? dlsym(android, motion ? "AMotionEvent_fromJava" : "AKeyEvent_fromJava") : nullptr);
    auto release = reinterpret_cast<void (*)(const AInputEvent *)>(android ? dlsym(android, "AInputEvent_release") : nullptr);
    const AInputEvent *native = convert ? convert(env, event) : nullptr;
    bool ok = native && release && !env->ExceptionCheck();
    if (ok) { entry<void (*)(const AInputEvent *)>(0x677264c)(native); release(native); }
    env->DeleteLocalRef(event);
    if (android) dlclose(android);
    return ok;
}
bool neutral_event(JNIEnv *env) {
    jclass factory = env->FindClass("com/peerlink/app/bot/SoftwareGamepadEvents");
    jmethodID method = factory ? env->GetStaticMethodID(factory, "neutral", "()Landroid/view/MotionEvent;") : nullptr;
    jobject event = method ? env->CallStaticObjectMethod(factory, method) : nullptr;
    bool result = input_event(env, event, true);
    if (factory) env->DeleteLocalRef(factory);
    return result;
}
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

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeConfigureRevisionCheck(
        JNIEnv *, jobject, jlong base, jboolean enabled) {
    if (!valid(base) || !g_client) return JNI_FALSE;
    TaskLock lock;
    // Apply only before introduction/model creation, just as the normal client
    // does after CMD_GET_SERVER_ENV. 07740314 parses is_revision_check into
    // response+990; 0773d85c compares it with YES and stores config+4dc.
    // 07a1df90 then copies that policy into the new model. This never writes a
    // model phase, checksum, connection flag or validation result.
    if (!lock.manager || g_introduction_peer || *reinterpret_cast<uintptr_t *>(g_base + 0xa5bb188))
        return JNI_FALSE;
    uintptr_t services = entry<uintptr_t (*)()>(0x7e0afbc)();
    uintptr_t config = services ? *reinterpret_cast<uintptr_t *>(services + 0x10) : 0;
    if (!config) return JNI_FALSE;
    auto *policy = reinterpret_cast<unsigned char *>(config + 0x4dc);
    *policy = enabled == JNI_TRUE ? 1 : 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "server revision policy applied enabled=%u", *policy);
    return *policy == (enabled == JNI_TRUE ? 1 : 0) ? JNI_TRUE : JNI_FALSE;
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

JNIEXPORT jstring JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeLoadingDiagnostics(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base)) return env->NewStringUTF("MATCH_LOADING unavailable=engine_not_loaded");
    TaskLock lock;
    if (!lock.manager) return env->NewStringUTF("MATCH_LOADING unavailable=no_task_manager");
    std::string text = "MATCH_LOADING hostStage=" + std::to_string(g_match_stage) +
        " previewEventSubmitted=" + std::to_string(g_preview_proceeded) +
        " userDataSyncComplete=" + std::to_string(g_user_sync_complete);
    char preview[512];
    peerlink_preview_diagnostics(preview, sizeof(preview));
    text += " "; text += preview;
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
        if (*reinterpret_cast<uintptr_t *>(node) == g_base + 0x996fde8) {
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
        JNIEnv *env, jobject, jlong base, jlong peer, jstring squad, jstring context) {
    if (!valid(base) || !g_client || peer <= 0 || peer > 0xffffffffLL || peer == g_uid ||
        !squad || !context || g_match_stage) return JNI_FALSE;
    const char *squad_path = env->GetStringUTFChars(squad, nullptr);
    if (!squad_path) return JNI_FALSE;
    const char *context_path = env->GetStringUTFChars(context, nullptr);
    if (!context_path) { env->ReleaseStringUTFChars(squad, squad_path); return JNI_FALSE; }
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
        }
    }
    env->ReleaseStringUTFChars(squad, squad_path);
    env->ReleaseStringUTFChars(context, context_path);
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
        entry<uintptr_t (*)()>(0x7b2a3a8)();
        if (peerlink_squad_import(g_base, 0, g_uid, g_squad_path.c_str()) <= 0) return -202;
        uintptr_t online = entry<uintptr_t (*)()>(0x7a1d8d4)();
        if (!online) return -203;
        entry<void (*)(uintptr_t)>(0x7a2469c)(online);
        uintptr_t flow = *reinterpret_cast<uintptr_t *>(g_base + 0xa53b6e8);
        if (!flow) return -204;
        entry<void (*)(uintptr_t, const char *)>(0x6aa70a8)(flow + 0x118, "Online/Match/MatchProcessPreMenu");
        g_match_stage = 2;
    }
    if (g_match_stage == 2 && !g_preview_proceeded) {
        long preview = peerlink_preview_drive(g_base);
        if (preview == -2) { peerlink_preview_stop(); return -209; }
        if (preview < 0) { peerlink_preview_stop(); return -210; }
        if (preview == 1) g_preview_proceeded = true;
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
    return g_match_stage;
}

JNIEXPORT jlongArray JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeSnapshot(
        JNIEnv *env, jobject, jlong base, jboolean passive) {
    // Append-only IPC layout. Diagnostic fields are reads under the same
    // original task lock; they never change the engine's connection verdict.
    constexpr unsigned kStateSize = 29;
    jlong state[kStateSize]{};
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
    }
    jlongArray result = env->NewLongArray(kStateSize);
    if (result) env->SetLongArrayRegion(result, 0, kStateSize, state);
    return result;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeInputKey(
        JNIEnv *env, jobject, jlong base, jint code, jint action) {
    if (!valid(base) || !g_match_stage) return JNI_FALSE;
    jclass factory = env->FindClass("com/peerlink/app/bot/SoftwareGamepadEvents");
    jmethodID method = factory ? env->GetStaticMethodID(factory, "key", "(II)Landroid/view/KeyEvent;") : nullptr;
    jobject event = method ? env->CallStaticObjectMethod(factory, method, code, action) : nullptr;
    bool result = input_event(env, event, false);
    if (factory) env->DeleteLocalRef(factory);
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
