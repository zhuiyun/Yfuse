#include "ycore/ycore.h"
#include "ycore/ycore_build.h"

#include <cassert>
#include <cstring>
#include <string>

namespace {

struct FakeEngine {
    int open_result = YCORE_OK;
    int open_count = 0;
    int close_count = 0;
    int play_count = 0;
    int pause_count = 0;
    int track_count = 0;
    int output_count = 0;
    int64_t opened_position = -1;
    float speed = 1.0f;
    ycore_state_t state{};

    FakeEngine() {
        state.struct_size = sizeof(state);
        state.abi_version = YCORE_ABI_VERSION;
        state.phase = YCORE_PHASE_READY;
        state.speed = 1.0f;
    }
};

int32_t fake_open(void *context, const ycore_media_request_t *request) {
    auto *fake = static_cast<FakeEngine *>(context);
    ++fake->open_count;
    fake->opened_position = request->start_position_ms;
    fake->state.position_ms = request->start_position_ms;
    fake->state.playback_requested = request->auto_play;
    fake->state.playing = request->auto_play;
    return fake->open_result;
}

int32_t fake_play(void *context) {
    auto *fake = static_cast<FakeEngine *>(context);
    ++fake->play_count;
    fake->state.playback_requested = 1;
    fake->state.playing = 1;
    return YCORE_OK;
}

int32_t fake_pause(void *context) {
    auto *fake = static_cast<FakeEngine *>(context);
    ++fake->pause_count;
    fake->state.playback_requested = 0;
    fake->state.playing = 0;
    return YCORE_OK;
}

int32_t fake_seek(void *context, int64_t position) {
    static_cast<FakeEngine *>(context)->state.position_ms = position;
    return YCORE_OK;
}

int32_t fake_speed(void *context, float speed) {
    auto *fake = static_cast<FakeEngine *>(context);
    fake->speed = speed;
    fake->state.speed = speed;
    return YCORE_OK;
}

int32_t fake_track(void *context, ycore_track_type_t, const char *) {
    ++static_cast<FakeEngine *>(context)->track_count;
    return YCORE_OK;
}
int32_t fake_output(void *context, void *) {
    ++static_cast<FakeEngine *>(context)->output_count;
    return YCORE_OK;
}
int32_t fake_retry(void *) { return YCORE_ERROR_UNSUPPORTED; }

int32_t fake_poll(void *context, ycore_state_t *state) {
    *state = static_cast<FakeEngine *>(context)->state;
    return YCORE_OK;
}

void fake_close(void *context) { ++static_cast<FakeEngine *>(context)->close_count; }

ycore_engine_registration_t registration(
    const char *name,
    ycore_route_t route,
    int priority,
    ycore_capabilities_t capabilities,
    FakeEngine *engine) {
    ycore_engine_registration_t value{};
    value.struct_size = sizeof(value);
    value.abi_version = YCORE_ABI_VERSION;
    value.name = name;
    value.route = route;
    value.priority = priority;
    value.capabilities = capabilities;
    value.context = engine;
    value.vtable.struct_size = sizeof(value.vtable);
    value.vtable.abi_version = YCORE_ABI_VERSION;
    value.vtable.open = fake_open;
    value.vtable.play = fake_play;
    value.vtable.pause = fake_pause;
    value.vtable.seek_to = fake_seek;
    value.vtable.set_speed = fake_speed;
    value.vtable.select_track = fake_track;
    value.vtable.set_video_output = fake_output;
    value.vtable.retry = fake_retry;
    value.vtable.poll_state = fake_poll;
    value.vtable.close = fake_close;
    return value;
}

ycore_media_request_t request(ycore_capabilities_t requirements = 0) {
    ycore_media_request_t value{};
    value.struct_size = sizeof(value);
    value.abi_version = YCORE_ABI_VERSION;
    value.media_id = "movie-1";
    value.uri = "https://example.invalid/Videos/movie-1/stream";
    value.mime_type = "video/x-matroska";
    value.headers_json = "{\"X-Emby-Token\":\"redacted\"}";
    value.provider_key = "emby";
    value.start_position_ms = 42'000;
    value.auto_play = 1;
    value.requirements = requirements;
    return value;
}

void test_priority_and_capability_routing() {
    auto *session = ycore_session_create();
    FakeEngine system;
    FakeEngine native;
    const auto system_registration = registration(
        "HarmonySystem", YCORE_ROUTE_SYSTEM, 100,
        YCORE_CAP_REMOTE_URL | YCORE_CAP_HARDWARE_VIDEO, &system);
    const auto native_registration = registration(
        "HarmonyNative", YCORE_ROUTE_NATIVE_ENHANCED, 50,
        YCORE_CAP_REMOTE_URL | YCORE_CAP_REQUEST_HEADERS | YCORE_CAP_HARDWARE_VIDEO |
            YCORE_CAP_CUSTOM_DEMUX | YCORE_CAP_NATIVE_WINDOW,
        &native);
    assert(ycore_session_register_engine(session, &system_registration) == YCORE_OK);
    assert(ycore_session_register_engine(session, &native_registration) == YCORE_OK);

    auto media = request(YCORE_CAP_REQUEST_HEADERS | YCORE_CAP_CUSTOM_DEMUX);
    assert(ycore_session_open(session, &media) == YCORE_OK);
    ycore_state_t state{};
    state.struct_size = sizeof(state);
    state.abi_version = YCORE_ABI_VERSION;
    assert(ycore_session_get_state(session, &state) == YCORE_OK);
    assert(state.route == YCORE_ROUTE_NATIVE_ENHANCED);
    assert(std::string(state.engine) == "HarmonyNative");
    assert(native.opened_position == 42'000);
    ycore_session_destroy(session);
}

void test_handover_preserves_state() {
    auto *session = ycore_session_create();
    FakeEngine primary;
    FakeEngine fallback;
    const ycore_capabilities_t capabilities = YCORE_CAP_REMOTE_URL | YCORE_CAP_HARDWARE_VIDEO;
    const auto first = registration("Primary", YCORE_ROUTE_SYSTEM, 100, capabilities, &primary);
    const auto second = registration("Fallback", YCORE_ROUTE_NATIVE_DIRECT, 50, capabilities, &fallback);
    assert(ycore_session_register_engine(session, &first) == YCORE_OK);
    assert(ycore_session_register_engine(session, &second) == YCORE_OK);
    auto media = request();
    assert(ycore_session_open(session, &media) == YCORE_OK);
    assert(ycore_session_seek_to(session, 91'250) == YCORE_OK);
    assert(ycore_session_set_speed(session, 1.5f) == YCORE_OK);

    primary.state.phase = YCORE_PHASE_FAILED;
    primary.state.failure_category = YCORE_FAILURE_DECODER;
    primary.state.position_ms = 91'250;
    primary.state.playback_requested = 1;
    primary.state.speed = 1.5f;
    assert(ycore_session_tick(session) == YCORE_OK);
    assert(primary.close_count == 1);
    assert(fallback.open_count == 1);
    assert(fallback.opened_position == 91'250);
    assert(fallback.speed == 1.5f);
    ycore_session_destroy(session);
}

void test_authorization_failure_does_not_change_backend() {
    auto *session = ycore_session_create();
    FakeEngine primary;
    FakeEngine fallback;
    const auto first = registration("Primary", YCORE_ROUTE_SYSTEM, 100, YCORE_CAP_REMOTE_URL, &primary);
    const auto second = registration("Fallback", YCORE_ROUTE_NATIVE_DIRECT, 50, YCORE_CAP_REMOTE_URL, &fallback);
    assert(ycore_session_register_engine(session, &first) == YCORE_OK);
    assert(ycore_session_register_engine(session, &second) == YCORE_OK);
    auto media = request();
    assert(ycore_session_open(session, &media) == YCORE_OK);
    primary.state.phase = YCORE_PHASE_FAILED;
    primary.state.failure_category = YCORE_FAILURE_AUTHORIZATION;
    assert(ycore_session_tick(session) == YCORE_OK);
    assert(primary.close_count == 0);
    assert(fallback.open_count == 0);
    ycore_session_destroy(session);
}

void test_ffi_convenience_api() {
    auto *session = ycore_session_create();
    FakeEngine engine;
    const auto value = registration("FFI Engine", YCORE_ROUTE_NATIVE_DIRECT, 1, YCORE_CAP_REMOTE_URL, &engine);
    assert(ycore_session_register_engine(session, &value) == YCORE_OK);
    assert(ycore_session_open_values(
               session, "item", "https://example.invalid/video", "video/mp4", "{}", "emby",
               12'345, 1, YCORE_CAP_REMOTE_URL) == YCORE_OK);
    assert(ycore_session_state_phase(session) == YCORE_PHASE_READY);
    assert(ycore_session_state_route(session) == YCORE_ROUTE_NATIVE_DIRECT);
    assert(ycore_session_state_playing(session) == 1);
    assert(ycore_session_state_position_ms(session) == 12'345);
    assert(std::string(ycore_session_state_engine(session)) == "FFI Engine");
    ycore_session_destroy(session);
}

void test_build_capabilities_do_not_claim_uncompiled_features() {
    ycore_build_info_t info{};
    info.struct_size = sizeof(info);
    info.abi_version = YCORE_ABI_VERSION;
    assert(ycore_get_build_info(&info) == YCORE_OK);
    assert(info.build_id != nullptr);
    assert((info.compiled_capabilities & YCORE_CAP_NATIVE_WINDOW) == 0);
    assert((info.compiled_capabilities & YCORE_CAP_OPTICAL_DISC) == 0);
}

void test_open_failure_falls_back() {
    auto *session = ycore_session_create();
    FakeEngine broken;
    FakeEngine working;
    broken.open_result = YCORE_ERROR_ENGINE_OPEN;
    const auto first = registration("Broken", YCORE_ROUTE_SYSTEM, 100, YCORE_CAP_REMOTE_URL, &broken);
    const auto second = registration("Working", YCORE_ROUTE_NATIVE_DIRECT, 50, YCORE_CAP_REMOTE_URL, &working);
    assert(ycore_session_register_engine(session, &first) == YCORE_OK);
    assert(ycore_session_register_engine(session, &second) == YCORE_OK);
    auto media = request();
    assert(ycore_session_open(session, &media) == YCORE_OK);
    assert(broken.open_count == 1);
    assert(broken.close_count == 1);
    assert(working.open_count == 1);
    assert(std::string(ycore_session_state_engine(session)) == "Working");
    ycore_session_destroy(session);
}

void test_no_compatible_engine_is_explicit() {
    auto *session = ycore_session_create();
    FakeEngine engine;
    const auto value = registration("Basic", YCORE_ROUTE_SYSTEM, 1, YCORE_CAP_REMOTE_URL, &engine);
    assert(ycore_session_register_engine(session, &value) == YCORE_OK);
    auto media = request(YCORE_CAP_OPTICAL_DISC | YCORE_CAP_NATIVE_WINDOW);
    assert(ycore_session_open(session, &media) == YCORE_ERROR_NO_ENGINE);
    assert(ycore_session_state_phase(session) == YCORE_PHASE_FAILED);
    assert(engine.open_count == 0);
    ycore_session_destroy(session);
}

void test_handover_restores_tracks_output_and_pause_intent() {
    auto *session = ycore_session_create();
    FakeEngine first_engine;
    FakeEngine second_engine;
    const auto first = registration("First", YCORE_ROUTE_SYSTEM, 100, YCORE_CAP_REMOTE_URL, &first_engine);
    const auto second = registration("Second", YCORE_ROUTE_NATIVE_DIRECT, 50, YCORE_CAP_REMOTE_URL, &second_engine);
    assert(ycore_session_register_engine(session, &first) == YCORE_OK);
    assert(ycore_session_register_engine(session, &second) == YCORE_OK);
    int output = 7;
    assert(ycore_session_set_video_output(session, &output) == YCORE_OK);
    auto media = request();
    assert(ycore_session_open(session, &media) == YCORE_OK);
    assert(ycore_session_select_track(session, YCORE_TRACK_AUDIO, "audio-2") == YCORE_OK);
    assert(ycore_session_select_track(session, YCORE_TRACK_SUBTITLE, "subtitle-4") == YCORE_OK);
    assert(ycore_session_pause(session) == YCORE_OK);
    assert(ycore_session_handover(session) == YCORE_OK);
    assert(second_engine.output_count == 1);
    assert(second_engine.track_count == 2);
    assert(second_engine.pause_count == 1);
    assert(ycore_session_state_playback_requested(session) == 0);
    ycore_session_destroy(session);
}

void test_drm_failure_does_not_change_backend() {
    auto *session = ycore_session_create();
    FakeEngine primary;
    FakeEngine fallback;
    const auto first = registration("Primary", YCORE_ROUTE_SYSTEM, 100, YCORE_CAP_REMOTE_URL, &primary);
    const auto second = registration("Fallback", YCORE_ROUTE_NATIVE_DIRECT, 50, YCORE_CAP_REMOTE_URL, &fallback);
    assert(ycore_session_register_engine(session, &first) == YCORE_OK);
    assert(ycore_session_register_engine(session, &second) == YCORE_OK);
    auto media = request();
    assert(ycore_session_open(session, &media) == YCORE_OK);
    primary.state.phase = YCORE_PHASE_FAILED;
    primary.state.failure_category = YCORE_FAILURE_DRM;
    assert(ycore_session_tick(session) == YCORE_OK);
    assert(primary.close_count == 0);
    assert(fallback.open_count == 0);
    ycore_session_destroy(session);
}

}  // namespace

int main() {
    test_priority_and_capability_routing();
    test_handover_preserves_state();
    test_authorization_failure_does_not_change_backend();
    test_ffi_convenience_api();
    test_build_capabilities_do_not_claim_uncompiled_features();
    test_open_failure_falls_back();
    test_no_compatible_engine_is_explicit();
    test_handover_restores_tracks_output_and_pause_intent();
    test_drm_failure_does_not_change_backend();
    return 0;
}
