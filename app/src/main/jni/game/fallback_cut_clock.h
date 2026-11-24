#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace peerlink {

struct CutClockSample {
    uintptr_t timer;
    float elapsed;
    float relative;
    float seek;
    float duration;
    float speed;
    float tick_rate;
};

class FallbackCutClock {
public:
    // A seek, parameter change, or interruption invalidates the tick count.
    // Wait for the next cut starting from zero before applying a correction.
    bool advance(const CutClockSample &sample, float &elapsed) {
        if (sample.seek != 0 || sample.elapsed != sample.relative || sample.elapsed < 0 ||
            !positive(sample.speed) || !positive(sample.tick_rate) || !positive(sample.duration)) {
            *this = {};
            return false;
        }
        if (sample.elapsed == 0) {
            timer_ = sample.timer;
            speed_ = sample.speed;
            tick_rate_ = sample.tick_rate;
            duration_ = sample.duration;
            elapsed_ = 0;
            ticks_ = 0;
        }
        if (!timer_ || timer_ != sample.timer || elapsed_ != sample.elapsed ||
            speed_ != sample.speed || tick_rate_ != sample.tick_rate ||
            duration_ != sample.duration || ticks_ == UINT32_MAX) {
            *this = {};
            return false;
        }
        // The loaded timeline supplies seconds as a float. Round at that
        // conversion, then normalize to 60 units per second as the engine does.
        const float seconds = static_cast<float>(double(ticks_ + 1) * speed_ / tick_rate_);
        const float next = seconds * 60.0f;
        if (!positive(next) || next < elapsed_) {
            *this = {};
            return false;
        }
        ++ticks_;
        elapsed = elapsed_ = std::min(next, duration_);
        return true;
    }

    uintptr_t timer() const { return timer_; }
    uint32_t ticks() const { return ticks_; }

private:
    static bool positive(float value) { return std::isfinite(value) && value > 0; }

    uintptr_t timer_ = 0;
    float speed_ = 0;
    float tick_rate_ = 0;
    float duration_ = 0;
    float elapsed_ = 0;
    uint32_t ticks_ = 0;
};

class FallbackCutClocks {
public:
    bool advance(const CutClockSample &sample, float &elapsed, uint32_t &ticks) {
        if (!sample.timer) return false;
        auto index = find(sample.timer);
        FallbackCutClock starting_clock;
        auto &clock = index < count_ ? entries_[index].clock : starting_clock;
        if (!clock.advance(sample, elapsed)) {
            remove(index);
            return false;
        }
        ticks = clock.ticks();
        if (elapsed == sample.duration) {
            remove(index);
            return true;
        }
        if (index == count_) {
            if (count_ < entries_.size()) {
                ++count_;
            } else {
                const auto oldest = std::min_element(entries_.begin(), entries_.end(),
                    [](const Entry &a, const Entry &b) { return a.last_use < b.last_use; });
                index = static_cast<std::size_t>(oldest - entries_.begin());
            }
            entries_[index].clock = starting_clock;
        }
        entries_[index].last_use = ++sequence_;
        return true;
    }

    void forget(uintptr_t timer) { remove(find(timer)); }

private:
    struct Entry {
        FallbackCutClock clock;
        uint64_t last_use = 0;
    };

    std::size_t find(uintptr_t timer) const {
        std::size_t index = 0;
        while (index < count_ && entries_[index].clock.timer() != timer) ++index;
        return index;
    }

    void remove(std::size_t index) {
        if (index < count_) entries_[index] = entries_[--count_];
    }

    // One native scene uses byte-sized cut indices. The original update can
    // visit several timers on the same thread. Reuse the oldest entry if
    // interrupted cuts no longer visit the update to release their slots.
    std::array<Entry, 256> entries_{};
    std::size_t count_ = 0;
    uint64_t sequence_ = 0;
};

void install_fallback_cut_clock(uintptr_t base);

} // namespace peerlink
