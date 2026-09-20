#pragma once

#include <atomic>
#include <cstdint>

// A temporary read interruption never resets permanent cancellation or a probe deadline.
struct YCoreDemuxInterrupt {
    std::atomic<bool> cancelled{false};
    std::atomic<int64_t> requested{0};
    std::atomic<int64_t> resumed{0};

    bool request(int64_t generation) {
        int64_t previous = requested.load();
        while (previous < generation && !requested.compare_exchange_weak(previous, generation)) {}
        return previous < generation;
    }

    bool pending() const { return requested.load() != resumed.load(); }

    bool resume(int64_t generation) {
        if (cancelled.load() || requested.load() != generation) return false;
        resumed.store(generation);
        return !cancelled.load() && requested.load() == generation;
    }
};
