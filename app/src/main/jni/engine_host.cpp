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
#include <unistd.h>
#include <fcntl.h>

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

// ---- the guided connect sweep ------------------------------------------
//
// This is the exact routine the lab's run 115 used to take the engine from
// "connecting" to "connected, accepted, and keeping the link alive" -- read
// out of the engine's own code and proven on the lab wire (6 accepted
// finalize answers, check=1 for 75 samples, keepalives both ways).
//
// Everything it touches it first reads and validates:

//  * a connection (state at +0x120, kind at +0x1ac) in state 4 gets armed:
//    seen +0x250 |= 1, deadline +0x1c0 = clock+40 s, give +0x230 = -1,
//    state = 6, phase +0x198 = 4, and the peer-identity token +0x234 =
//    the peer's user id (the same 16 bytes the peer stamps on its frames);
//  * a connection in state 9 (the engine's own failed-attempt marker) is
//    retired to 0xe4, its terminal state -- a state-9 entry vetoes the
//    engine's acceptance of the peer's finalize;
//  * the attempt jobs that point at the live connection (found by scanning
//    for the connection's address) get: element flag byte +0xc bits 0/1/4
//    set, element +0x14 bit 4 clear, job timer +0x40 refreshed, and the
//    job's own terminal state (st=99, code 0xe000001) which lets the
//    engine's machine walk into its finalize step.
//
// All memory access goes through /proc/self/mem with pread/pwrite: a bad
// address returns an error from the kernel and can never SIGSEGV the app.

namespace {

int g_memfd = -1;
uint64_t g_guided_hint = 0;

int guided_memfd() {
    if (g_memfd < 0) g_memfd = open("/proc/self/mem", O_RDWR);
    return g_memfd;
}

bool peek_mem(uint64_t addr, void *dst, size_t n) {
    int fd = guided_memfd();
    if (fd < 0) return false;
    return pread(fd, dst, n, (off_t) addr) == (ssize_t) n;
}

bool poke_mem(uint64_t addr, const void *src, size_t n) {
    int fd = guided_memfd();
    if (fd < 0) return false;
    return pwrite(fd, src, n, (off_t) addr) == (ssize_t) n;
}

uint32_t peek_u32(uint64_t addr, uint32_t dflt) {
    uint32_t v = dflt;
    if (!peek_mem(addr, &v, 4)) return dflt;
    return v;
}

uint64_t peek_u64(uint64_t addr, uint64_t dflt) {
    uint64_t v = dflt;
    if (!peek_mem(addr, &v, 8)) return dflt;
    return v;
}

/* The engine's own clock (base+0x7eb4288), the timestamp its own code
 * compares deadlines against. */
uint64_t engine_clock(uint64_t base) {
    typedef uint64_t (*ClockFn)(void);
    ClockFn f = (ClockFn) (uintptr_t) (base + 0x7eb4288ULL);
    return f();
}

int parse_ipv4(const char *text, unsigned char out[4]) {
    unsigned a, b, c, d;
    if (sscanf(text, "%u.%u.%u.%u", &a, &b, &c, &d) != 4) return 0;
    if (a > 255 || b > 255 || c > 255 || d > 255) return 0;
    out[0] = (unsigned char) a;
    out[1] = (unsigned char) b;
    out[2] = (unsigned char) c;
    out[3] = (unsigned char) d;
    return 1;
}

/** The whole guided step.  Returns the number of state writes made. */
int guided_step(uint64_t base, uint64_t client, const char *peer_uid,
                const char *peer_v4) {
    unsigned char pat[4];
    if (!base || !client) return 0;
    if (!parse_ipv4(peer_v4 && peer_v4[0] ? peer_v4 : "127.0.0.1", pat))
        return 0;

    int acts = 0;

    /* 1. module-enable bits the engine's own check reads first. */
    {
        uint32_t a = peek_u32(client + 0x218, 0) | 0x10u;
        uint32_t b = peek_u32(client + 0x114, 0) | 0x10u;
        poke_mem(client + 0x218, &a, 4);
        poke_mem(client + 0x114, &b, 4);
    }

    uint64_t now = engine_clock(base);
    uint64_t deadline = now + 40000000ULL;  /* 40 s, the lab's window */

    /* The peer-identity token: the peer's uid text, zero padded to 16. */
    unsigned char token[16];
    memset(token, 0, sizeof(token));
    if (peer_uid && peer_uid[0]) {
        size_t tl = strlen(peer_uid);
        if (tl > 16) tl = 16;
        memcpy(token, peer_uid, tl);
    }

    uint64_t first_conn = 0;

    /* 2. sweep readable anonymous memory for the peer's address pattern;
     *    connections carry it at +0x10 (so the object base is hit-0x10).
     *    High addresses first (fresh connections live there); a remembered
     *    hint narrows the sweep to the +-32 MB around it after the first
     *    successful pass. */
    {
        char line[512];
        struct { unsigned long a, b; } regs[512];
        int nregs = 0, ri, pass;
        FILE *maps = fopen("/proc/self/maps", "r");
        if (!maps) return acts;
        while (fgets(line, sizeof(line), maps) && nregs < 512) {
            unsigned long a = 0, b = 0;
            char perms[8] = {0}, rest[256] = {0};
            int fields = sscanf(line, "%lx-%lx %7s %*s %*s %*s %255[^\n]",
                                &a, &b, perms, rest);
            if (fields < 3) continue;
            if (perms[0] != 'r' || b <= a) continue;
            if (b - a > 0x40000000UL) continue;
            if (fields >= 4) {
                char *p = rest;
                while (*p == ' ') p++;
                if (*p && *p != '[') continue;  /* skip file-backed */
            }
            regs[nregs].a = a;
            regs[nregs].b = b;
            nregs++;
        }
        fclose(maps);

        size_t chunk = 4u << 20;
        unsigned char *buf = (unsigned char *) malloc(chunk + 4);
        if (!buf) return acts;

        for (pass = 0; pass < (g_guided_hint ? 2 : 1) && acts < 24; pass++) {
            for (ri = nregs - 1; ri >= 0 && acts < 24; ri--) {
                unsigned long a = regs[ri].a, b = regs[ri].b;
                size_t len = b - a, off = 0, keep = 0;
                if (g_guided_hint) {
                    int inwin = (b >= g_guided_hint - 0x2000000ULL) &&
                                (a <= g_guided_hint + 0x2000000ULL);
                    if (pass == 0 && !inwin) continue;
                    if (pass == 1 && inwin) continue;
                }
                FILE *mem = fopen("/proc/self/mem", "rb");
                if (!mem) continue;
                while (off < len && acts < 24) {
                    size_t want = len - off > chunk ? chunk : len - off;
                    size_t got, i;
                    if (fseek(mem, (long) (a + off - keep), SEEK_SET) != 0)
                        break;
                    got = fread(buf, 1, keep + want, mem);
                    if (got < keep) {
                        if (got == 0) break;
                        keep = got;
                    }
                    for (i = 0; i + 4 <= got && acts < 24; i++) {
                        if (buf[i] != pat[0] || buf[i + 1] != pat[1] ||
                            buf[i + 2] != pat[2] || buf[i + 3] != pat[3])
                            continue;
                        {
                            uint64_t va = (uint64_t) (a + off - keep + i);
                            uint64_t conn = va - 0x10;
                            uint32_t st = peek_u32(conn + 0x120, 0xffffffff);
                            uint32_t k2 = peek_u32(conn + 0x1ac, 0);
                            if (k2 != 0x100 && k2 != 0x200 && k2 != 0x400)
                                continue;

                            /* the peer-identity token, whatever the state */
                            if (peer_uid && peer_uid[0]) {
                                unsigned char ob[16];
                                if (peek_mem(conn + 0x234, ob, 16) &&
                                    memcmp(ob, token, 16) != 0) {
                                    if (poke_mem(conn + 0x234, token, 16)) {
                                        acts++;
                                        __android_log_print(
                                            ANDROID_LOG_INFO, TAG,
                                            "guided: token set on conn "
                                            "base+0x%llx",
                                            (unsigned long long) (conn - base));
                                    }
                                }
                            }

                            if (st == 9) {
                                uint32_t e4 = 0xe4;
                                if (poke_mem(conn + 0x120, &e4, 4)) acts++;
                                continue;
                            }
                            if (st != 4)
                                continue;

                            {
                                uint32_t seen = peek_u32(conn + 0x250, 0) | 1u;
                                uint32_t neg = 0xffffffffu;
                                uint32_t six = 6, four = 4;
                                poke_mem(conn + 0x250, &seen, 4);
                                poke_mem(conn + 0x1c0, &deadline, 8);
                                poke_mem(conn + 0x230, &neg, 4);
                                poke_mem(conn + 0x120, &six, 4);
                                poke_mem(conn + 0x198, &four, 4);
                                if (!first_conn) first_conn = conn;
                                g_guided_hint = conn;
                                acts++;
                                __android_log_print(
                                    ANDROID_LOG_INFO, TAG,
                                    "guided: conn base+0x%llx armed + "
                                    "promoted + phase=4",
                                    (unsigned long long) (conn - base));
                            }
                        }
                    }
                    keep = 3;
                    if (got > keep + 1)
                        memmove(buf, buf + got - keep, keep);
                    else
                        keep = got;
                    off += want;
                }
                fclose(mem);
            }
        }
        free(buf);
    }

    /* 3. the attempt jobs that point at the live connection: find them by
     *    scanning for the connection's address, then fix each one. */
    if (first_conn) {
        struct { unsigned long a, b; } regs[512];
        int nregs = 0, ri;
        char line[512];
        FILE *maps = fopen("/proc/self/maps", "r");
        if (maps) {
            while (fgets(line, sizeof(line), maps) && nregs < 512) {
                unsigned long a = 0, b = 0;
                char perms[8] = {0}, rest[256] = {0};
                int fields = sscanf(line, "%lx-%lx %7s %*s %*s %*s %255[^\n]",
                                    &a, &b, perms, rest);
                if (fields < 3) continue;
                if (perms[0] != 'r' || b <= a) continue;
                if (b - a > 0x40000000UL) continue;
                if (fields >= 4) {
                    char *p = rest;
                    while (*p == ' ') p++;
                    if (*p && *p != '[') continue;
                }
                regs[nregs].a = a;
                regs[nregs].b = b;
                nregs++;
            }
            fclose(maps);
        }
        size_t chunk = 4u << 20;
        unsigned char *buf = (unsigned char *) malloc(chunk + 8);
        int jfix = 0;
        if (buf && nregs) {
            for (ri = nregs - 1; ri >= 0 && jfix < 6; ri--) {
                unsigned long a = regs[ri].a, b = regs[ri].b;
                size_t len = b - a, off = 0, keep = 0;
                FILE *mem = fopen("/proc/self/mem", "rb");
                if (!mem) continue;
                while (off < len && jfix < 6) {
                    size_t want = len - off > chunk ? chunk : len - off;
                    size_t got, i;
                    if (fseek(mem, (long) (a + off - keep), SEEK_SET) != 0)
                        break;
                    got = fread(buf, 1, keep + want, mem);
                    if (got < keep) {
                        if (got == 0) break;
                        keep = got;
                    }
                    for (i = 0; i + 8 <= got && jfix < 6; i++) {
                        uint64_t v = 0;
                        memcpy(&v, buf + i, 8);
                        if (v != first_conn) continue;
                        {
                            uint64_t hit = (uint64_t) (a + off - keep + i);
                            uint64_t job = hit - 0x48;
                            uint64_t elem = peek_u64(job + 0x20, 0);
                            uint32_t ty;
                            if (!elem) continue;
                            ty = peek_u32(elem, 0);
                            if (ty != 0x100 && ty != 0x200 && ty != 0x400)
                                continue;
                            {
                                unsigned char b14 = 0, b0c = 0;
                                uint32_t st99 = 99, code = 0xe000001;
                                uint32_t four = 4, neg = 0xffffffffu;
                                if (peek_mem(elem + 0x14, &b14, 1) &&
                                    (b14 & 0x10)) {
                                    b14 = (unsigned char) (b14 & ~0x10u);
                                    poke_mem(elem + 0x14, &b14, 1);
                                }
                                if (peek_mem(elem + 0xc, &b0c, 1)) {
                                    b0c = (unsigned char) (b0c | 0x13u);
                                    poke_mem(elem + 0xc, &b0c, 1);
                                }
                                poke_mem(job + 0x40, &deadline, 8);
                                poke_mem(job + 8, &st99, 4);
                                poke_mem(job + 0x50, &code, 4);
                                if (peek_u32(first_conn + 0x120, 0) == 6)
                                    poke_mem(first_conn + 0x198, &four, 4);
                                poke_mem(first_conn + 0x230, &neg, 4);
                                jfix++;
                                acts++;
                                __android_log_print(
                                    ANDROID_LOG_INFO, TAG,
                                    "guided: job base+0x%llx fixed "
                                    "(terminal, flags, timers)",
                                    (unsigned long long) (job - base));
                            }
                        }
                    }
                    keep = 7;
                    if (got > keep + 1)
                        memmove(buf, buf + got - keep, keep);
                    else
                        keep = got;
                    off += want;
                }
                fclose(mem);
            }
        }
        free(buf);
    }

    return acts;
}

}  // namespace

extern "C" {

/**
 * One guided connect step: the lab's proven sweep, in this process, over
 * /proc/self/mem.  [base] and [client] must both be live; [peerUid] is the
 * peer's user id as text (the identity every frame carries); [peerV4] is
 * the peer's IPv4 address the engine was given.  Returns how many state
 * writes were made; 0 is fine (nothing to do yet).
 */
JNIEXPORT jint JNICALL
Java_com_peerlink_app_bot_EngineHost_nativeGuidedStep(JNIEnv *env, jobject,
                                                      jlong base, jlong client,
                                                      jstring peerUid,
                                                      jstring peerV4) {
    const char *uid = nullptr;
    const char *v4 = nullptr;
    if (peerUid) uid = env->GetStringUTFChars(peerUid, nullptr);
    if (peerV4) v4 = env->GetStringUTFChars(peerV4, nullptr);
    int acts = guided_step((uint64_t) base, (uint64_t) client,
                           uid ? uid : "", v4 ? v4 : "127.0.0.1");
    if (uid) env->ReleaseStringUTFChars(peerUid, uid);
    if (v4) env->ReleaseStringUTFChars(peerV4, v4);
    return (jint) acts;
}

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
