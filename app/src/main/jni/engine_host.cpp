// engine_host.cpp -- call the engine's own entry points, from the app.
//
// The engine (libUE4.so) exports almost nothing, so the way in is the load
// base plus a fixed offset.  Every offset used here was pinned down by
// reading the engine's own code (build B, sha256 2cea0c85...330659); the
// list and the reasons behind it are in docs/ENGINE_DRIVEN_P2P.md.
//
// This is the same trick the lab driver uses on the PC: find the load base,
// add the offset, call it.  Nothing about the engine is re-implemented here
// - the engine's own code runs.
//
// A call is only allowed to land inside the engine's image, so a wrong
// offset fails loudly instead of jumping into the weeds.

#include <jni.h>
#include <dlfcn.h>
#include <android/dlext.h>
#include <android/log.h>
#include <link.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <string>

#define TAG "PeerlinkEngineHost"

// The engine image, build B.  The size bounds the offsets we may call.
#define ENGINE_BUILD_B_SIZE 161937720ULL

namespace {

uint64_t g_base = 0;
void *g_handle = nullptr;

}  // namespace

extern "C" {

/**
 * Load the engine and remember its load base.
 *
 * @param path the staged copy of the game's libUE4.so that the bot owns
 * @return the load base, or 0 when the engine could not be loaded
 */
JNIEXPORT jlong JNICALL
Java_com_peerlink_app_bot_EngineHost_nativeLoad(JNIEnv *env, jobject,
                                                jstring path) {
    if (g_base) return (jlong) g_base;
    const char *p = env->GetStringUTFChars(path, nullptr);
    if (!p) return 0;
    // The engine's ad-library dependency sits beside it in the staged
    // directory, so that directory is the dependency search path.
    std::string full(p);
    std::string dir = full;
    size_t slash = dir.find_last_of('/');
    if (slash != std::string::npos) dir = dir.substr(0, slash);
    void *h = android_dlopen_ext(p, RTLD_NOW | RTLD_GLOBAL,
                                  dir.empty() ? nullptr : dir.c_str());
    if (!h) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlopen failed: %s",
                            dlerror());
        env->ReleaseStringUTFChars(path, p);
        return 0;
    }
    // The load base is the base of the mapping the engine landed in.
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (dladdr(h, &info) && info.dli_fbase) {
        g_base = (uint64_t) (uintptr_t) info.dli_fbase;
    } else {
        // Fall back to a symbol the engine always has.
        void *sym = dlsym(h, "JNI_OnLoad");
        if (sym && dladdr(sym, &info) && info.dli_fbase) {
            g_base = (uint64_t) (uintptr_t) info.dli_fbase;
        }
    }
    g_handle = h;
    __android_log_print(ANDROID_LOG_INFO, TAG, "engine loaded, base=0x%llx",
                        (unsigned long long) g_base);
    env->ReleaseStringUTFChars(path, p);
    return (jlong) g_base;
}

/**
 * Call one of the engine's own functions: base + offset, with up to three
 * integer arguments, returning its result.
 */
JNIEXPORT jlong JNICALL
Java_com_peerlink_app_bot_EngineHost_nativeCall(JNIEnv *env, jobject,
                                                jlong base, jlong offset,
                                                jlong a0, jlong a1,
                                                jlong a2, jlong a3) {
    if (!base) return 0;
    uint64_t off = (uint64_t) offset;
    if (off >= ENGINE_BUILD_B_SIZE) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "offset 0x%llx is outside the engine",
                            (unsigned long long) off);
        return 0;
    }
    void *fn = (void *) (uintptr_t) ((uint64_t) base + off);
    typedef long (*Fn4)(long, long, long, long);
    Fn4 f = (Fn4) fn;
    long r = f((long) a0, (long) a1, (long) a2, (long) a3);
    return (jlong) r;
}

/** The engine's load base, or 0 when it is not loaded. */
JNIEXPORT jlong JNICALL
Java_com_peerlink_app_bot_EngineHost_nativeBase(JNIEnv *env, jobject) {
    return (jlong) g_base;
}

/**
 * Allocate a buffer for the engine's own structures (the peer record).
 * Plain app memory: the engine reads it and copies what it needs.
 */
JNIEXPORT jlong JNICALL
Java_com_peerlink_app_bot_EngineHost_nativeAlloc(JNIEnv *env, jobject,
                                                 jint size) {
    if (size <= 0) return 0;
    void *p = malloc((size_t) size);
    if (p) memset(p, 0, (size_t) size);
    return (jlong) (uintptr_t) p;
}

/** Copy bytes into a buffer the engine will read. */
JNIEXPORT void JNICALL
Java_com_peerlink_app_bot_EngineHost_nativeWrite(JNIEnv *env, jobject,
                                                 jlong addr,
                                                 jbyteArray bytes) {
    if (!addr || !bytes) return;
    jsize n = env->GetArrayLength(bytes);
    if (n <= 0) return;
    jbyte *b = env->GetByteArrayElements(bytes, nullptr);
    if (!b) return;
    memcpy((void *) (uintptr_t) addr, b, (size_t) n);
    env->ReleaseByteArrayElements(bytes, b, JNI_ABORT);
}

/** Release a buffer made by nativeAlloc. */
JNIEXPORT void JNICALL
Java_com_peerlink_app_bot_EngineHost_nativeFree(JNIEnv *env, jobject,
                                                jlong addr) {
    if (addr) free((void *) (uintptr_t) addr);
}

/**
 * Build one address block with the ENGINE's own builder (0x7e04330):
 * family, destination, address string, port.  The engine writes the
 * 0x80-byte block, so the block the peer record carries is the engine's
 * own shape, not a copy of it.
 */
JNIEXPORT jlong JNICALL
Java_com_peerlink_app_bot_EngineHost_nativeBuildBlock(JNIEnv *env, jobject,
                                                      jlong base,
                                                      jlong dst,
                                                      jint family,
                                                      jstring address,
                                                      jint port) {
    if (!base || !dst) return 0;
    const char *a = env->GetStringUTFChars(address, nullptr);
    if (!a) return 0;
    void *fn = (void *) (uintptr_t) ((uint64_t) base + 0x7e04330ULL);
    typedef long (*Fn)(long, void *, const char *, long);
    long r = ((Fn) fn)((long) family, (void *) (uintptr_t) dst, a, (long) port);
    env->ReleaseStringUTFChars(address, a);
    return (jlong) r;
}

/** Read bytes back out of a buffer the engine wrote. */
JNIEXPORT void JNICALL
Java_com_peerlink_app_bot_EngineHost_nativeRead(JNIEnv *env, jobject,
                                                jlong addr,
                                                jbyteArray bytes) {
    if (!addr || !bytes) return;
    jsize n = env->GetArrayLength(bytes);
    if (n <= 0) return;
    jbyte *b = env->GetByteArrayElements(bytes, nullptr);
    if (!b) return;
    memcpy(b, (void *) (uintptr_t) addr, (size_t) n);
    env->ReleaseByteArrayElements(bytes, b, 0);
}

}  // extern "C"
