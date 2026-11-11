#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace ycore_demux {

// DASH may mark its streams discarded when read-ahead reaches EOF. A successful seek must
// make selected streams readable again; never enable a track outside the user's selection.
// Keep the policy independent of FFmpeg headers so failures and selection bounds are testable.
template <typename Stream, typename Discard>
void restore_selected_streams_after_seek(
    int seek_result,
    Stream* const* streams,
    size_t stream_count,
    const std::vector<uint8_t>& selected,
    Discard default_discard) {
    if (seek_result < 0) return;
    for (size_t index = 0; index < stream_count && index < selected.size(); ++index) {
        if (selected[index]) streams[index]->discard = default_discard;
    }
}

}  // namespace ycore_demux
