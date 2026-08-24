#ifndef YFUSE_YCORE_BUILD_H
#define YFUSE_YCORE_BUILD_H

#include "ycore/ycore.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ycore_build_info {
    uint32_t struct_size;
    uint32_t abi_version;
    ycore_capabilities_t compiled_capabilities;
    int32_t has_ffmpeg;
    int32_t has_libass;
    int32_t has_libbluray;
    int32_t has_harmony_avcodec;
    int32_t has_harmony_native_window;
    const char *build_id;
} ycore_build_info_t;

YCORE_API int32_t ycore_get_build_info(ycore_build_info_t *out_info);
YCORE_API ycore_capabilities_t ycore_get_compiled_capabilities(void);

#ifdef __cplusplus
}
#endif

#endif
