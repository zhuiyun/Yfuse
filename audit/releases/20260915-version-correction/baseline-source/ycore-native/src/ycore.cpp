#include "ycore/ycore.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {

template <size_t N>
void copy_text(char (&destination)[N], const char *source) {
    destination[0] = '\0';
    if (source == nullptr) return;
    std::strncpy(destination, source, N - 1);
    destination[N - 1] = '\0';
}

bool valid_header(uint32_t size, uint32_t minimum, uint32_t version) {
    return size >= minimum && version == YCORE_ABI_VERSION;
}

bool eligible_for_automatic_handover(ycore_failure_category_t category) {
    return category != YCORE_FAILURE_AUTHORIZATION && category != YCORE_FAILURE_DRM;
}

struct OwnedRequest {
    std::string media_id;
    std::string uri;
    std::string mime_type;
    std::string headers_json;
    std::string provider_key;
    int64_t start_position_ms = 0;
    int32_t auto_play = 1;
    ycore_capabilities_t requirements = 0;

    ycore_media_request_t view(int64_t position_ms, int32_t should_play) const {
        ycore_media_request_t value{};
        value.struct_size = sizeof(value);
        value.abi_version = YCORE_ABI_VERSION;
        value.media_id = media_id.c_str();
        value.uri = uri.c_str();
        value.mime_type = mime_type.c_str();
        value.headers_json = headers_json.c_str();
        value.provider_key = provider_key.c_str();
        value.start_position_ms = position_ms;
        value.auto_play = should_play;
        value.requirements = requirements;
        return value;
    }
};

struct Engine {
    std::string name;
    ycore_route_t route = YCORE_ROUTE_SYSTEM;
    int32_t priority = 0;
    ycore_capabilities_t capabilities = 0;
    void *context = nullptr;
    ycore_engine_vtable_t vtable{};
};

}  // namespace

struct ycore_session {
    std::recursive_mutex mutex;
    std::vector<Engine> engines;
    int active_index = -1;
    /**
     * Engines this request has already been opened on.
     *
     * Handover only ever knew which engine it was leaving, so two engines that both open and then
     * fail sent every later tick back and forth between them for as long as playback was attempted.
     * An explicit retry clears this; automatic handover never revisits an engine.
     */
    std::vector<int> attempted;
    OwnedRequest request;
    bool has_request = false;
    void *video_output = nullptr;
    std::string audio_track_id;
    std::string subtitle_track_id;
    ycore_state_t state{};
    ycore_state_listener_t listener = nullptr;
    void *listener_user_data = nullptr;

    ycore_session() {
        state.struct_size = sizeof(state);
        state.abi_version = YCORE_ABI_VERSION;
        state.phase = YCORE_PHASE_IDLE;
        state.failure_category = YCORE_FAILURE_NONE;
        state.speed = 1.0f;
        state.av_sync_offset_ms = INT64_MIN;
    }

    void publish() {
        if (listener != nullptr) listener(&state, listener_user_data);
    }

    bool compatible(const Engine &engine) const {
        return (engine.capabilities & request.requirements) == request.requirements;
    }

    void close_active() {
        if (active_index < 0) return;
        Engine &engine = engines[static_cast<size_t>(active_index)];
        if (engine.vtable.close != nullptr) engine.vtable.close(engine.context);
        active_index = -1;
    }

    bool already_attempted(int index) const {
        return std::find(attempted.begin(), attempted.end(), index) != attempted.end();
    }

    int activate(int index, int64_t position_ms, bool playback_requested) {
        Engine &engine = engines[static_cast<size_t>(index)];
        attempted.push_back(index);
        const ycore_media_request_t open_request =
            request.view(std::max<int64_t>(0, position_ms), playback_requested ? 1 : 0);

        state.phase = YCORE_PHASE_PREPARING;
        state.buffering = 1;
        state.playing = 0;
        state.playback_requested = playback_requested ? 1 : 0;
        state.position_ms = open_request.start_position_ms;
        state.route = engine.route;
        state.failure_category = YCORE_FAILURE_NONE;
        copy_text(state.engine, engine.name.c_str());
        copy_text(state.reason, "opening backend");
        publish();

        if (engine.vtable.open == nullptr || engine.vtable.open(engine.context, &open_request) != YCORE_OK) {
            if (engine.vtable.close != nullptr) engine.vtable.close(engine.context);
            return YCORE_ERROR_ENGINE_OPEN;
        }

        active_index = index;
        if (video_output != nullptr && engine.vtable.set_video_output != nullptr) {
            engine.vtable.set_video_output(engine.context, video_output);
        }
        if (engine.vtable.set_speed != nullptr) engine.vtable.set_speed(engine.context, state.speed);
        if (!audio_track_id.empty() && engine.vtable.select_track != nullptr) {
            engine.vtable.select_track(engine.context, YCORE_TRACK_AUDIO, audio_track_id.c_str());
        }
        if (!subtitle_track_id.empty() && engine.vtable.select_track != nullptr) {
            engine.vtable.select_track(engine.context, YCORE_TRACK_SUBTITLE, subtitle_track_id.c_str());
        }
        if (playback_requested && engine.vtable.play != nullptr) {
            engine.vtable.play(engine.context);
        } else if (!playback_requested && engine.vtable.pause != nullptr) {
            engine.vtable.pause(engine.context);
        }

        state.phase = YCORE_PHASE_READY;
        state.buffering = 0;
        state.playing = playback_requested ? 1 : 0;
        copy_text(state.reason, "backend ready");
        publish();
        return YCORE_OK;
    }

    int open_next() {
        std::vector<int> candidates;
        for (size_t index = 0; index < engines.size(); ++index) {
            if (!already_attempted(static_cast<int>(index)) && compatible(engines[index])) {
                candidates.push_back(static_cast<int>(index));
            }
        }
        std::stable_sort(candidates.begin(), candidates.end(), [&](int left, int right) {
            return engines[static_cast<size_t>(left)].priority > engines[static_cast<size_t>(right)].priority;
        });

        const int64_t resume_position = state.position_ms;
        const bool resume_playback = state.playback_requested != 0;
        for (int candidate : candidates) {
            if (activate(candidate, resume_position, resume_playback) == YCORE_OK) return YCORE_OK;
        }
        state.phase = YCORE_PHASE_FAILED;
        state.playing = 0;
        state.buffering = 0;
        if (state.failure_category == YCORE_FAILURE_NONE) state.failure_category = YCORE_FAILURE_UNKNOWN;
        copy_text(state.reason, "no compatible backend opened the media");
        publish();
        return candidates.empty() ? YCORE_ERROR_NO_ENGINE : YCORE_ERROR_ENGINE_OPEN;
    }
};

extern "C" {

ycore_session_t *ycore_session_create(void) {
    try {
        return new ycore_session();
    } catch (...) {
        return nullptr;
    }
}

void ycore_session_destroy(ycore_session_t *session) {
    if (session == nullptr) return;
    {
        std::lock_guard<std::recursive_mutex> lock(session->mutex);
        session->close_active();
    }
    delete session;
}

int32_t ycore_session_register_engine(
    ycore_session_t *session,
    const ycore_engine_registration_t *registration) {
    if (session == nullptr || registration == nullptr || registration->name == nullptr ||
        !valid_header(registration->struct_size, sizeof(*registration), registration->abi_version) ||
        !valid_header(registration->vtable.struct_size, sizeof(registration->vtable), registration->vtable.abi_version)) {
        return YCORE_ERROR_INVALID_ARGUMENT;
    }
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    if (session->has_request) return YCORE_ERROR_NOT_READY;
    if (std::any_of(session->engines.begin(), session->engines.end(), [&](const Engine &engine) {
            return engine.name == registration->name;
        })) {
        return YCORE_ERROR_INVALID_ARGUMENT;
    }
    Engine engine;
    engine.name = registration->name;
    engine.route = registration->route;
    engine.priority = registration->priority;
    engine.capabilities = registration->capabilities;
    engine.context = registration->context;
    engine.vtable = registration->vtable;
    session->engines.push_back(std::move(engine));
    return YCORE_OK;
}

int32_t ycore_session_set_listener(
    ycore_session_t *session,
    ycore_state_listener_t listener,
    void *user_data) {
    if (session == nullptr) return YCORE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    session->listener = listener;
    session->listener_user_data = user_data;
    return YCORE_OK;
}

int32_t ycore_session_open(ycore_session_t *session, const ycore_media_request_t *request) {
    if (session == nullptr || request == nullptr || request->uri == nullptr || request->uri[0] == '\0' ||
        request->start_position_ms < 0 ||
        !valid_header(request->struct_size, sizeof(*request), request->abi_version)) {
        return YCORE_ERROR_INVALID_ARGUMENT;
    }
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    session->close_active();
    session->request.media_id = request->media_id == nullptr ? "" : request->media_id;
    session->request.uri = request->uri;
    session->request.mime_type = request->mime_type == nullptr ? "" : request->mime_type;
    session->request.headers_json = request->headers_json == nullptr ? "{}" : request->headers_json;
    session->request.provider_key = request->provider_key == nullptr ? "" : request->provider_key;
    session->request.start_position_ms = request->start_position_ms;
    session->request.auto_play = request->auto_play != 0;
    session->request.requirements = request->requirements;
    session->has_request = true;
    session->state.position_ms = request->start_position_ms;
    session->state.playback_requested = request->auto_play != 0;
    session->state.speed = 1.0f;
    session->state.failure_category = YCORE_FAILURE_NONE;
    session->attempted.clear();
    return session->open_next();
}

int32_t ycore_session_play(ycore_session_t *session) {
    if (session == nullptr) return YCORE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    session->state.playback_requested = 1;
    if (session->active_index < 0) return YCORE_ERROR_NOT_READY;
    Engine &engine = session->engines[static_cast<size_t>(session->active_index)];
    const int32_t result = engine.vtable.play == nullptr ? YCORE_ERROR_UNSUPPORTED : engine.vtable.play(engine.context);
    if (result == YCORE_OK) session->state.playing = 1;
    session->publish();
    return result;
}

int32_t ycore_session_pause(ycore_session_t *session) {
    if (session == nullptr) return YCORE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    session->state.playback_requested = 0;
    if (session->active_index < 0) return YCORE_ERROR_NOT_READY;
    Engine &engine = session->engines[static_cast<size_t>(session->active_index)];
    const int32_t result = engine.vtable.pause == nullptr ? YCORE_ERROR_UNSUPPORTED : engine.vtable.pause(engine.context);
    if (result == YCORE_OK) session->state.playing = 0;
    session->publish();
    return result;
}

int32_t ycore_session_seek_to(ycore_session_t *session, int64_t position_ms) {
    if (session == nullptr || position_ms < 0) return YCORE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    if (session->active_index < 0) return YCORE_ERROR_NOT_READY;
    Engine &engine = session->engines[static_cast<size_t>(session->active_index)];
    const int32_t result = engine.vtable.seek_to == nullptr
        ? YCORE_ERROR_UNSUPPORTED
        : engine.vtable.seek_to(engine.context, position_ms);
    if (result == YCORE_OK) session->state.position_ms = position_ms;
    session->publish();
    return result;
}

int32_t ycore_session_set_speed(ycore_session_t *session, float speed) {
    if (session == nullptr || speed < 0.25f || speed > 4.0f) return YCORE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    session->state.speed = speed;
    if (session->active_index < 0) return YCORE_ERROR_NOT_READY;
    Engine &engine = session->engines[static_cast<size_t>(session->active_index)];
    const int32_t result = engine.vtable.set_speed == nullptr
        ? YCORE_ERROR_UNSUPPORTED
        : engine.vtable.set_speed(engine.context, speed);
    session->publish();
    return result;
}

int32_t ycore_session_select_track(
    ycore_session_t *session,
    ycore_track_type_t type,
    const char *track_id) {
    if (session == nullptr || track_id == nullptr) return YCORE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    if (type == YCORE_TRACK_AUDIO) session->audio_track_id = track_id;
    else if (type == YCORE_TRACK_SUBTITLE) session->subtitle_track_id = track_id;
    else return YCORE_ERROR_INVALID_ARGUMENT;
    if (session->active_index < 0) return YCORE_ERROR_NOT_READY;
    Engine &engine = session->engines[static_cast<size_t>(session->active_index)];
    return engine.vtable.select_track == nullptr
        ? YCORE_ERROR_UNSUPPORTED
        : engine.vtable.select_track(engine.context, type, track_id);
}

int32_t ycore_session_set_video_output(ycore_session_t *session, void *native_output) {
    if (session == nullptr) return YCORE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    session->video_output = native_output;
    if (session->active_index < 0) return YCORE_OK;
    Engine &engine = session->engines[static_cast<size_t>(session->active_index)];
    return engine.vtable.set_video_output == nullptr
        ? YCORE_ERROR_UNSUPPORTED
        : engine.vtable.set_video_output(engine.context, native_output);
}

int32_t ycore_session_retry(ycore_session_t *session) {
    if (session == nullptr) return YCORE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    // An explicit retry is host-driven and bounded there, so it may start the whole chain over.
    // Only automatic handover has to refuse an engine this request already exhausted.
    session->attempted.clear();
    if (session->active_index < 0) return session->has_request ? session->open_next() : YCORE_ERROR_NOT_READY;
    Engine &engine = session->engines[static_cast<size_t>(session->active_index)];
    if (engine.vtable.retry != nullptr && engine.vtable.retry(engine.context) == YCORE_OK) return YCORE_OK;
    return ycore_session_handover(session);
}

int32_t ycore_session_tick(ycore_session_t *session) {
    if (session == nullptr) return YCORE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    if (session->active_index < 0) return YCORE_ERROR_NOT_READY;
    Engine &engine = session->engines[static_cast<size_t>(session->active_index)];
    if (engine.vtable.poll_state == nullptr) return YCORE_ERROR_UNSUPPORTED;

    ycore_state_t observed = session->state;
    observed.struct_size = sizeof(observed);
    observed.abi_version = YCORE_ABI_VERSION;
    const int32_t result = engine.vtable.poll_state(engine.context, &observed);
    if (result != YCORE_OK) return result;
    observed.route = engine.route;
    copy_text(observed.engine, engine.name.c_str());
    session->state = observed;
    session->publish();

    if (observed.phase == YCORE_PHASE_FAILED && eligible_for_automatic_handover(observed.failure_category)) {
        return ycore_session_handover(session);
    }
    return YCORE_OK;
}

int32_t ycore_session_handover(ycore_session_t *session) {
    if (session == nullptr) return YCORE_ERROR_INVALID_ARGUMENT;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    if (!session->has_request) return YCORE_ERROR_NOT_READY;
    session->close_active();
    return session->open_next();
}

int32_t ycore_session_get_state(ycore_session_t *session, ycore_state_t *out_state) {
    if (session == nullptr || out_state == nullptr ||
        !valid_header(out_state->struct_size, sizeof(*out_state), out_state->abi_version)) {
        return YCORE_ERROR_INVALID_ARGUMENT;
    }
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    *out_state = session->state;
    return YCORE_OK;
}

int32_t ycore_session_open_values(
    ycore_session_t *session,
    const char *media_id,
    const char *uri,
    const char *mime_type,
    const char *headers_json,
    const char *provider_key,
    int64_t start_position_ms,
    int32_t auto_play,
    ycore_capabilities_t requirements) {
    ycore_media_request_t request{};
    request.struct_size = sizeof(request);
    request.abi_version = YCORE_ABI_VERSION;
    request.media_id = media_id;
    request.uri = uri;
    request.mime_type = mime_type;
    request.headers_json = headers_json;
    request.provider_key = provider_key;
    request.start_position_ms = start_position_ms;
    request.auto_play = auto_play;
    request.requirements = requirements;
    return ycore_session_open(session, &request);
}

int32_t ycore_session_state_phase(ycore_session_t *session) {
    if (session == nullptr) return YCORE_PHASE_FAILED;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.phase;
}

int32_t ycore_session_state_route(ycore_session_t *session) {
    if (session == nullptr) return YCORE_ROUTE_SYSTEM;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.route;
}

int32_t ycore_session_state_playing(ycore_session_t *session) {
    if (session == nullptr) return 0;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.playing;
}

int32_t ycore_session_state_playback_requested(ycore_session_t *session) {
    if (session == nullptr) return 0;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.playback_requested;
}

int32_t ycore_session_state_buffering(ycore_session_t *session) {
    if (session == nullptr) return 0;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.buffering;
}

int64_t ycore_session_state_position_ms(ycore_session_t *session) {
    if (session == nullptr) return 0;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.position_ms;
}

int64_t ycore_session_state_duration_ms(ycore_session_t *session) {
    if (session == nullptr) return 0;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.duration_ms;
}

int64_t ycore_session_state_buffered_position_ms(ycore_session_t *session) {
    if (session == nullptr) return 0;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.buffered_position_ms;
}

float ycore_session_state_speed(ycore_session_t *session) {
    if (session == nullptr) return 1.0f;
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.speed;
}

const char *ycore_session_state_engine(ycore_session_t *session) {
    if (session == nullptr) return "";
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.engine;
}

const char *ycore_session_state_reason(ycore_session_t *session) {
    if (session == nullptr) return "";
    std::lock_guard<std::recursive_mutex> lock(session->mutex);
    return session->state.reason;
}

}  // extern "C"
