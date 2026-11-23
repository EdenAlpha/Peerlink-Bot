// Experimental pinned eFootball 11.1.1 client with the original NullDrv backend.
// Install before NativeActivity startup. This does not modify game flow,
// native packet handling, simulation state, or input handling.
#include <android/log.h>
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <unistd.h>
#include "executable_patch.h"
#include "fallback_cut_clock.h"
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
bool skip_missing_mesh_wait = false;
std::mutex error_lock;
std::string last_error = "none";
void record_error(const std::string &detail) {
    { std::lock_guard<std::mutex> lock(error_lock); last_error = detail; }
    __android_log_print(ANDROID_LOG_ERROR, "PeerlinkEngineHost", "HEADLESS_SETUP %s", detail.c_str());
}
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
    if (!protect(reinterpret_cast<Address>(slot), PROT_READ | PROT_WRITE)) {
        record_error("operation=initializer_write errno=" + std::to_string(errno));
        return false;
    }
    __atomic_store_n(slot, value, __ATOMIC_RELEASE);
    if (protect(reinterpret_cast<Address>(slot), PROT_READ)) return true;
    const int error = errno;
    __atomic_store_n(slot, previous, __ATOMIC_RELEASE);
    protect(reinterpret_cast<Address>(slot), PROT_READ);
    record_error("operation=initializer_restore errno=" + std::to_string(error));
    return false;
}
bool set_code(Address offset, const uint8_t *bytes, size_t length) {
    peerlink::CodePatchFailure failure;
    if (peerlink::replace_executable_bytes(engine + offset, bytes, length, &failure)) return true;
    char detail[256];
    snprintf(detail, sizeof(detail), "operation=%s offset=0x%lx errno=%d pageSize=%zu memory=anonymous_rx",
             failure.operation, static_cast<unsigned long>(offset), failure.error, failure.page_size);
    record_error(detail);
    return false;
}
bool set_factory(uint32_t instruction) {
    return set_code(kFactoryCall, reinterpret_cast<const uint8_t *>(&instruction), sizeof(instruction));
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
    static constexpr uint8_t visual_cave_original[] = {
        0x60,0xd8,0x0a,0x95,0xf3,0x03,0x00,0xaa,0xd2,0x00,0x00,0x94,0xe0,0x03,0x13,0xaa
    };
    static constexpr uint8_t visual_call_original[] = {0xe9,0x01,0x00,0x94};
    static constexpr uint8_t visual_resource_load[] = {0x08,0x80,0x4c,0xf9};
    static constexpr uint8_t visual_cave[] = {
        0x08,0x80,0x4c,0xf9, // ldr x8, [x0, #0x1900]
        0x48,0x00,0x00,0xb4, // cbz x8, return to the original caller
        0x52,0xa6,0xc1,0x17, // b to the original visual-resource function
        0xc0,0x03,0x5f,0xd6  // ret
    };
    static constexpr uint8_t visual_call[] = {0x95,0x5b,0x3e,0x94};
    static constexpr uint8_t mesh_wait_original[] = {0x69,0x00,0x00,0x34};
    static constexpr uint8_t mesh_wait_skip[] = {0x03,0x00,0x00,0x14};
    static constexpr uint8_t texture_bind_original[] = {0xff,0x43,0x02,0xd1};
    static constexpr uint8_t texture_bind_branch[] = {0x3a,0x7b,0xa8,0x17};
    static constexpr uint8_t texture_bind_cave_original[] = {
        0xf3,0x0b,0x40,0xf9,0xfd,0x7b,0xc2,0xa8,0xc0,0x03,0x5f,0xd6
    };
    static constexpr uint8_t texture_bind_cave[] = {
        0xe2,0xff,0xff,0xb4, // cbz x2, return at 0x4965a5c
        0xff,0x43,0x02,0xd1, // original sub sp, sp, #0x90
        0xc5,0x84,0x57,0x14  // b original shader binder + 4
    };
    if (memcmp(reinterpret_cast<void *>(engine + 0x4965a40), factory_original, sizeof(factory_original)) ||
        memcmp(reinterpret_cast<void *>(engine + 0x5431af8), original, sizeof(original)) ||
        memcmp(reinterpret_cast<void *>(engine + 0x2fc27f4), hud_wait, sizeof(hud_wait)) ||
        memcmp(reinterpret_cast<void *>(engine + 0x4965a50), visual_cave_original, sizeof(visual_cave_original)) ||
        memcmp(reinterpret_cast<void *>(engine + 0x39cebfc), visual_call_original, sizeof(visual_call_original)) ||
        memcmp(reinterpret_cast<void *>(engine + 0x39cf3dc), visual_resource_load, sizeof(visual_resource_load)) ||
        memcmp(reinterpret_cast<void *>(engine + 0x4965a60), texture_bind_cave_original, sizeof(texture_bind_cave_original)) ||
        memcmp(reinterpret_cast<void *>(engine + 0x5f46d78), texture_bind_original, sizeof(texture_bind_original)) ||
        (skip_missing_mesh_wait && memcmp(reinterpret_cast<void *>(engine + 0x39d06f4), mesh_wait_original, sizeof(mesh_wait_original)))) {
        record_error("operation=graphics_guard_verify result=unexpected_instructions");
        return false;
    }
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
    if (!set_code(0x2fc27f4, nop, sizeof(nop))) {
        set_code(0x5431af8, original, sizeof(original));
        set_code(0x4965a40, factory_original, sizeof(factory_original));
        return false;
    }
    // This call loads a visual resource and updates color material values. A
    // no-pack run crashed because its resource at x0+0x1900 was absent. Skip
    // only this call in that case; a present resource takes the original path.
    // The original factory already returned and its entry is the trampoline
    // above, so its following four instructions are available as this cave.
    if (!set_code(0x4965a50, visual_cave, sizeof(visual_cave))) {
        set_code(0x2fc27f4, hud_wait, sizeof(hud_wait));
        set_code(0x5431af8, original, sizeof(original));
        set_code(0x4965a40, factory_original, sizeof(factory_original));
        return false;
    }
    if (set_code(0x39cebfc, visual_call, sizeof(visual_call))) {
        // The original readiness function waits for an asynchronous SkeletalMesh
        // callback. With NullDrv and no extra packs, that visual callback never
        // arrives. Skip only this mesh wait; the function still checks its
        // other dependencies and the game itself advances the player state.
        if (!skip_missing_mesh_wait || set_code(0x39d06f4, mesh_wait_skip, sizeof(mesh_wait_skip))) {
            // The observed RenderThread crash at 5f46dd4 dereferences the
            // missing texture x2+0x28. With no texture to bind on NullDrv,
            // return before allocating the stack. Present textures replay
            // the exact prologue and take the original binding path.
            // This uses only the factory's unused 12-byte epilogue: the
            // next function at 4965a6c remains untouched.
            if (set_code(0x4965a60, texture_bind_cave, sizeof(texture_bind_cave))) {
                if (set_code(0x5f46d78, texture_bind_branch, sizeof(texture_bind_branch))) return true;
                set_code(0x4965a60, texture_bind_cave_original, sizeof(texture_bind_cave_original));
            }
            if (skip_missing_mesh_wait) set_code(0x39d06f4, mesh_wait_original, sizeof(mesh_wait_original));
        }
        set_code(0x39cebfc, visual_call_original, sizeof(visual_call_original));
    }
    set_code(0x4965a50, visual_cave_original, sizeof(visual_cave_original));
    set_code(0x2fc27f4, hud_wait, sizeof(hud_wait));
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
        record_error("operation=initializer_verify selected=" + std::to_string(selected) +
                     " client=" + std::to_string(normal_client) + " globals=" +
                     std::to_string(globals_ready) + " restored=" + std::to_string(restored));
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
        if (skip_missing_mesh_wait) peerlink::install_fallback_cut_clock(engine);
        applied.store(true, std::memory_order_release);
    } else {
        if (!profile_matches) record_error("operation=android_profile_verify result=mismatch");
        *reinterpret_cast<uint8_t *>(null_flag) = 1;
        failed.store(true, std::memory_order_release);
    }
    __android_log_print(applied.load() ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
        "PeerlinkEngineHost", "NULL_CLIENT_PROFILE originalInit=1 featureLevel=1 shaderPlatform=24 applied=%d meshWaitBypass=%d missingTextureGuard=%d",
        applied.load(), applied.load() && skip_missing_mesh_wait, applied.load());
}
}
extern "C" bool peerlink_install_null_client_rhi(uintptr_t base, bool missing_match_resources) {
    if (!base) { record_error("operation=engine_base result=missing"); return false; }
    if (engine) return engine == base && skip_missing_mesh_wait == missing_match_resources && installed.load(std::memory_order_acquire);
    auto *factory = reinterpret_cast<const uint32_t *>(base + kFactoryCall);
    auto *slot = reinterpret_cast<const Address *>(base + kNullVtable + 16);
    if (*factory != kPlatformFactoryCall || *slot != base + kNullInit) {
        char detail[256];
        snprintf(detail, sizeof(detail), "operation=startup_verify factory=0x%x expectedFactory=0x%x initializerMatches=%d",
                 *factory, kPlatformFactoryCall, *slot == base + kNullInit);
        record_error(detail);
        return false;
    }
    engine = base;
    skip_missing_mesh_wait = missing_match_resources;
    original_init = reinterpret_cast<Init>(base + kNullInit);
    if (!set_slot(reinterpret_cast<Address>(&initialize_null_for_android))) {
        engine = 0; original_init = nullptr; skip_missing_mesh_wait = false; return false;
    }
    if (set_factory(kOriginalNullFactoryCall)) {
        installed.store(true, std::memory_order_release);
        __android_log_print(ANDROID_LOG_INFO, "PeerlinkEngineHost", "HEADLESS_SETUP installed=1 memory=anonymous_rx pageSize=%ld", sysconf(_SC_PAGESIZE));
        return true;
    }
    set_slot(reinterpret_cast<Address>(original_init));
    engine = 0; original_init = nullptr; skip_missing_mesh_wait = false;
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
extern "C" const char *peerlink_null_client_rhi_diagnostics() {
    thread_local std::string snapshot;
    std::lock_guard<std::mutex> lock(error_lock);
    snapshot = "installed=" + std::to_string(installed.load()) + " applied=" +
        std::to_string(applied.load()) + " failed=" + std::to_string(failed.load()) +
        " pageSize=" + std::to_string(sysconf(_SC_PAGESIZE)) + " memory=anonymous_rx " + last_error;
    return snapshot.c_str();
}
