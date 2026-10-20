// engine_probe -- first stone of the bridge.
//
// Goal: prove, on the phone, that the bot can reach the game's own engine
// (libUE4.so) and load it.  Everything downstream depends on that one answer.
//
// It runs as the shell-uid helper (uid 2000) that PeerLink already has, so it
// can read the game's private directories.
//
// What it does, in order:
//   1. locate the installed game's extracted native library directory
//   2. report the engine's path and size
//   3. hash it                        -> pins the exact build of the engine
//   4. copy it into the bot's storage -> the bot owns a copy from now on
//   5. fork a child and try to LOAD it there
//      (isolated on purpose: the engine runs ~11,900 registration functions
//       the moment it is opened, and if one of them is unhappy we want the
//       signal, not a dead probe)
//   6. report the load result, the load base address, and a byte of real code
//
// Output is line-oriented `KEY=value` so the bot can parse it.
//
// Usage: engine_probe [--out <dir>] [--no-load] [--keep]

#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <dirent.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

namespace {

constexpr const char *kGamePackage = "jp.konami.pesam";
constexpr const char *kEngineName = "libUE4.so";
constexpr const char *kAdLibName = "libgadsme.so";

// 161,937,720 bytes in the build we hold.  We do not assume it; we report what
// we find.  This is only used to sanity-check that we found a real engine.
constexpr uint64_t kPlausibleEngineMin = 100ULL * 1024ULL * 1024ULL;

void say(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    putchar('\n');
    fflush(stdout);
}

std::string dirName(const std::string &path) {
    const size_t slash = path.find_last_of('/');
    return slash == std::string::npos ? std::string() : path.substr(0, slash);
}

bool fileExists(const std::string &path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

uint64_t fileSize(const std::string &path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) return 0;
    return static_cast<uint64_t>(st.st_size);
}

// --------------------------------------------------------------------- sha256
struct Sha256 {
    uint32_t h[8];
    uint64_t len = 0;
    uint8_t buf[64];
    size_t bufLen = 0;

    static uint32_t rotr(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

    void reset() {
        static const uint32_t init[8] = {0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
                                         0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u};
        memcpy(h, init, sizeof(h));
        len = 0;
        bufLen = 0;
    }

    void block(const uint8_t *p) {
        static const uint32_t k[64] = {
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u,
            0x923f82a4u, 0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
            0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u,
            0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u,
            0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
            0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu,
            0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au,
            0x5b9cca4fu, 0x682e6ff3u, 0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
            0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u};
        uint32_t w[64];
        for (int i = 0; i < 16; ++i) {
            w[i] = (uint32_t(p[i * 4]) << 24) | (uint32_t(p[i * 4 + 1]) << 16) |
                   (uint32_t(p[i * 4 + 2]) << 8) | uint32_t(p[i * 4 + 3]);
        }
        for (int i = 16; i < 64; ++i) {
            const uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }
        uint32_t a = h[0], b = h[1], c = h[2], d = h[3];
        uint32_t e = h[4], f = h[5], g = h[6], hh = h[7];
        for (int i = 0; i < 64; ++i) {
            const uint32_t S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            const uint32_t ch = (e & f) ^ (~e & g);
            const uint32_t t1 = hh + S1 + ch + k[i] + w[i];
            const uint32_t S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            const uint32_t mj = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t t2 = S0 + mj;
            hh = g; g = f; f = e; e = d + t1;
            d = c; c = b; b = a; a = t1 + t2;
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d;
        h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
    }

    void update(const uint8_t *p, size_t n) {
        len += n;
        while (n > 0) {
            const size_t take = (n < 64 - bufLen) ? n : (64 - bufLen);
            memcpy(buf + bufLen, p, take);
            bufLen += take;
            p += take;
            n -= take;
            if (bufLen == 64) {
                block(buf);
                bufLen = 0;
            }
        }
    }

    void finish(uint8_t out[32]) {
        const uint64_t bits = len * 8;
        uint8_t pad = 0x80;
        update(&pad, 1);
        pad = 0;
        while (bufLen != 56) update(&pad, 1);
        uint8_t tail[8];
        for (int i = 0; i < 8; ++i) tail[i] = uint8_t(bits >> (56 - i * 8));
        update(tail, 8);
        for (int i = 0; i < 8; ++i) {
            out[i * 4] = uint8_t(h[i] >> 24);
            out[i * 4 + 1] = uint8_t(h[i] >> 16);
            out[i * 4 + 2] = uint8_t(h[i] >> 8);
            out[i * 4 + 3] = uint8_t(h[i]);
        }
    }
};

bool sha256File(const std::string &path, std::string &hexOut, uint64_t &sizeOut) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    Sha256 s;
    s.reset();
    std::vector<uint8_t> chunk(1 << 20);
    uint64_t total = 0;
    while (true) {
        const ssize_t got = read(fd, chunk.data(), chunk.size());
        if (got < 0) { close(fd); return false; }
        if (got == 0) break;
        s.update(chunk.data(), size_t(got));
        total += uint64_t(got);
    }
    close(fd);
    uint8_t digest[32];
    s.finish(digest);
    static const char *hexd = "0123456789abcdef";
    hexOut.clear();
    hexOut.reserve(64);
    for (int i = 0; i < 32; ++i) {
        hexOut.push_back(hexd[digest[i] >> 4]);
        hexOut.push_back(hexd[digest[i] & 0xf]);
    }
    sizeOut = total;
    return true;
}

// ------------------------------------------------------------------ locating
bool runCapture(const char *cmd, std::string &out) {
    FILE *f = popen(cmd, "r");
    if (f == nullptr) return false;
    char line[4096];
    out.clear();
    while (fgets(line, sizeof(line), f) != nullptr) out += line;
    pclose(f);
    return true;
}

// `pm path <pkg>` prints one `package:<path>` line per split.
std::vector<std::string> packagePaths(const char *pkg) {
    std::vector<std::string> paths;
    std::string raw;
    std::string cmd = "pm path ";
    cmd += pkg;
    if (!runCapture(cmd.c_str(), raw)) return paths;
    size_t pos = 0;
    while (pos < raw.size()) {
        size_t eol = raw.find('\n', pos);
        if (eol == std::string::npos) eol = raw.size();
        std::string line = raw.substr(pos, eol - pos);
        pos = eol + 1;
        while (!line.empty() && (line.back() == '\r' || line.back() == ' ')) line.pop_back();
        const std::string prefix = "package:";
        if (line.compare(0, prefix.size(), prefix) == 0) paths.push_back(line.substr(prefix.size()));
    }
    return paths;
}

bool existsDir(const std::string &path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0 && S_ISDIR(st.st_mode);
}

std::vector<std::string> listDir(const std::string &path) {
    std::vector<std::string> names;
    DIR *d = opendir(path.c_str());
    if (d == nullptr) return names;
    while (struct dirent *e = readdir(d)) {
        const char *n = e->d_name;
        if (strcmp(n, ".") == 0 || strcmp(n, "..") == 0) continue;
        names.push_back(n);
    }
    closedir(d);
    return names;
}

/**
 * Fallback for when `pm path` cannot be used: walk the install tree by hand.
 *
 * NOT glob(3) -- bionic does not provide it, which is exactly how the first
 * build of this file failed.  The installed layout is
 *   /data/app/~~<random>==/<package>-<random>==/lib/arm64/libUE4.so
 * so the package directory is matched by name prefix and the library is then
 * looked for at the fixed relative path beneath it.
 */
std::string scanInstallTree() {
    const size_t prefixLen = strlen(kGamePackage);
    for (const std::string &top : listDir("/data/app")) {
        const std::string topPath = "/data/app/" + top;
        if (!existsDir(topPath)) continue;
        for (const std::string &pkgDir : listDir(topPath)) {
            if (pkgDir.size() < prefixLen) continue;
            if (pkgDir.compare(0, prefixLen, kGamePackage) != 0) continue;
            const std::string libDir = topPath + "/" + pkgDir + "/lib/arm64";
            if (fileExists(libDir + "/" + kEngineName)) return libDir;
        }
    }
    return std::string();
}

std::string findLibDir() {
    // Preferred: derive from the package's own installed location.
    const std::vector<std::string> paths = packagePaths(kGamePackage);
    for (const std::string &p : paths) {
        const std::string dir = dirName(p);
        if (dir.empty()) continue;
        const std::string candidate = dir + "/lib/arm64";
        if (fileExists(candidate + "/" + kEngineName)) return candidate;
    }
    // Fallback: walk the install tree directly.
    return scanInstallTree();
}

// ------------------------------------------------------------------- copying
bool copyFile(const std::string &src, const std::string &dst) {
    const int in = open(src.c_str(), O_RDONLY | O_CLOEXEC);
    if (in < 0) return false;
    const int out = open(dst.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (out < 0) { close(in); return false; }
    std::vector<uint8_t> chunk(1 << 20);
    bool ok = true;
    while (true) {
        const ssize_t got = read(in, chunk.data(), chunk.size());
        if (got < 0) { ok = false; break; }
        if (got == 0) break;
        ssize_t written = 0;
        while (written < got) {
            const ssize_t w = write(out, chunk.data() + written, size_t(got - written));
            if (w <= 0) { ok = false; break; }
            written += w;
        }
        if (!ok) break;
    }
    close(in);
    close(out);
    return ok;
}

// ------------------------------------------------------------------ elf check
// Read a symbol's st_value straight out of the .so, so the load base can be
// computed even though we do not know which build this is.
// The section-header table lives at the END of the file and the symbol tables
// near the START, so each piece is read from its own offset.
bool readAt(int fd, uint64_t off, void *dst, size_t n) {
    uint8_t *p = static_cast<uint8_t *>(dst);
    size_t done = 0;
    while (done < n) {
        const ssize_t got = pread(fd, p + done, n - done, off_t(off + done));
        if (got <= 0) return false;
        done += size_t(got);
    }
    return true;
}

bool elfSymbolValue(const std::string &path, const char *want, uint64_t &valueOut) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size < int64_t(sizeof(Elf64_Ehdr))) {
        close(fd);
        return false;
    }

    Elf64_Ehdr eh {};
    if (!readAt(fd, 0, &eh, sizeof(eh))) { close(fd); return false; }
    if (memcmp(eh.e_ident, ELFMAG, SELFMAG) != 0) { close(fd); return false; }
    if (eh.e_ident[EI_CLASS] != ELFCLASS64) { close(fd); return false; }
    const size_t shnum = eh.e_shnum;
    const size_t shentsize = eh.e_shentsize;
    if (shnum == 0 || shentsize != sizeof(Elf64_Shdr)) { close(fd); return false; }

    std::vector<uint8_t> shBuf(shnum * shentsize);
    if (!readAt(fd, eh.e_shoff, shBuf.data(), shBuf.size())) { close(fd); return false; }
    const Elf64_Shdr *sh = reinterpret_cast<const Elf64_Shdr *>(shBuf.data());

    const Elf64_Shdr *dynsym = nullptr;
    for (size_t i = 0; i < shnum; ++i) {
        if (sh[i].sh_type == SHT_DYNSYM) { dynsym = &sh[i]; break; }
    }
    if (dynsym == nullptr || dynsym->sh_link >= shnum) { close(fd); return false; }
    const Elf64_Shdr &dynstr = sh[dynsym->sh_link];

    std::vector<char> strtab(size_t(dynstr.sh_size) + 1);
    if (!readAt(fd, dynstr.sh_offset, strtab.data(), size_t(dynstr.sh_size))) {
        close(fd);
        return false;
    }
    strtab[size_t(dynstr.sh_size)] = '\0';

    const size_t count = size_t(dynsym->sh_size) / sizeof(Elf64_Sym);
    std::vector<Elf64_Sym> syms(count);
    if (count > 0 && !readAt(fd, dynsym->sh_offset, syms.data(), count * sizeof(Elf64_Sym))) {
        close(fd);
        return false;
    }
    close(fd);

    for (size_t i = 0; i < count; ++i) {
        if (syms[i].st_name == 0 || syms[i].st_name >= dynstr.sh_size) continue;
        if (strcmp(strtab.data() + syms[i].st_name, want) == 0) {
            valueOut = syms[i].st_value;
            return true;
        }
    }
    return false;
}

// ------------------------------------------------------------- the load test
// Runs in the forked child.  Nothing here may assume it survives.

void *tryOpen(const char *label, const std::string &path) {
    void *h = dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    say("CHILD_TRY_%s=%s", label, h ? "OK" : "failed");
    if (h == nullptr) {
        const char *e = dlerror();
        say("CHILD_TRY_%s_ERROR=%s", label, e ? e : "(none)");
    }
    return h;
}

/**
 * Copy a file into an anonymous, in-memory file and hand back the descriptor.
 *
 * Android refuses to dlopen a library that lives inside an app's data
 * directory -- which is exactly where the app-level route has to stage its
 * copy, and is very likely how that route will fail.  /proc/self/fd/<n> is
 * not a data directory, so the very same bytes pass the check.  This is the
 * documented workaround, not a trick.
 */
int makeMemfdCopy(const std::string &src) {
    const long fdRaw = syscall(__NR_memfd_create, "libUE4.so", (unsigned int) MFD_CLOEXEC);
    if (fdRaw < 0) return -1;
    const int fd = int(fdRaw);
    const int in = open(src.c_str(), O_RDONLY | O_CLOEXEC);
    if (in < 0) {
        close(fd);
        return -1;
    }
    std::vector<uint8_t> chunk(1 << 20);
    while (true) {
        const ssize_t got = read(in, chunk.data(), chunk.size());
        if (got < 0) { close(in); close(fd); return -1; }
        if (got == 0) break;
        ssize_t off = 0;
        while (off < got) {
            const ssize_t w = write(fd, chunk.data() + off, size_t(got - off));
            if (w <= 0) { close(in); close(fd); return -1; }
            off += w;
        }
    }
    close(in);
    return fd;
}

void childTryLoad(const std::vector<std::string> &enginePaths, const std::string &libDir) {
    setvbuf(stdout, nullptr, _IOLBF, 0);

    // Sibling libraries first, by their own path: the engine demands
    // libgadsme.so and the loader has no reason to search this directory.
    const std::string adLib = libDir + "/" + kAdLibName;
    if (fileExists(adLib)) {
        void *sibling = dlopen(adLib.c_str(), RTLD_NOW | RTLD_GLOBAL);
        say("CHILD_SIBLING=%s", sibling ? "loaded" : "failed");
        if (sibling == nullptr) {
            const char *e = dlerror();
            say("CHILD_SIBLING_ERROR=%s", e ? e : "(none)");
        }
    } else {
        say("CHILD_SIBLING=absent");
    }

    void *handle = nullptr;
    size_t opened = 0;
    for (size_t i = 0; i < enginePaths.size() && handle == nullptr; ++i) {
        char label[16];
        snprintf(label, sizeof(label), "P%u", unsigned(i + 1));
        handle = tryOpen(label, enginePaths[i]);
        if (handle != nullptr) opened = i;
    }

    // Last resort: the same bytes, but through an anonymous in-memory file.
    // Used when every path failed, which is the expected shape of the
    // app-level route (its staging directory is inside the app's data dir).
    if (handle == nullptr && enginePaths.size() > 1) {
        const int fd = makeMemfdCopy(enginePaths.back());
        if (fd < 0) {
            say("CHILD_MEMFD=unavailable");
        } else {
            char proc[64];
            snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd);
            handle = tryOpen("MFD", proc);
            if (handle != nullptr) opened = enginePaths.size() - 1;
            // Deliberately leaked on success: the mapping must stay valid for
            // as long as the library is loaded.
        }
    }

    say("CHILD_LOAD=%s", handle ? "OK" : "FAILED");
    if (handle == nullptr) {
        say("CHILD_DONE=0");
        _exit(3);
    }
    say("CHILD_OPENED_INDEX=%u", unsigned(opened));

    // Recover the load base: resolved address minus the symbol's own value.
    uint64_t symValue = 0;
    void *resolved = dlsym(handle, "JNI_OnLoad");
    if (resolved != nullptr && elfSymbolValue(enginePaths[opened], "JNI_OnLoad", symValue)) {
        const uint64_t base = reinterpret_cast<uint64_t>(resolved) - symValue;
        say("CHILD_BASE=0x%llx", static_cast<unsigned long long>(base));
        // Read a few bytes of the engine's own code through the base, to show
        // that real instructions are mapped and readable.
        const uint8_t *code = reinterpret_cast<const uint8_t *>(resolved);
        say("CHILD_JNI_ONLOAD_CODE=%02x%02x%02x%02x %02x%02x%02x%02x",
            unsigned(code[0]), unsigned(code[1]), unsigned(code[2]), unsigned(code[3]),
            unsigned(code[4]), unsigned(code[5]), unsigned(code[6]), unsigned(code[7]));
    } else {
        say("CHILD_BASE=unknown");
    }
    say("CHILD_DONE=1");
    _exit(0);
}

}  // namespace

int main(int argc, char **argv) {
    std::string outDir = "/data/local/tmp/peerlink_engine";
    bool doLoad = true;
    bool hashOnly = false;

    for (int i = 1; i < argc; ++i) {
        const char *a = argv[i];
        if (strcmp(a, "--out") == 0 && i + 1 < argc) {
            outDir = argv[++i];
        } else if (strcmp(a, "--no-load") == 0) {
            doLoad = false;
        } else if (strcmp(a, "--hash-only") == 0) {
            // Find the engine and fingerprint it, and stop.  No copy, no
            // child, no load.  This is the mode that is safe to run while the
            // phone is busy -- it reads 161 MB in 1 MB chunks and writes
            // nothing, so it costs no meaningful memory and cannot disturb a
            // match in progress.  The load test is the heavy half and is only
            // ever run on purpose.
            hashOnly = true;
        } else if (strcmp(a, "--keep") == 0) {
            // copy always happens; flag kept for symmetry with later stages
        } else if (strcmp(a, "--help") == 0) {
            say("engine_probe [--out <dir>] [--no-load] [--hash-only]");
            return 0;
        }
    }

    say("PROBE=engine_probe");
    say("UID=%d", static_cast<int>(getuid()));

    const std::string libDir = findLibDir();
    if (libDir.empty()) {
        say("LIB_DIR=");
        say("RESULT=no_game_library_found");
        return 2;
    }
    say("LIB_DIR=%s", libDir.c_str());

    const std::string engineRom = libDir + "/" + kEngineName;
    if (!fileExists(engineRom)) {
        say("UE4_ROM_PATH=%s", engineRom.c_str());
        say("RESULT=engine_file_missing");
        return 2;
    }
    const uint64_t romSize = fileSize(engineRom);
    say("UE4_ROM_PATH=%s", engineRom.c_str());
    say("UE4_ROM_SIZE=%llu", static_cast<unsigned long long>(romSize));
    say("PLAUSIBLE=%s", romSize >= kPlausibleEngineMin ? "yes" : "no");

    std::string hash;
    uint64_t hashed = 0;
    if (sha256File(engineRom, hash, hashed)) {
        say("UE4_SHA256=%s", hash.c_str());
        say("UE4_HASHED_BYTES=%llu", static_cast<unsigned long long>(hashed));
    } else {
        say("UE4_SHA256=unreadable");
    }

    const std::string adRom = libDir + "/" + kAdLibName;
    say("GADSME_PATH=%s", fileExists(adRom) ? adRom.c_str() : "(absent)");

    if (hashOnly) {
        // Everything this mode was asked for has been reported. Stop here --
        // before the copy, before the child, before anything heavy.
        say("RESULT=engine_identified");
        return 0;
    }

    // ---- take a copy the bot owns -----------------------------------------
    // The bot app loads this staged engine in its own process, so the
    // directory and the file have to be readable by the app: readable and
    // searchable, not writable.
    mkdir(outDir.c_str(), 0755);
    chmod(outDir.c_str(), 0755);
    say("STAGE_DIR=%s", outDir.c_str());
    const std::string staged = outDir + "/" + kEngineName;
    if (fileExists(staged) && fileSize(staged) == romSize) {
        say("STAGE_UE4=already_present");
    } else if (copyFile(engineRom, staged)) {
        say("STAGE_UE4=copied");
    } else {
        say("STAGE_UE4=copy_failed");
    }
    const std::string adStage = outDir + "/" + kAdLibName;
    if (fileExists(adRom)) {
        if (fileExists(adStage) && fileSize(adStage) == fileSize(adRom)) {
            say("STAGE_GADSME=already_present");
        } else {
            say("STAGE_GADSME=%s", copyFile(adRom, adStage) ? "copied" : "copy_failed");
        }
        // The app loads the engine with this directory as its dependency
        // search path, so the ad library beside it has to be readable too.
        chmod(adStage.c_str(), 0644);
    }
    chmod(staged.c_str(), 0644);

    if (!doLoad) {
        say("RESULT=stopped_before_load");
        return 0;
    }

    // ---- the load test, isolated ------------------------------------------
    const pid_t pid = fork();
    if (pid < 0) {
        say("RESULT=fork_failed");
        return 4;
    }
    if (pid == 0) {
        // Two candidates, tried in order:
        //   1. the installed copy -- the exact bytes the real game loads
        //   2. the staged copy   -- always readable by us, and a location the
        //                           shell process is allowed to execute from
        std::vector<std::string> candidates;
        candidates.push_back(engineRom);
        if (staged != engineRom) candidates.push_back(staged);
        childTryLoad(candidates, libDir);
        _exit(1);
    }
    say("LOAD_CHILD_PID=%d", static_cast<int>(pid));
    int status = 0;
    // The engine's registration pass is heavy, but the caller is a shell
    // command whose own cap is 120 s, and the staging above already spent
    // part of it.  Cap the load well inside that: a hang must never be
    // reported as silence.
    const int waited = [] (pid_t p, int *st) {
        for (int i = 0; i < 450; ++i) {
            const pid_t r = waitpid(p, st, WNOHANG);
            if (r == p) return 0;
            if (r < 0) return -1;
            usleep(100000);
        }
        kill(p, 9);
        waitpid(p, st, 0);
        return -2;
    }(pid, &status);

    if (waited == -2) {
        say("LOAD_TIMEOUT=1");
        say("RESULT=load_hung_then_killed");
        return 0;
    }
    if (waited < 0) {
        say("RESULT=wait_failed");
        return 4;
    }
    if (WIFSIGNALED(status)) {
        say("LOAD_SIGNAL=%d", WTERMSIG(status));
        say("RESULT=engine_crashed_on_load");
        return 0;
    }
    say("LOAD_EXIT=%d", WEXITSTATUS(status));
    say("RESULT=%s", WEXITSTATUS(status) == 0 ? "load_ok" : "load_failed");
    return 0;
}
