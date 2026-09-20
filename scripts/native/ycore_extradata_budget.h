#pragma once

#include <cstddef>
#include <unordered_set>

namespace ycore_demux {

// Metadata and optional fonts have separate limits. No media packet/body size is constrained here.
class ExtradataBudget {
public:
    static constexpr size_t kMaximumBytes = 32U * 1024U * 1024U;
    static constexpr size_t kMaximumFonts = 128;

    bool allows(int track, int size, bool font) const {
        if (size <= 0 || static_cast<size_t>(size) > kMaximumBytes) return false;
        const auto& tracks = font ? font_tracks_ : codec_tracks_;
        if (tracks.count(track)) return true;
        if (font && tracks.size() >= kMaximumFonts) return false;
        const size_t used = font ? font_bytes_ : codec_bytes_;
        return static_cast<size_t>(size) <= kMaximumBytes - used;
    }

    // Commit only after the Java allocation/copy succeeds. Re-reading a track does not spend twice.
    void copied(int track, int size, bool font) {
        auto& tracks = font ? font_tracks_ : codec_tracks_;
        if (tracks.insert(track).second) {
            (font ? font_bytes_ : codec_bytes_) += static_cast<size_t>(size);
        }
    }

private:
    size_t codec_bytes_ = 0;
    size_t font_bytes_ = 0;
    std::unordered_set<int> codec_tracks_;
    std::unordered_set<int> font_tracks_;
};

}  // namespace ycore_demux
