#ifndef YFUSE_YCORE_H
#define YFUSE_YCORE_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(YCORE_BUILDING_LIBRARY)
#define YCORE_API __declspec(dllexport)
#else
#define YCORE_API __declspec(dllimport)
#endif
#elif defined(__GNUC__) || defined(__clang__)
#define YCORE_API __attribute__((visibility("default")))
#else
#define YCORE_API
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define YCORE_ABI_VERSION 1u
#define YCORE_ENGINE_NAME_MAX 48u
#define YCORE_DIAGNOSTIC_TEXT_MAX 96u

typedef struct ycore_session ycore_session_t;

typedef enum ycore_result {
    YCORE_OK = 0,
    YCORE_ERROR_INVALID_ARGUMENT = -1,
    YCORE_ERROR_NO_ENGINE = -2,
    YCORE_ERROR_ENGINE_OPEN = -3,
    YCORE_ERROR_NOT_READY = -4,
    YCORE_ERROR_UNSUPPORTED = -5,
    YCORE_ERROR_INTERNAL = -6
} ycore_result_t;

typedef enum ycore_phase {
    YCORE_PHASE_IDLE = 0,
    YCORE_PHASE_PREPARING = 1,
    YCORE_PHASE_READY = 2,
    YCORE_PHASE_ENDED = 3,
    YCORE_PHASE_FAILED = 4
} ycore_phase_t;

typedef enum ycore_route {
    YCORE_ROUTE_SYSTEM = 0,
    YCORE_ROUTE_NATIVE_TUNNEL = 1,
    YCORE_ROUTE_NATIVE_DIRECT = 2,
    YCORE_ROUTE_NATIVE_ENHANCED = 3,
    YCORE_ROUTE_GPU_ENHANCED = 4,
    YCORE_ROUTE_SOFTWARE_FALLBACK = 5
} ycore_route_t;

typedef enum ycore_failure_category {
    YCORE_FAILURE_NONE = 0,
    YCORE_FAILURE_AUTHORIZATION = 1,
    YCORE_FAILURE_DRM = 2,
    YCORE_FAILURE_NETWORK = 3,
    YCORE_FAILURE_CONTAINER = 4,
    YCORE_FAILURE_DECODER = 5,
    YCORE_FAILURE_RENDERER = 6,
    YCORE_FAILURE_AUDIO_SINK = 7,
    YCORE_FAILURE_UNKNOWN = 8
} ycore_failure_category_t;

typedef enum ycore_track_type {
    YCORE_TRACK_AUDIO = 0,
    YCORE_TRACK_SUBTITLE = 1
} ycore_track_type_t;

typedef uint64_t ycore_capabilities_t;

enum {
    YCORE_CAP_REMOTE_URL = 1ull << 0,
    YCORE_CAP_REQUEST_HEADERS = 1ull << 1,
    YCORE_CAP_HARDWARE_VIDEO = 1ull << 2,
    YCORE_CAP_CUSTOM_DEMUX = 1ull << 3,
    YCORE_CAP_EXTERNAL_SUBTITLE = 1ull << 4,
    YCORE_CAP_BITMAP_SUBTITLE = 1ull << 5,
    YCORE_CAP_HDR_OUTPUT = 1ull << 6,
    YCORE_CAP_DOLBY_VISION_OUTPUT = 1ull << 7,
    YCORE_CAP_ENCODED_AUDIO_OUTPUT = 1ull << 8,
    YCORE_CAP_OPTICAL_DISC = 1ull << 9,
    YCORE_CAP_DISC_MENU = 1ull << 10,
    YCORE_CAP_DRM = 1ull << 11,
    YCORE_CAP_NATIVE_WINDOW = 1ull << 12
};

typedef struct ycore_media_request {
    uint32_t struct_size;
    uint32_t abi_version;
    const char *media_id;
    const char *uri;
    const char *mime_type;
    const char *headers_json;
    const char *provider_key;
    int64_t start_position_ms;
    int32_t auto_play;
    ycore_capabilities_t requirements;
} ycore_media_request_t;

typedef struct ycore_state {
    uint32_t struct_size;
    uint32_t abi_version;
    ycore_phase_t phase;
    ycore_route_t route;
    ycore_failure_category_t failure_category;
    int32_t playing;
    int32_t playback_requested;
    int32_t buffering;
    int64_t position_ms;
    int64_t duration_ms;
    int64_t buffered_position_ms;
    float speed;
    int32_t video_output_verified;
    int32_t audio_output_verified;
    int32_t dolby_vision_output_verified;
    int32_t dolby_atmos_output_verified;
    int64_t av_sync_offset_ms;
    char engine[YCORE_ENGINE_NAME_MAX];
    char decoder[YCORE_DIAGNOSTIC_TEXT_MAX];
    char renderer[YCORE_DIAGNOSTIC_TEXT_MAX];
    char reason[YCORE_DIAGNOSTIC_TEXT_MAX];
} ycore_state_t;

typedef struct ycore_engine_vtable {
    uint32_t struct_size;
    uint32_t abi_version;
    int32_t (*open)(void *context, const ycore_media_request_t *request);
    int32_t (*play)(void *context);
    int32_t (*pause)(void *context);
    int32_t (*seek_to)(void *context, int64_t position_ms);
    int32_t (*set_speed)(void *context, float speed);
    int32_t (*select_track)(void *context, ycore_track_type_t type, const char *track_id);
    int32_t (*set_video_output)(void *context, void *native_output);
    int32_t (*retry)(void *context);
    int32_t (*poll_state)(void *context, ycore_state_t *state);
    void (*close)(void *context);
} ycore_engine_vtable_t;

typedef struct ycore_engine_registration {
    uint32_t struct_size;
    uint32_t abi_version;
    const char *name;
    ycore_route_t route;
    int32_t priority;
    ycore_capabilities_t capabilities;
    void *context;
    ycore_engine_vtable_t vtable;
} ycore_engine_registration_t;

typedef void (*ycore_state_listener_t)(const ycore_state_t *state, void *user_data);

YCORE_API ycore_session_t *ycore_session_create(void);
YCORE_API void ycore_session_destroy(ycore_session_t *session);

YCORE_API int32_t ycore_session_register_engine(
    ycore_session_t *session,
    const ycore_engine_registration_t *registration);

YCORE_API int32_t ycore_session_set_listener(
    ycore_session_t *session,
    ycore_state_listener_t listener,
    void *user_data);

YCORE_API int32_t ycore_session_open(ycore_session_t *session, const ycore_media_request_t *request);
YCORE_API int32_t ycore_session_play(ycore_session_t *session);
YCORE_API int32_t ycore_session_pause(ycore_session_t *session);
YCORE_API int32_t ycore_session_seek_to(ycore_session_t *session, int64_t position_ms);
YCORE_API int32_t ycore_session_set_speed(ycore_session_t *session, float speed);
YCORE_API int32_t ycore_session_select_track(
    ycore_session_t *session,
    ycore_track_type_t type,
    const char *track_id);
YCORE_API int32_t ycore_session_set_video_output(ycore_session_t *session, void *native_output);
YCORE_API int32_t ycore_session_retry(ycore_session_t *session);

/* Polls the backend, publishes state, and performs an eligible automatic handover. */
YCORE_API int32_t ycore_session_tick(ycore_session_t *session);

/* Explicit user/diagnostic handover to the next compatible backend. */
YCORE_API int32_t ycore_session_handover(ycore_session_t *session);

YCORE_API int32_t ycore_session_get_state(ycore_session_t *session, ycore_state_t *out_state);

/* FFI-friendly convenience entry points for languages that cannot model fixed-size C arrays. */
YCORE_API int32_t ycore_session_open_values(
    ycore_session_t *session,
    const char *media_id,
    const char *uri,
    const char *mime_type,
    const char *headers_json,
    const char *provider_key,
    int64_t start_position_ms,
    int32_t auto_play,
    ycore_capabilities_t requirements);
YCORE_API int32_t ycore_session_state_phase(ycore_session_t *session);
YCORE_API int32_t ycore_session_state_route(ycore_session_t *session);
YCORE_API int32_t ycore_session_state_playing(ycore_session_t *session);
YCORE_API int32_t ycore_session_state_playback_requested(ycore_session_t *session);
YCORE_API int32_t ycore_session_state_buffering(ycore_session_t *session);
YCORE_API int64_t ycore_session_state_position_ms(ycore_session_t *session);
YCORE_API int64_t ycore_session_state_duration_ms(ycore_session_t *session);
YCORE_API int64_t ycore_session_state_buffered_position_ms(ycore_session_t *session);
YCORE_API float ycore_session_state_speed(ycore_session_t *session);
YCORE_API const char *ycore_session_state_engine(ycore_session_t *session);
YCORE_API const char *ycore_session_state_reason(ycore_session_t *session);

#ifdef __cplusplus
}
#endif

#endif
