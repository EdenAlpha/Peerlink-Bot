// Original build-B entry points, owned input DTOs, and real Android JNI input.
// No fake JavaVM, connection-state writes or replacement game callbacks.
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
#include <unistd.h>

extern "C" {
long peerlink_game_database_input(uintptr_t, const char *);
int peerlink_squad_import(uintptr_t, uint32_t, uint32_t, const char *);
int peerlink_player_cache_import(uintptr_t, uint32_t, const char *);
uint32_t peerlink_lab_uid_participants(uintptr_t, uint32_t, uint32_t, uint32_t);
uintptr_t peerlink_original_online_inputs_v224(uintptr_t, uint32_t, uint32_t);
long peerlink_live_match_context_input(uintptr_t, const char *);
long peerlink_original_preview_proceed_v271(uintptr_t);
}

#define TAG "PeerlinkEngineHost"
namespace {
constexpr uint64_t kEngineSize = 161937720ULL;
uintptr_t g_base = 0;
ANativeActivity g_activity{};
jobject g_assets = nullptr;
std::string g_internal_path, g_external_path;
uintptr_t g_client = 0;
uint32_t g_uid = 0;
bool g_core_ready = false;
ANativeActivityCallbacks g_callbacks{};
ANativeWindow *g_window = nullptr;
std::string g_obb_path, g_squad_path;
int g_match_stage = 0;
bool g_preview_proceeded = false;
bool g_controller_scanned = false;

struct EngineString { uint64_t words[3]; };
struct EnginePair { EngineString identity, channel; };
static_assert(sizeof(EngineString) == 24 && sizeof(EnginePair) == 48, "build-B libc++ ABI");
static_assert(offsetof(ANativeActivity, vm) == 8 && offsetof(ANativeActivity, clazz) == 24,
              "build-B ANativeActivity ABI requires LP64");

template <typename Fn> Fn entry(uint64_t offset) {
    return reinterpret_cast<Fn>(g_base + offset);
}
bool valid(jlong base) { return g_base && static_cast<uintptr_t>(base) == g_base; }
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

struct TaskLock {
    uintptr_t manager;
    TaskLock() : manager(entry<uintptr_t (*)(uint32_t)>(0x822933c)(1)) {
        if (manager) entry<void (*)(uintptr_t)>(0x82298c8)(manager);
    }
    ~TaskLock() { if (manager) entry<void (*)(uintptr_t)>(0x82298d0)(manager); }
};
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
}

extern "C" {
JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeStartApplication(
        JNIEnv *env, jobject, jlong base, jobject surface, jstring root, jstring command) {
    if (!valid(base) || !g_activity.vm || !surface || !root || !command) return JNI_FALSE;
    if (g_activity.instance) return JNI_TRUE;
    const char *directory = env->GetStringUTFChars(root, nullptr);
    if (!directory) return JNI_FALSE;
    std::string assets = std::string(directory) + "/assets";
    g_obb_path = std::string(directory) + "/obb";
    env->ReleaseStringUTFChars(root, directory);
    if (chdir(assets.c_str())) return JNI_FALSE;
    const jchar *arguments = env->GetStringChars(command, nullptr);
    if (!arguments) return JNI_FALSE;
    std::u16string copy(reinterpret_cast<const char16_t *>(arguments), env->GetStringLength(command));
    env->ReleaseStringChars(command, arguments);
    entry<void (*)(const char16_t *)>(0x438062c)(copy.c_str());
    g_window = ANativeWindow_fromSurface(env, surface);
    if (!g_window) return JNI_FALSE;
    g_activity.callbacks = &g_callbacks;
    g_activity.obbPath = g_obb_path.c_str();
    entry<void (*)(ANativeActivity *, void *, size_t)>(0x288a448)(&g_activity, nullptr, 0);
    return g_activity.instance ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeResumeApplication(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base) || !g_activity.instance || !g_window) return;
    if (g_callbacks.onStart) g_callbacks.onStart(&g_activity);
    if (g_callbacks.onResume) g_callbacks.onResume(&g_activity);
    if (g_callbacks.onNativeWindowCreated) g_callbacks.onNativeWindowCreated(&g_activity, g_window);
    ARect bounds{0, 0, 320, 180};
    if (g_callbacks.onContentRectChanged) g_callbacks.onContentRectChanged(&g_activity, &bounds);
    if (g_callbacks.onWindowFocusChanged) g_callbacks.onWindowFocusChanged(&g_activity, 1);
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeApplicationReady(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base) || !g_activity.instance) return JNI_FALSE;
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
    long result;
    {
        TaskLock lock;
        uintptr_t owner = *reinterpret_cast<uintptr_t *>(g_base + 0xa5fb348);
        uintptr_t work = owner ? *reinterpret_cast<uintptr_t *>(owner + 0x40) : 0;
        result = work && *reinterpret_cast<uint32_t *>(work + 0x319db64) ? 44 :
            peerlink_game_database_input(g_base, directory);
        uintptr_t services = entry<uintptr_t (*)()>(0x677cfa4)();
        uintptr_t data = services ? *reinterpret_cast<uintptr_t *>(services + 9 * 8) : 0;
        if (result == 44 && data && *reinterpret_cast<uintptr_t *>(data) == g_base + 0x988c270 &&
            !*reinterpret_cast<uintptr_t *>(data + 0x80)) entry<void (*)(uintptr_t)>(0x68c3768)(data);
    }
    env->ReleaseStringUTFChars(path, directory);
    __android_log_print(ANDROID_LOG_INFO, TAG, "database startup result=%ld", result);
    return result == 44 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeStartupInfo(
        JNIEnv *env, jobject, jlong base) {
    if (!valid(base)) return env->NewStringUTF("startup: engine not loaded");
    uintptr_t app = reinterpret_cast<uintptr_t>(g_activity.instance);
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
            result = peerlink_squad_import(g_base, 0, g_uid, squad_path);
            if (result > 0 && !peerlink_lab_uid_participants(g_base, g_uid, static_cast<uint32_t>(peer), g_uid)) result = -101;
            if (result > 0) result = peerlink_player_cache_import(g_base, g_uid, squad_path);
            if (result > 0 && !peerlink_original_online_inputs_v224(g_base, g_uid, static_cast<uint32_t>(peer))) result = -102;
            if (result > 0) {
                uintptr_t task = entry<uintptr_t (*)(const char *)>(0x7b488c8)("TaskSyncUserData");
                uintptr_t task_root = *reinterpret_cast<uintptr_t *>(lock.manager + 0x118);
                if (!task || !task_root) result = -103;
                else entry<void (*)(uintptr_t, uintptr_t)>(0x822b6d0)(task_root, task);
            }
            if (result > 0) {
                entry<void (*)()>(0x6a9e2bc)();
                result = static_cast<int>(peerlink_live_match_context_input(g_base, context_path));
            }
        }
        if (result > 0) { g_match_stage = 1; g_squad_path = squad_path; }
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
    if (!g_controller_scanned) {
        entry<void (*)()>(0x6772fbc)();
        if (env->ExceptionCheck()) return -3;
        if (!neutral_event(env)) return -4;
        g_controller_scanned = true;
    }
    uintptr_t model = *reinterpret_cast<uintptr_t *>(g_base + 0xa5bb188);
    if (model && *reinterpret_cast<unsigned char *>(model + 0x7f2)) return -201;
    if (g_match_stage == 1 && model && *reinterpret_cast<uint32_t *>(model + 0x7b8) == 5 &&
        *reinterpret_cast<unsigned char *>(model + 0x7f1)) {
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
    if (g_match_stage == 2 && !g_preview_proceeded && peerlink_original_preview_proceed_v271(g_base) == 1)
        g_preview_proceeded = true;
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
        JNIEnv *env, jobject, jlong base) {
    jlong state[18]{};
    if (valid(base)) {
        TaskLock lock;
        state[0] = *reinterpret_cast<unsigned char *>(g_base + 0xa53b850);
        state[1] = g_client ? entry<uint32_t (*)(uintptr_t)>(0x7e013ac)(g_client) : 0;
        uintptr_t manager = session_manager();
        if (manager) {
            entry<void (*)(uintptr_t)>(0x2f39738)(manager);
            uintptr_t session = *reinterpret_cast<uintptr_t *>(manager + 0x38);
            if (session) {
                state[2] = entry<uint32_t (*)(uintptr_t)>(0x7cedee8)(session);
                state[3] = entry<uint32_t (*)(uintptr_t, uint32_t)>(0x7cee08c)(session, 14);
                state[4] = entry<uint32_t (*)(uintptr_t, uint32_t)>(0x7cee08c)(session, 15);
            }
            entry<void (*)(uintptr_t)>(0x8c1f0d0)(manager + 8);
        }
        uintptr_t model = *reinterpret_cast<uintptr_t *>(g_base + 0xa5bb188);
        if (model) {
            state[5] = *reinterpret_cast<uint32_t *>(model + 0x7b8);
            state[6] = *reinterpret_cast<unsigned char *>(model + 0x7f1);
            state[7] = *reinterpret_cast<unsigned char *>(model + 0x7f2);
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
        state[17] = settings ? *reinterpret_cast<unsigned char *>(settings + 0xd3) : 0;
    }
    jlongArray result = env->NewLongArray(18);
    if (result) env->SetLongArrayRegion(result, 0, 18, state);
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
    // Full UE4 JNI_OnLoad/activity startup is deliberately not invoked: this
    // host uses the original networking helpers without launching the renderer.
    return static_cast<jlong>(g_base);
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativePrepareCore(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base)) return JNI_FALSE;
    if (g_core_ready) return JNI_TRUE;
    // Same original constructor order as the clean two-engine lab. Optional
    // Java-dependent services are constructed before registering the activity.
    if (!*reinterpret_cast<uintptr_t *>(g_base + 0xa535738)) entry<void (*)()>(0x7e06990)();
    if (!*reinterpret_cast<uintptr_t *>(g_base + 0xa5fb028)) entry<void (*)()>(0x8226208)();
    if (!*reinterpret_cast<uintptr_t *>(g_base + 0xa5d9cd0)) entry<void (*)()>(0x7e0af8c)();
    uintptr_t services = *reinterpret_cast<uintptr_t *>(g_base + 0xa5d9cd0);
    uintptr_t clock = services ? *reinterpret_cast<uintptr_t *>(services + 8) : 0;
    if (!clock || !session_manager()) return JNI_FALSE;
    entry<void (*)(uintptr_t, uint32_t)>(0x7e14a38)(clock, static_cast<uint32_t>(time(nullptr)));
    entry<void (*)(int)>(0x822ee30)(1);
    g_core_ready = true;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeInstallAndroidHost(
        JNIEnv *env, jobject, jlong base, jobject activity, jobject assets,
        jstring internal_path, jstring external_path) {
    if (!valid(base) || !activity || !assets || !internal_path) return JNI_FALSE;
    if (g_activity.clazz) return env->IsSameObject(g_activity.clazz, activity);
    JavaVM *vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || !vm) return JNI_FALSE;
    AAssetManager *manager = AAssetManager_fromJava(env, assets);
    if (!manager) return JNI_FALSE;
    const char *internal = env->GetStringUTFChars(internal_path, nullptr);
    if (!internal) return JNI_FALSE;
    g_internal_path = internal;
    env->ReleaseStringUTFChars(internal_path, internal);
    if (external_path) {
        const char *external = env->GetStringUTFChars(external_path, nullptr);
        if (!external) return JNI_FALSE;
        g_external_path = external;
        env->ReleaseStringUTFChars(external_path, external);
    }
    jobject receiver = env->NewGlobalRef(activity);
    if (!receiver) return JNI_FALSE;
    g_assets = env->NewGlobalRef(assets);
    if (!g_assets) { env->DeleteGlobalRef(receiver); return JNI_FALSE; }
    g_activity.vm = vm;
    g_activity.env = env;
    g_activity.clazz = receiver;
    g_activity.internalDataPath = g_internal_path.c_str();
    g_activity.externalDataPath = g_external_path.empty() ? nullptr : g_external_path.c_str();
    g_activity.sdkVersion = android_get_device_api_level();
    g_activity.assetManager = manager;
    entry<void (*)(ANativeActivity *)>(0x2f4ebc0)(&g_activity);
    return entry<ANativeActivity *(*)()>(0x2f4ebcc)() == &g_activity ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineRuntime_nativeStart(
        JNIEnv *, jobject, jlong base, jlong uid) {
    if (!valid(base) || !g_core_ready || !g_activity.vm || uid <= 0 || uid > 0xffffffffLL) return 0;
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
        uintptr_t sdk = entry<uintptr_t (*)()>(0x7db9468)();
        if (sdk) {
            entry<void (*)(uintptr_t, uint32_t, uint32_t, uint32_t)>(0x7db99ec)(sdk, static_cast<uint32_t>(connecting_start), 0, 1);
            accepted = entry<uint32_t (*)(uintptr_t, const void *)>(0x7cf16fc)(manager, config);
        }
    }
    entry<void (*)(void *)>(0x7b33bf8)(config);
    free(request);
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
