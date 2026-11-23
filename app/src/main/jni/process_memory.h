#pragma once

#include <algorithm>
#include <array>
#include <cinttypes>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <string>

namespace peerlink_memory {

enum Region {
    Anonymous, ScudoPrimary, ScudoSecondary, OtherMalloc, Java, NativeBss, EngineLibrary,
    OtherLibraries, GameFiles, Apk, Stacks, Graphics, SharedMemory, OtherFiles, Count
};
constexpr const char *labels[] = {
    "anon", "scudoPrimary", "scudoSecondary", "otherMalloc", "art", "bss", "engineLib",
    "otherLibs", "gameFiles", "apk", "stacks", "graphics", "sharedMem", "otherFiles"
};
static_assert(sizeof(labels) / sizeof(*labels) == Count);

inline bool hasSuffixToken(const char *name, const char *suffix) {
    for (const char *p = strstr(name, suffix); p; p = strstr(p + 1, suffix)) {
        const char after = p[strlen(suffix)];
        if (!after || after == '\n' || after == ' ' || after == '!' || after == '.') return true;
    }
    return false;
}

inline Region region(const char *name) {
    if (strstr(name, "scudo:primary")) return ScudoPrimary;
    if (strstr(name, "scudo:secondary")) return ScudoSecondary;
    if (strstr(name, "libc_malloc") || strstr(name, "jemalloc") || !strncmp(name, "[heap]", 6)) return OtherMalloc;
    if (strstr(name, "stack_and_tls") || !strncmp(name, "[stack", 6)) return Stacks;
    if (strstr(name, ".bss]")) return NativeBss;
    if (hasSuffixToken(name, "libUE4.so")) return EngineLibrary;
    if (hasSuffixToken(name, ".so")) return OtherLibraries;
    if (strstr(name, "dalvik-") || hasSuffixToken(name, ".art") || hasSuffixToken(name, ".oat") ||
            hasSuffixToken(name, ".dex") || hasSuffixToken(name, ".vdex")) return Java;
    if (hasSuffixToken(name, ".cpk") || hasSuffixToken(name, ".pak") || hasSuffixToken(name, ".ucas") ||
            hasSuffixToken(name, ".utoc") || hasSuffixToken(name, ".decoded")) return GameFiles;
    if (hasSuffixToken(name, ".apk")) return Apk;
    if (strstr(name, "dmabuf") || strstr(name, "dma_buf") || strstr(name, "/dev/mali") ||
            strstr(name, "/dev/kgsl") || strstr(name, "/dev/dri/")) return Graphics;
    if (strstr(name, "/memfd:") || strstr(name, "/dev/ashmem") || !strncmp(name, "[anon_shmem:", 12)) return SharedMemory;
    if (!name[0] || name[0] == '[') return Anonymous;
    return OtherFiles;
}

struct Usage {
    long rss = 0, pss = 0, swapPss = 0, privateDirty = 0;

    Usage &operator+=(const Usage &other) {
        rss += other.rss;
        pss += other.pss;
        swapPss += other.swapPss;
        privateDirty += other.privateDirty;
        return *this;
    }
};

struct Mapping {
    uintptr_t start = 0, end = 0;
    Region group = Anonymous;
    Usage usage;
};

struct Sample {
    std::array<Usage, Count> groups{};
    std::unique_ptr<Mapping[]> mappings;
    size_t recordedMappings = 0;
    size_t ranges = 0;
    bool hasSwapPss = false;
    bool layoutComplete = true;
};

// Bound the retained layout even if the engine creates many more mappings.
inline constexpr size_t maxMappings = 8192;

// smaps is a single streaming read. The addresses are used only in this process.
inline bool readSmaps(FILE *file, Sample &sample) {
    sample.mappings.reset(new (std::nothrow) Mapping[maxMappings]);
    sample.layoutComplete = sample.mappings != nullptr;
    char *line = nullptr;
    size_t capacity = 0;
    Mapping current;
    size_t pssLines = 0;
    const auto finishMapping = [&] {
        if (!sample.ranges) return;
        sample.groups[current.group] += current.usage;
        if (!sample.layoutComplete) return;
        if (sample.recordedMappings == maxMappings ||
            (sample.recordedMappings && sample.mappings[sample.recordedMappings - 1].end > current.start)) {
            sample.layoutComplete = false;
            return;
        }
        sample.mappings[sample.recordedMappings++] = current;
    };
    while (getline(&line, &capacity, file) != -1) {
        const size_t first = strspn(line, "0123456789abcdefABCDEF");
        if (first && line[first] == '-') {
            finishMapping();
            current = {};
            int path = 0;
            if (sscanf(line, "%" SCNxPTR "-%" SCNxPTR " %*s %*s %*s %*s %n",
                       &current.start, &current.end, &path) != 2 || !path || current.end <= current.start) {
                free(line);
                return false;
            }
            current.group = region(line + path);
            ++sample.ranges;
        } else if (sample.ranges) {
            auto &usage = current.usage;
            if (!strncmp(line, "Rss:", 4)) usage.rss = atol(line + 4);
            else if (!strncmp(line, "Pss:", 4)) { usage.pss = atol(line + 4); ++pssLines; }
            else if (!strncmp(line, "SwapPss:", 8)) { usage.swapPss = atol(line + 8); sample.hasSwapPss = true; }
            else if (!strncmp(line, "Private_Dirty:", 14)) usage.privateDirty = atol(line + 14);
        }
    }
    finishMapping();
    free(line);
    return !ferror(file) && sample.ranges > 0 && pssLines == sample.ranges;
}

inline std::string describe(const Sample &sample) {
    std::string result = "units=KiB fields=rss,pss,swapPss,privateDirty ranges=" + std::to_string(sample.ranges);
    for (size_t i = 0; i < sample.groups.size(); ++i) {
        const auto &group = sample.groups[i];
        if (!group.rss && !group.pss && !group.swapPss && !group.privateDirty) continue;
        char values[112];
        snprintf(values, sizeof(values), " %s=%ld,%ld,%ld,%ld", labels[i], group.rss, group.pss,
            sample.hasSwapPss ? group.swapPss : -1L, group.privateDirty);
        result += values;
    }
    return result;
}

struct MappingTotals {
    Usage usage;
    uintptr_t start = 0, end = 0;
    size_t count = 0;
    Region group = Count;

    void add(const Mapping &mapping) {
        if (!count) {
            start = mapping.start;
            group = mapping.group;
        } else if (group != mapping.group) {
            group = Count;
        }
        end = mapping.end;
        usage += mapping.usage;
        ++count;
    }

    const char *label() const {
        if (!count) return "none";
        return group == Count ? "mixed" : labels[group];
    }
};

struct MappingDelta {
    long rss = 0, swapPss = 0, privateDirty = 0;
};

// Compare by overlapping address spans so a split or renamed mapping is not
// counted as a newly added address range. Matching addresses do not establish
// the allocation's identity. Paths, addresses and memory contents stay local.
inline std::string describeMappingChanges(const Sample &before, const Sample &after) {
    enum { SameRange, NewRange, RemovedRange, ChangedBounds, Kinds };
    struct TotalDelta { size_t count = 0; MappingDelta delta; };
    struct LargeChange {
        MappingDelta delta;
        const char *from = nullptr, *to = nullptr;
        uintptr_t spanKiB = 0;
        size_t beforeCount = 0, afterCount = 0;
    };
    std::array<TotalDelta, Kinds> totals{};
    std::array<LargeChange, 5> largest{};
    size_t left = 0, right = 0;
    const auto next = [](const Sample &sample, size_t index) {
        return index < sample.recordedMappings ? sample.mappings[index].start : std::numeric_limits<uintptr_t>::max();
    };
    const bool hasSwap = before.hasSwapPss && after.hasSwapPss;
    while (left < before.recordedMappings || right < after.recordedMappings) {
        MappingTotals from, to;
        const uintptr_t start = std::min(next(before, left), next(after, right));
        uintptr_t end = start + 1;
        while (next(before, left) < end || next(after, right) < end) {
            if (next(before, left) <= next(after, right)) {
                from.add(before.mappings[left++]);
                end = std::max(end, from.end);
            } else {
                to.add(after.mappings[right++]);
                end = std::max(end, to.end);
            }
        }
        size_t kind = ChangedBounds;
        if (!from.count) kind = NewRange;
        else if (!to.count) kind = RemovedRange;
        else if (from.count == 1 && to.count == 1 && from.start == to.start && from.end == to.end) kind = SameRange;
        const MappingDelta delta{to.usage.rss - from.usage.rss,
                                 to.usage.swapPss - from.usage.swapPss,
                                 to.usage.privateDirty - from.usage.privateDirty};
        auto &total = totals[kind];
        ++total.count;
        total.delta.rss += delta.rss;
        total.delta.swapPss += delta.swapPss;
        total.delta.privateDirty += delta.privateDirty;
        const long change = std::abs(delta.rss);
        if (change < 1024) continue;
        for (auto it = largest.begin(); it != largest.end(); ++it) {
            if (change <= std::abs(it->delta.rss)) continue;
            std::move_backward(it, largest.end() - 1, largest.end());
            *it = {delta, from.label(), to.label(), (end - start) / 1024, from.count, to.count};
            break;
        }
    }
    std::string result = " mapDeltaFields=ranges,rssKiB,swapPssKiB,privateDirtyKiB";
    constexpr std::array<const char *, Kinds> names = {"sameRange", "newRange", "removedRange", "changedBounds"};
    for (size_t i = 0; i < totals.size(); ++i) {
        const auto &total = totals[i];
        result += " " + std::string(names[i]) + "=" + std::to_string(total.count) + "," +
            std::to_string(total.delta.rss) + "," +
            (hasSwap ? std::to_string(total.delta.swapPss) : "unavailable") + "," +
            std::to_string(total.delta.privateDirty);
    }
    if (largest.front().from) result += " largeMapFields=groups,ranges,spanKiB,rssKiB,swapPssKiB,privateDirtyKiB";
    for (size_t i = 0; i < largest.size() && largest[i].from; ++i) {
        const auto &entry = largest[i];
        result += " map" + std::to_string(i + 1) + "=" + entry.from + ">" + entry.to + "," +
            std::to_string(entry.beforeCount) + ">" + std::to_string(entry.afterCount) + "," +
            std::to_string(entry.spanKiB) + "," + std::to_string(entry.delta.rss) + "," +
            (hasSwap ? std::to_string(entry.delta.swapPss) : "unavailable") + "," +
            std::to_string(entry.delta.privateDirty);
    }
    return result;
}
} // namespace peerlink_memory
