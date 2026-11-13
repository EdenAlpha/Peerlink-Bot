// Original build-B entry points, owned input DTOs, and real Android JNI input.
// No fake JavaVM, connection-state writes or replacement game callbacks.
#include <jni.h>
#include <dlfcn.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/native_activity.h>
#include <android/api-level.h>
#include <arpa/inet.h>
#include <cstdint>
#include <cstddef>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>

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
}

extern "C" {
JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineHost_nativeLoad(
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

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineHost_nativePrepareCore(
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

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineHost_nativeInstallAndroidHost(
        JNIEnv *env, jobject, jlong base, jobject activity, jobject assets,
        jstring internal_path, jstring external_path) {
    if (!valid(base) || !g_core_ready || !activity || !assets || !internal_path) return JNI_FALSE;
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

JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineHost_nativeStart(
        JNIEnv *, jobject, jlong base, jlong uid) {
    if (!valid(base) || !g_core_ready || !g_activity.vm || uid <= 0 || uid > 0xffffffffLL) return 0;
    if (g_client) return g_uid == static_cast<uint32_t>(uid) ? static_cast<jlong>(g_client) : 0;
    if (!entry<uintptr_t (*)()>(0x7db9294)() ||
        *reinterpret_cast<uint32_t *>(g_base + 0xa5d5c20) != 3) return 0;
    uintptr_t client = entry<uintptr_t (*)()>(0x7e02524)();
    if (!client) return 0;
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

JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineHost_nativeCall(
        JNIEnv *, jobject, jlong base, jlong offset, jlong a0, jlong a1, jlong a2, jlong a3) {
    if (!valid(base) || offset < 0 || static_cast<uint64_t>(offset) >= kEngineSize) return 0;
    return entry<jlong (*)(jlong, jlong, jlong, jlong)>(static_cast<uint64_t>(offset))(a0, a1, a2, a3);
}

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineHost_nativeSetPeers(
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

JNIEXPORT jboolean JNICALL Java_com_peerlink_app_bot_EngineHost_nativeOpenSession(
        JNIEnv *, jobject, jlong base, jlong peer) {
    if (!valid(base) || !g_client || peer <= 0 || peer > 0xffffffffLL || peer == g_uid) return JNI_FALSE;
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
        uint32_t now = entry<uint32_t (*)()>(0x7eb4288)();
        if (sdk && now) {
            entry<void (*)(uintptr_t, uint32_t, uint32_t, uint32_t)>(0x7db99ec)(sdk, now + 1, 0, 1);
            accepted = entry<uint32_t (*)(uintptr_t, const void *)>(0x7cf16fc)(manager, config);
        }
    }
    entry<void (*)(void *)>(0x7b33bf8)(config);
    free(request);
    return accepted & 1 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineHost_nativeTick(
        JNIEnv *, jobject, jlong base) {
    if (!valid(base) || !g_client) return;
    entry<void (*)()>(0x2f4b5ec)();
    uintptr_t manager = session_manager();
    if (manager) entry<void (*)(uintptr_t)>(0x7cf23ac)(manager);
}

JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineHost_nativeAlloc(JNIEnv *, jobject, jint size) {
    return size > 0 ? reinterpret_cast<jlong>(calloc(1, static_cast<size_t>(size))) : 0;
}
JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineHost_nativeFree(JNIEnv *, jobject, jlong addr) {
    free(reinterpret_cast<void *>(static_cast<uintptr_t>(addr)));
}
JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineHost_nativeWrite(
        JNIEnv *env, jobject, jlong addr, jbyteArray bytes) {
    if (addr && bytes) env->GetByteArrayRegion(bytes, 0, env->GetArrayLength(bytes), reinterpret_cast<jbyte *>(addr));
}
JNIEXPORT void JNICALL Java_com_peerlink_app_bot_EngineHost_nativeRead(
        JNIEnv *env, jobject, jlong addr, jbyteArray bytes) {
    if (addr && bytes) env->SetByteArrayRegion(bytes, 0, env->GetArrayLength(bytes), reinterpret_cast<jbyte *>(addr));
}
JNIEXPORT jlong JNICALL Java_com_peerlink_app_bot_EngineHost_nativeBuildBlock(
        JNIEnv *env, jobject, jlong base, jlong dst, jint family, jstring address, jint port) {
    if (!valid(base) || !dst || !address) return 0;
    const char *text = env->GetStringUTFChars(address, nullptr);
    if (!text) return 0;
    bool result = build_address(reinterpret_cast<void *>(static_cast<uintptr_t>(dst)), family, text, port);
    env->ReleaseStringUTFChars(address, text);
    return result ? 1 : 0;
}
}
