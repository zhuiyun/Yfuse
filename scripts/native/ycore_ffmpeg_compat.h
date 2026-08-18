#pragma once

extern "C" {
#include <libavcodec/avcodec.h>
}

// FFmpeg 8 exposes AV_PROFILE_* as the canonical names. Some compatible builds still retain the
// historical FF_PROFILE_* aliases. Keep the bridge source portable across both without changing
// its runtime ABI.
#ifndef FF_PROFILE_HEVC_MAIN_10
#define FF_PROFILE_HEVC_MAIN_10 AV_PROFILE_HEVC_MAIN_10
#endif

#ifndef FF_PROFILE_H264_HIGH_10
#define FF_PROFILE_H264_HIGH_10 AV_PROFILE_H264_HIGH_10
#endif
