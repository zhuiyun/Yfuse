// These checks must also run in release builds.
#undef NDEBUG

#include "ycore_demux_seek.h"
#include <cassert>

namespace {
enum Discard { Default = 0, Nonkey = 32, All = 48 };
struct Stream { Discard discard; };
}

int main() {
    Stream video{All}, disabled_audio{All}, disabled_subtitle{Nonkey}, extra{All};
    Stream* streams[] = {&video, &disabled_audio, &disabled_subtitle, &extra};
    const std::vector<uint8_t> selected{1, 0, 0};

    // Failed seeks must not revive an exhausted stream or alter intentional discards.
    ycore_demux::restore_selected_streams_after_seek(-1, streams, 4, selected, Default);
    assert(video.discard == All);
    assert(disabled_audio.discard == All);
    assert(disabled_subtitle.discard == Nonkey);
    assert(extra.discard == All);

    ycore_demux::restore_selected_streams_after_seek(0, streams, 4, selected, Default);
    assert(video.discard == Default);
    assert(disabled_audio.discard == All);
    assert(disabled_subtitle.discard == Nonkey);
    assert(extra.discard == All);

    // Repeated EOF/seek cycles and a smaller stream array remain bounded by both inputs.
    video.discard = All;
    const std::vector<uint8_t> all_selected{1, 1, 1, 1, 1};
    ycore_demux::restore_selected_streams_after_seek(0, streams, 1, all_selected, Default);
    assert(video.discard == Default);
    assert(disabled_audio.discard == All);
    assert(disabled_subtitle.discard == Nonkey);
    assert(extra.discard == All);

    video.discard = All;
    ycore_demux::restore_selected_streams_after_seek(0, streams, 4, {}, Default);
    assert(video.discard == All);
}
