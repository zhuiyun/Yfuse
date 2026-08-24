cmake_minimum_required(VERSION 3.20)
project(yfuse_harmony_native LANGUAGES C CXX)

set(YCORE_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ycore-native")

add_library(ycore SHARED
    "${YCORE_ROOT}/src/ycore.cpp"
    "${YCORE_ROOT}/src/ycore_build.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/ysecure_huks.cpp"
)
target_include_directories(ycore PUBLIC "${YCORE_ROOT}/include")
target_compile_features(ycore PUBLIC cxx_std_17)
target_compile_definitions(ycore PRIVATE YCORE_HARMONY=1 YCORE_BUILDING_LIBRARY=1)
set_target_properties(ycore PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)
target_link_libraries(ycore PRIVATE libasset_ndk.z.so)

set(YFUSE_MEDIA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/harmony-media")
option(YFUSE_ENABLE_FFMPEG "Enable bundled FFmpeg demux/codec integration" OFF)
option(YFUSE_ENABLE_LIBASS "Enable bundled libass subtitles" OFF)
option(YFUSE_ENABLE_LIBBLURAY "Enable bundled libbluray optical-disc access" OFF)
option(YFUSE_ENABLE_HARMONY_NATIVE_RENDERER "Enable verified AVCodec + NativeWindow renderer" OFF)

if(YFUSE_ENABLE_FFMPEG)
    if(NOT EXISTS "${YFUSE_MEDIA_ROOT}/include/libavformat/avformat.h")
        message(FATAL_ERROR "YFUSE_ENABLE_FFMPEG requires pinned HarmonyOS FFmpeg headers and libraries")
    endif()
    target_include_directories(ycore PRIVATE "${YFUSE_MEDIA_ROOT}/include")
    target_link_directories(ycore PRIVATE "${YFUSE_MEDIA_ROOT}/lib/${OHOS_ARCH}")
    target_link_libraries(ycore PRIVATE avformat avcodec avutil swresample swscale)
    target_compile_definitions(ycore PRIVATE YCORE_HAS_FFMPEG=1)
endif()

if(YFUSE_ENABLE_LIBASS)
    if(NOT EXISTS "${YFUSE_MEDIA_ROOT}/include/ass/ass.h")
        message(FATAL_ERROR "YFUSE_ENABLE_LIBASS requires pinned HarmonyOS libass artifacts")
    endif()
    target_include_directories(ycore PRIVATE "${YFUSE_MEDIA_ROOT}/include")
    target_link_directories(ycore PRIVATE "${YFUSE_MEDIA_ROOT}/lib/${OHOS_ARCH}")
    target_link_libraries(ycore PRIVATE ass)
    target_compile_definitions(ycore PRIVATE YCORE_HAS_LIBASS=1)
endif()

if(YFUSE_ENABLE_LIBBLURAY)
    if(NOT YFUSE_ENABLE_FFMPEG OR NOT EXISTS "${YFUSE_MEDIA_ROOT}/include/libbluray/bluray.h")
        message(FATAL_ERROR "YFUSE_ENABLE_LIBBLURAY requires FFmpeg and pinned HarmonyOS libbluray artifacts")
    endif()
    target_link_libraries(ycore PRIVATE bluray)
    target_compile_definitions(ycore PRIVATE YCORE_HAS_LIBBLURAY=1)
endif()

if(YFUSE_ENABLE_HARMONY_NATIVE_RENDERER)
    if(NOT YFUSE_ENABLE_FFMPEG)
        message(FATAL_ERROR "Native renderer requires the FFmpeg packet pipeline")
    endif()
    target_compile_definitions(ycore PRIVATE YCORE_HAS_HARMONY_AVCODEC=1 YCORE_HAS_HARMONY_NATIVE_WINDOW=1)
    target_link_libraries(ycore PRIVATE native_media_codecbase native_media_core native_window)
endif()

# Harmony-specific AVPlayer/AVCodec/NativeWindow engines are registered through the stable
# C vtable. They stay separate from the portable coordinator so host tests need no OHOS SDK.
