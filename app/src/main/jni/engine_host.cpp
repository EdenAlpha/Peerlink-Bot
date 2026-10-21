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
#include <android/log.h>
#include <link.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <string>

#define TAG "PeerlinkEngineHost"

// The engine image, build B.  The size bounds the offsets we may call.
#define ENGINE_BUILD_B_SIZE 161937720ULL

namespace {

uint64_t g_base = 0;
void *g_handle = nullptr;

// ---- the lab's "no Java attached" activity -----------------------------
//
// The engine's own startup expects the activity the game's Java side hands
// it: an ANativeActivity whose JavaVM is the game's.  The bot is not the
// game, so that field was null, and the engine's start walked into it and
// died -- on the phone that took the whole app down the moment a room was
// made.
//
// The lab's driver solves it the way the engine's own code allows: it calls
// the engine's own activity setter (base + 0x2f4ebc0) with a REAL
// ANativeActivity whose JavaVM is a fake whose every function answers
// JNI_EDETACHED ("this thread is not attached").  The engine then takes its
// no-Java path and carries on -- proven in the lab, on this same build.
//
// The block lives in THIS library's memory, not the engine's heap: the
// engine must not be able to free what the JavaVM's function table is in.

volatile unsigned long g_jni_calls = 0;
bool g_no_java_ready = false;

// Page-aligned so it can never share a page with engine data.
unsigned long g_jni_block[0x800] __attribute__((aligned(4096)));

long jni_stub(void) {
    unsigned long n = ++g_jni_calls;
    if (n <= 8) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "jni stub call %lu -> JNI_EDETACHED", n);
    }
    return -2;  // JNI_EDETACHED
}

/** Decode an "adrp x8, <page>" instruction into the page it names. */
bool adrp_target(uintptr_t pc, uint32_t word, uintptr_t *result) {
    if ((word & 0x9f00001fu) != 0x90000008u) return false;
    long immlo = (long) ((word >> 29) & 3);
    long immhi = (long) ((word >> 5) & 0x7ffff);
    long imm = (immhi << 2) | immlo;
    if (imm & (1L << 20)) imm -= (1L << 21);
    *result = (pc & ~(uintptr_t) 0xfff) + ((uintptr_t) imm << 12);
    return true;
}

/**
 * Find the global the engine's own startup stores the activity in, from the
 * instructions of the engine's own setter:
 *
 *     adrp x8, <page> ; str x0, [x8, #0x10] ; ret
 *
 * The lab's own check, so a different build is refused instead of poked.
 */
bool activity_global(uintptr_t base, uintptr_t *global) {
    uintptr_t setter = base + 0x2f4ebc0u;
    uint32_t w0 = ((const uint32_t *) setter)[0];
    uint32_t w1 = ((const uint32_t *) setter)[1];
    uint32_t w2 = ((const uint32_t *) setter)[2];
    if (w1 != 0xf9000900u || w2 != 0xd65f03c0u) return false;
    if (!adrp_target(setter, w0, global)) return false;
    *global += 0x10;
    return true;
}

/** Whether [addr] falls in a writable mapping of this process. */
bool writable_at(uintptr_t addr) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[512];
    bool ok = false;
    while (fgets(line, sizeof(line), f)) {
        unsigned long lo = 0, hi = 0;
        char perms[8] = {0};
        if (sscanf(line, "%lx-%lx %7s", &lo, &hi, perms) != 3) continue;
        if (addr >= (uintptr_t) lo && addr < (uintptr_t) hi) {
            ok = (perms[1] == 'w');
            break;
        }
    }
    fclose(f);
    return ok;
}

/** Install the fake "no Java attached" activity, exactly like the lab. */
void install_no_java_activity(uintptr_t base) {
    uintptr_t global = 0;
    if (!activity_global(base, &global)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "the activity setter at base+0x2f4ebc0 is not the "
                            "expected code; refusing to install the no-Java "
                            "activity");
        return;
    }
    if (!writable_at(global)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "the activity global 0x%llx is not writable; "
                            "refusing to install the no-Java activity",
                            (unsigned long long) global);
        return;
    }
    unsigned long *act = g_jni_block;         // ANativeActivity: vm at +8
    unsigned long *vm = g_jni_block + 0x100;  // JavaVM:           vtable at +0
    unsigned long *vt = g_jni_block + 0x200;  // every slot is the stub
    for (int i = 0; i < 16; i++) {
        vt[i] = (unsigned long) (uintptr_t) jni_stub;
    }
    vm[0] = (unsigned long) (uintptr_t) vt;
    act[1] = (unsigned long) (uintptr_t) vm;
    ((void (*)(void *)) (uintptr_t) (base + 0x2f4ebc0u))((void *) act);
    g_no_java_ready =
        (*(unsigned long *) global == (unsigned long) (uintptr_t) act);
    __android_log_print(g_no_java_ready ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
                        TAG,
                        "no-Java activity: act=0x%llx vm=0x%llx vt=0x%llx "
                        "stub=0x%llx global=0x%llx -> %s",
                        (unsigned long long) (uintptr_t) act,
                        (unsigned long long) (uintptr_t) vm,
                        (unsigned long long) (uintptr_t) vt,
                        (unsigned long long) (uintptr_t) jni_stub,
                        (unsigned long long) global,
                        g_no_java_ready ? "taken" : "NOT taken");
}

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
    // directory.  Loading that first, by its absolute path, means the
    // engine's own dependency resolves when it is loaded.
    std::string full(p);
    std::string dir = full;
    size_t slash = dir.find_last_of('/');
    if (slash != std::string::npos) dir = dir.substr(0, slash);
    if (!dir.empty()) {
        std::string dep = dir + "/libgadsme.so";
        dlopen(dep.c_str(), RTLD_NOW | RTLD_GLOBAL);
    }
    void *h = dlopen(p, RTLD_NOW | RTLD_GLOBAL);
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
    if (g_base) install_no_java_activity((uintptr_t) g_base);
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "engine loaded, base=0x%llx, no_java=%d",
                        (unsigned long long) g_base, g_no_java_ready ? 1 : 0);
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

/** Whether the engine has the lab's no-Java activity installed. */
JNIEXPORT jboolean JNICALL
Java_com_peerlink_app_bot_EngineHost_nativeNoJavaActivity(JNIEnv *, jobject) {
    return g_no_java_ready ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
