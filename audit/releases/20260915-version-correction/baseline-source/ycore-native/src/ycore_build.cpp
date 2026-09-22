#include "ycore/ycore_build.h"

#ifndef YCORE_BUILD_ID
#define YCORE_BUILD_ID "portable"
#endif

namespace {

constexpr ycore_capabilities_t compiled_capabilities() {
    ycore_capabilities_t value = YCORE_CAP_REMOTE_URL | YCORE_CAP_REQUEST_HEADERS;
#if defined(YCORE_HAS_FFMPEG)
    value |= YCORE_CAP_CUSTOM_DEMUX;
#endif
#if defined(YCORE_HAS_LIBASS)
    value |= YCORE_CAP_EXTERNAL_SUBTITLE;
#endif
#if defined(YCORE_HAS_HARMONY_AVCODEC)
    value |= YCORE_CAP_HARDWARE_VIDEO;
#endif
#if defined(YCORE_HAS_HARMONY_NATIVE_WINDOW)
    value |= YCORE_CAP_NATIVE_WINDOW;
#endif
#if defined(YCORE_HAS_LIBBLURAY) && defined(YCORE_HAS_FFMPEG)
    value |= YCORE_CAP_OPTICAL_DISC;
#endif
    return value;
}

}  // namespace

extern "C" {

int32_t ycore_get_build_info(ycore_build_info_t *out_info) {
    if (out_info == nullptr || out_info->struct_size < sizeof(*out_info) ||
        out_info->abi_version != YCORE_ABI_VERSION) {
        return YCORE_ERROR_INVALID_ARGUMENT;
    }
    out_info->compiled_capabilities = compiled_capabilities();
#if defined(YCORE_HAS_FFMPEG)
    out_info->has_ffmpeg = 1;
#else
    out_info->has_ffmpeg = 0;
#endif
#if defined(YCORE_HAS_LIBASS)
    out_info->has_libass = 1;
#else
    out_info->has_libass = 0;
#endif
#if defined(YCORE_HAS_LIBBLURAY)
    out_info->has_libbluray = 1;
#else
    out_info->has_libbluray = 0;
#endif
#if defined(YCORE_HAS_HARMONY_AVCODEC)
    out_info->has_harmony_avcodec = 1;
#else
    out_info->has_harmony_avcodec = 0;
#endif
#if defined(YCORE_HAS_HARMONY_NATIVE_WINDOW)
    out_info->has_harmony_native_window = 1;
#else
    out_info->has_harmony_native_window = 0;
#endif
    out_info->build_id = YCORE_BUILD_ID;
    return YCORE_OK;
}

ycore_capabilities_t ycore_get_compiled_capabilities(void) { return compiled_capabilities(); }

}
