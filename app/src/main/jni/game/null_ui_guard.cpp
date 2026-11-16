// Experimental pinned eFootball 11.1.1 client with the original NullDrv backend.
// Install before NativeActivity startup. This does not modify game flow,
// native packet handling, simulation state, or input handling.
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>
namespace {
using Address = uintptr_t;
using Init = void (*)(Address);
constexpr Address kFactoryCall = 0x496aa4c;
constexpr Address kNullVtable = 0x93b6e98;
constexpr Address kNullInit = 0x4965e78;
constexpr uint32_t kPlatformFactoryCall = 0x97fff40e;
constexpr uint32_t kOriginalNullFactoryCall = 0x97ffebfd;
Address engine = 0;
Init original_init = nullptr;
std::atomic<bool> applied{false};
std::atomic<bool> failed{false};
std::atomic<bool> installed{false};
Address pointer(Address offset) { return *reinterpret_cast<Address *>(engine + offset); }
bool protect(Address address, int flags) {
    long size = sysconf(_SC_PAGESIZE);
    if (size < 4096 || size > 65536 || (size & (size - 1))) return false;
    return mprotect(reinterpret_cast<void *>(address & ~(static_cast<Address>(size) - 1)),
                    static_cast<size_t>(size), flags) == 0;
}
bool set_slot(Address value) {
    auto *slot = reinterpret_cast<Address *>(engine + kNullVtable + 16);
    Address previous = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    if (!protect(reinterpret_cast<Address>(slot), PROT_READ | PROT_WRITE)) return false;
    __atomic_store_n(slot, value, __ATOMIC_RELEASE);
    if (protect(reinterpret_cast<Address>(slot), PROT_READ)) return true;
    __atomic_store_n(slot, previous, __ATOMIC_RELEASE);
    protect(reinterpret_cast<Address>(slot), PROT_READ);
    return false;
}
bool set_factory(uint32_t instruction) {
    auto *code = reinterpret_cast<uint32_t *>(engine + kFactoryCall);
    uint32_t previous = __atomic_load_n(code, __ATOMIC_ACQUIRE);
    if (!protect(reinterpret_cast<Address>(code), PROT_READ | PROT_WRITE)) return false;
    __atomic_store_n(code, instruction, __ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char *>(code), reinterpret_cast<char *>(code + 1));
    if (protect(reinterpret_cast<Address>(code), PROT_READ | PROT_EXEC)) return true;
    __atomic_store_n(code, previous, __ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char *>(code), reinterpret_cast<char *>(code + 1));
    protect(reinterpret_cast<Address>(code), PROT_READ | PROT_EXEC);
    return false;
}
bool set_code(Address offset, const uint8_t *bytes, size_t length) {
    Address address = engine + offset;
    uint8_t previous[16];
    if (!length || length > sizeof(previous)) return false;
    memcpy(previous, reinterpret_cast<void *>(address), length);
    if (!protect(address, PROT_READ | PROT_WRITE)) return false;
    memcpy(reinterpret_cast<void *>(address), bytes, length);
    __builtin___clear_cache(reinterpret_cast<char *>(address), reinterpret_cast<char *>(address + length));
    if (protect(address, PROT_READ | PROT_EXEC)) return true;
    memcpy(reinterpret_cast<void *>(address), previous, length);
    __builtin___clear_cache(reinterpret_cast<char *>(address), reinterpret_cast<char *>(address + length));
    protect(address, PROT_READ | PROT_EXEC);
    return false;
}
bool install_graphics_guards() {
    static constexpr uint8_t factory_original[] = {
        0xfd,0x7b,0xbe,0xa9,0xf3,0x0b,0x00,0xf9,0xfd,0x03,0x00,0x91,0x00,0x2c,0x80,0x52
    };
    static constexpr uint8_t original[] = {0x88,0x02,0x40,0xf9};
    static constexpr uint8_t trampoline[] = {
        0x54,0x00,0x00,0xb5,0x38,0x30,0x2b,0x14,0x88,0x02,0x40,0xf9,0x2c,0x30,0x2b,0x14
    };
    static constexpr uint8_t branch[] = {0xd2,0xcf,0xd4,0x17};
    static constexpr uint8_t hud_wait[] = {0x00,0x02,0x00,0x36};
    static constexpr uint8_t nop[] = {0x1f,0x20,0x03,0xd5};
    if (memcmp(reinterpret_cast<void *>(engine + 0x4965a40), factory_original, sizeof(factory_original)) ||
        memcmp(reinterpret_cast<void *>(engine + 0x5431af8), original, sizeof(original)) ||
        memcmp(reinterpret_cast<void *>(engine + 0x2fc27f4), hud_wait, sizeof(hud_wait))) return false;
    // The original factory has already returned. Its first four instructions
    // are now a trampoline: if the optional texture resource is missing, skip
    // only its graphics-dimension calls; otherwise execute the original load.
    if (!set_code(0x4965a40, trampoline, sizeof(trampoline))) return false;
    if (!set_code(0x5431af8, branch, sizeof(branch))) {
        set_code(0x4965a40, factory_original, sizeof(factory_original));
        return false;
    }
    // The original actor only reaches this check when its match HUD is active.
    // Its callee checks that each HUD widget finished loading; a headless bot
    // has no HUD to load. Continue through the original actor's own event and
    // task updates; never modify the match, players or network verdict here.
    if (set_code(0x2fc27f4, nop, sizeof(nop))) return true;
    set_code(0x5431af8, original, sizeof(original));
    set_code(0x4965a40, factory_original, sizeof(factory_original));
    return false;
}
void initialize_null_for_android(Address instance) {
    Init call = original_init;
    if (!call) return;
    bool selected = instance && pointer(0xa0111f8) == instance &&
        *reinterpret_cast<Address *>(instance) == engine + kNullVtable;
    bool normal_client = (*reinterpret_cast<uint8_t *>(engine + 0x9a95a70) & 1) &&
        *reinterpret_cast<uint8_t *>(engine + 0x9a95a68) == 0;
    Address platform = pointer(0x99e4e30);
    Address feature = pointer(0x99e50e0);
    Address mapping = pointer(0x99e50e8);
    Address null_flag = pointer(0x99e4e50);
    bool globals_ready = platform && feature && mapping && null_flag;
    bool restored = set_slot(reinterpret_cast<Address>(call));
    if (!selected || !normal_client || !globals_ready || !restored) {
        __android_log_print(ANDROID_LOG_ERROR, "PeerlinkEngineHost",
            "NULL_CLIENT_PROFILE rejected selected=%d client=%d globals=%d restored=%d",
            selected, normal_client, globals_ready, restored);
        call(instance);
        failed.store(true, std::memory_order_release);
        return;
    }
    // Values read from the working, original Android ES3.1 renderer.
    // NullDrv's constructor otherwise selects its desktop shader defaults.
    *reinterpret_cast<uint32_t *>(platform) = 24; // SP_OPENGL_ES3_1_ANDROID
    *reinterpret_cast<uint32_t *>(feature) = 1;  // ERHIFeatureLevel::ES3_1
    uint32_t *table = reinterpret_cast<uint32_t *>(mapping);
    table[0] = 49; table[1] = 24; table[2] = 49; table[3] = 49;
    call(instance); // The original NullDrv initializer fills its shader mapping.
    bool profile_matches = *reinterpret_cast<uint32_t *>(platform) == 24 &&
        *reinterpret_cast<uint32_t *>(feature) == 1 && table[1] == 24;
    if (profile_matches && install_graphics_guards()) {
        // Keep normal client/UI logic enabled. Actual rendering still uses
        // the verified original NullDrv vtable.
        *reinterpret_cast<uint8_t *>(null_flag) = 0;
        applied.store(true, std::memory_order_release);
    } else {
        *reinterpret_cast<uint8_t *>(null_flag) = 1;
        failed.store(true, std::memory_order_release);
    }
    __android_log_print(applied.load() ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        "PeerlinkEngineHost", "NULL_CLIENT_PROFILE originalInit=1 featureLevel=1 shaderPlatform=24 applied=%d",
        applied.load());
}
}
extern "C" bool peerlink_install_null_client_rhi(uintptr_t base) {
    if (!base) return false;
    if (engine) return engine == base && installed.load(std::memory_order_acquire);
    auto *factory = reinterpret_cast<const uint32_t *>(base + kFactoryCall);
    auto *slot = reinterpret_cast<const Address *>(base + kNullVtable + 16);
    if (*factory != kPlatformFactoryCall || *slot != base + kNullInit) return false;
    engine = base;
    original_init = reinterpret_cast<Init>(base + kNullInit);
    if (!set_slot(reinterpret_cast<Address>(&initialize_null_for_android))) {
        engine = 0; original_init = nullptr; return false;
    }
    if (set_factory(kOriginalNullFactoryCall)) {
        installed.store(true, std::memory_order_release);
        return true;
    }
    set_slot(reinterpret_cast<Address>(original_init));
    engine = 0; original_init = nullptr;
    return false;
}
extern "C" bool peerlink_null_client_rhi_ready(uintptr_t base) {
    if (base != engine || !applied.load(std::memory_order_acquire)) return false;
    Address instance = pointer(0xa0111f8);
    Address null_flag = pointer(0x99e4e50);
    Address feature = pointer(0x99e50e0);
    Address platform = pointer(0x99e4e30);
    if (!null_flag || !feature || !platform) return false;
    return instance && *reinterpret_cast<Address *>(instance) == engine + kNullVtable &&
        *reinterpret_cast<uint8_t *>(null_flag) == 0 &&
        *reinterpret_cast<uint32_t *>(feature) == 1 &&
        *reinterpret_cast<uint32_t *>(platform) == 24;
}
extern "C" bool peerlink_null_client_rhi_failed(uintptr_t base) {
    return base == engine && failed.load(std::memory_order_acquire);
}
