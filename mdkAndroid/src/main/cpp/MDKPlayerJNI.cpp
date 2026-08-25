#include <jni.h>

#include <mdk/MediaInfo.h>
#include <mdk/Player.h>
#include <mdk/global.h>

#include <algorithm>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <vector>

using namespace MDK_NS;

namespace {

JavaVM* gJavaVm = nullptr;

struct RuntimeState {
    std::mutex mutex;
    int lastError = 0;
    std::string lastErrorCategory;
    std::string lastErrorDetail;
    bool firstVideoFrameRendered = false;
    std::string videoDecoder;
    std::string audioDecoder;
    jobject listener = nullptr;
    bool closed = false;
    int64_t eventRevision = 0;
};

struct PlayerRef {
    std::unique_ptr<Player> player = std::make_unique<Player>();
    jobject surface = nullptr;
    int selectedAudio = -1;
    int selectedSubtitle = 0;
    std::shared_ptr<RuntimeState> runtime = std::make_shared<RuntimeState>();
};

PlayerRef* ref(jlong ptr) {
    return reinterpret_cast<PlayerRef*>(ptr);
}

Player* player(jlong ptr) {
    auto* value = ref(ptr);
    return value == nullptr ? nullptr : value->player.get();
}

MediaType mediaType(jint value) {
    switch (value) {
        case 0:
            return MediaType::Video;
        case 1:
            return MediaType::Audio;
        case 3:
            return MediaType::Subtitle;
        default:
            return MediaType::Unknown;
    }
}

std::string metadataValue(
        const std::unordered_map<std::string, std::string>& metadata,
        const char* key
) {
    const auto found = metadata.find(key);
    return found == metadata.end() ? std::string() : found->second;
}

std::string cleanField(std::string value) {
    std::replace(value.begin(), value.end(), '\x1f', ' ');
    return value;
}

std::string safeString(const char* value) {
    return value == nullptr ? std::string() : std::string(value);
}

std::string colorSpaceName(ColorSpace value) {
    switch (value) {
        case ColorSpaceBT709:
            return "BT.709";
        case ColorSpaceBT2100_PQ:
            return "BT.2100 PQ";
        case ColorSpaceSCRGB:
            return "scRGB";
        case ColorSpaceExtendedLinearDisplayP3:
            return "Extended Linear Display P3";
        case ColorSpaceExtendedSRGB:
            return "Extended sRGB";
        case ColorSpaceExtendedLinearSRGB:
            return "Extended Linear sRGB";
        case ColorSpaceBT2100_HLG:
            return "BT.2100 HLG";
        case ColorSpaceUnknown:
        default:
            return "Unknown";
    }
}

template <typename Stream>
std::string trackRow(const Stream& stream, int ordinal, bool selected) {
    const auto language = cleanField(metadataValue(stream.metadata, "language"));
    auto title = cleanField(metadataValue(stream.metadata, "title"));
    if (title.empty()) {
        title = cleanField(metadataValue(stream.metadata, "handler_name"));
    }
    return std::to_string(ordinal) + '\x1f' + language + '\x1f' + title + '\x1f' +
           (selected ? "1" : "0");
}

jobjectArray stringArray(JNIEnv* env, const std::vector<std::string>& values) {
    auto* stringClass = env->FindClass("java/lang/String");
    auto result = env->NewObjectArray(
            static_cast<jsize>(values.size()),
            stringClass,
            nullptr
    );
    for (size_t index = 0; index < values.size(); ++index) {
        auto value = env->NewStringUTF(values[index].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

void clearSurface(JNIEnv* env, PlayerRef* value) {
    if (value == nullptr || value->surface == nullptr) {
        return;
    }
    value->player->updateNativeSurface(nullptr, 0, 0);
    env->DeleteGlobalRef(value->surface);
    value->surface = nullptr;
}

void notifyRuntimeEvent(const std::shared_ptr<RuntimeState>& runtime) {
    if (gJavaVm == nullptr) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    const auto status = gJavaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (gJavaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    } else if (status != JNI_OK) {
        return;
    }

    jobject listener = nullptr;
    int64_t revision = 0;
    {
        std::lock_guard<std::mutex> lock(runtime->mutex);
        if (!runtime->closed && runtime->listener != nullptr) {
            listener = env->NewLocalRef(runtime->listener);
            revision = runtime->eventRevision;
        }
    }
    if (listener != nullptr) {
        auto listenerClass = env->GetObjectClass(listener);
        auto callback = env->GetMethodID(listenerClass, "onNativeEvent", "(J)V");
        if (callback != nullptr) {
            env->CallVoidMethod(listener, callback, static_cast<jlong>(revision));
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(listenerClass);
        env->DeleteLocalRef(listener);
    }
    if (attached) gJavaVm->DetachCurrentThread();
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    gJavaVm = vm;
    SetGlobalOption("JavaVM", reinterpret_cast<void*>(vm));
    SetGlobalOption("log", LogLevel::Warning);
    // Ask MDK's Android renderer to negotiate the matching EGL colorspace and forward HDR
    // metadata when the display chain supports it. Runtime diagnostics still distinguish this
    // request from proof that Dolby Vision actually reached the display.
    SetGlobalOption("videoout.hdr", 1);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeCreate(JNIEnv*, jclass) {
    auto* value = new PlayerRef();
    value->player->setTimeout(20'000);
    value->player->setProperty("subtitle", "1");
    const auto runtime = value->runtime;
    value->player->onEvent([runtime](const MediaEvent& event) {
        {
            std::lock_guard<std::mutex> lock(runtime->mutex);
            if (runtime->closed) return false;
            if (event.category == "render.video" && event.detail == "1st_frame") {
                runtime->firstVideoFrameRendered = true;
            } else if (event.category == "decoder.video" && event.error == 0 &&
                       event.detail != "open" && event.detail != "size") {
                runtime->videoDecoder = event.detail;
            } else if (event.category == "decoder.audio" && event.error == 0 &&
                       event.detail != "open") {
                runtime->audioDecoder = event.detail;
            }
            // Positive values carry event data (for example the first-frame timestamp or a thread
            // state), while only negative values are failures in MDK's event contract.
            if (event.error < 0 && event.category != "reader.buffering") {
                runtime->lastError = static_cast<int>(event.error);
                runtime->lastErrorCategory = event.category;
                runtime->lastErrorDetail = event.detail;
            }
            ++runtime->eventRevision;
        }
        notifyRuntimeEvent(runtime);
        return false;
    });
    return reinterpret_cast<jlong>(value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeDestroy(JNIEnv* env, jclass, jlong ptr) {
    auto* value = ref(ptr);
    if (value == nullptr) {
        return;
    }
    clearSurface(env, value);
    {
        std::lock_guard<std::mutex> lock(value->runtime->mutex);
        value->runtime->closed = true;
        if (value->runtime->listener != nullptr) {
            env->DeleteGlobalRef(value->runtime->listener);
            value->runtime->listener = nullptr;
        }
    }
    value->player->set(State::Stopped);
    delete value;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSetMedia(
        JNIEnv* env,
        jclass,
        jlong ptr,
        jstring url
) {
    auto* value = ref(ptr);
    if (value == nullptr) {
        return;
    }
    value->selectedAudio = -1;
    value->selectedSubtitle = 0;
    {
        std::lock_guard<std::mutex> lock(value->runtime->mutex);
        value->runtime->lastError = 0;
        value->runtime->lastErrorCategory.clear();
        value->runtime->lastErrorDetail.clear();
        value->runtime->firstVideoFrameRendered = false;
        value->runtime->videoDecoder.clear();
        value->runtime->audioDecoder.clear();
        ++value->runtime->eventRevision;
    }
    if (url == nullptr) {
        value->player->setMedia(nullptr);
        return;
    }
    const char* chars = env->GetStringUTFChars(url, nullptr);
    value->player->setMedia(chars);
    env->ReleaseStringUTFChars(url, chars);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSetProperty(
        JNIEnv* env,
        jclass,
        jlong ptr,
        jstring name,
        jstring propertyValue
) {
    auto* value = player(ptr);
    if (value == nullptr || name == nullptr || propertyValue == nullptr) {
        return;
    }
    const char* nameChars = env->GetStringUTFChars(name, nullptr);
    const char* valueChars = env->GetStringUTFChars(propertyValue, nullptr);
    value->setProperty(nameChars, valueChars);
    env->ReleaseStringUTFChars(propertyValue, valueChars);
    env->ReleaseStringUTFChars(name, nameChars);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSetState(
        JNIEnv*,
        jclass,
        jlong ptr,
        jint state
) {
    if (auto* value = player(ptr)) {
        value->set(static_cast<State>(state));
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeState(JNIEnv*, jclass, jlong ptr) {
    auto* value = player(ptr);
    return value == nullptr ? 0 : static_cast<jint>(value->state());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativePosition(JNIEnv*, jclass, jlong ptr) {
    auto* value = player(ptr);
    return value == nullptr ? 0 : static_cast<jlong>(value->position());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeDuration(JNIEnv*, jclass, jlong ptr) {
    auto* value = player(ptr);
    return value == nullptr ? 0 : static_cast<jlong>(value->mediaInfo().duration);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeBufferedDuration(JNIEnv*, jclass, jlong ptr) {
    auto* value = player(ptr);
    return value == nullptr ? 0 : static_cast<jlong>(value->buffered());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeMediaStatus(JNIEnv*, jclass, jlong ptr) {
    auto* value = player(ptr);
    return value == nullptr ? static_cast<jint>(MediaStatus::Invalid)
                            : static_cast<jint>(value->mediaStatus());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeLastError(JNIEnv* env, jclass, jlong ptr) {
    auto* value = ref(ptr);
    if (value == nullptr) {
        return env->NewStringUTF("");
    }
    std::lock_guard<std::mutex> lock(value->runtime->mutex);
    if (value->runtime->lastError == 0 && value->runtime->lastErrorCategory.empty() &&
        value->runtime->lastErrorDetail.empty()) {
        return env->NewStringUTF("");
    }
    const auto details = std::to_string(value->runtime->lastError) + " " +
                         value->runtime->lastErrorCategory + " " +
                         value->runtime->lastErrorDetail;
    value->runtime->lastError = 0;
    value->runtime->lastErrorCategory.clear();
    value->runtime->lastErrorDetail.clear();
    return env->NewStringUTF(details.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeVideoHeight(JNIEnv*, jclass, jlong ptr) {
    auto* value = player(ptr);
    if (value == nullptr) {
        return 0;
    }
    const auto& videos = value->mediaInfo().video;
    return videos.empty() ? 0 : videos.front().codec.height;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativePlaybackEvidence(JNIEnv* env, jclass, jlong ptr) {
    auto* value = ref(ptr);
    if (value == nullptr) {
        return stringArray(env, {});
    }

    bool firstFrame = false;
    std::string videoDecoder;
    std::string audioDecoder;
    int64_t eventRevision = 0;
    {
        std::lock_guard<std::mutex> lock(value->runtime->mutex);
        firstFrame = value->runtime->firstVideoFrameRendered;
        videoDecoder = value->runtime->videoDecoder;
        audioDecoder = value->runtime->audioDecoder;
        eventRevision = value->runtime->eventRevision;
    }

    const auto& info = value->player->mediaInfo();
    const VideoCodecParameters* video =
            info.video.empty() ? nullptr : &info.video.front().codec;
    const AudioCodecParameters* audio =
            info.audio.empty() ? nullptr : &info.audio.front().codec;
    return stringArray(env, {
            firstFrame ? "1" : "0",
            videoDecoder,
            audioDecoder,
            video == nullptr ? "" : safeString(video->codec),
            video == nullptr ? "" : safeString(video->format_name),
            video == nullptr ? "0" : std::to_string(video->width),
            video == nullptr ? "0" : std::to_string(video->height),
            video == nullptr ? "0" : std::to_string(video->bit_rate),
            video == nullptr ? "0" : std::to_string(video->frame_rate),
            video == nullptr ? "Unknown" : colorSpaceName(video->color_space),
            video == nullptr ? "0" : std::to_string(video->dovi_profile),
            video == nullptr ? "-99" : std::to_string(video->profile),
            audio == nullptr ? "" : safeString(audio->codec),
            audio == nullptr ? "0" : std::to_string(audio->channels),
            audio == nullptr ? "0" : std::to_string(audio->sample_rate),
            audio == nullptr ? "0" : std::to_string(audio->bit_rate),
            std::to_string(info.bit_rate),
            std::to_string(eventRevision),
            std::to_string(MDK_NS::version()),
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeVersion(JNIEnv*, jclass) {
    return static_cast<jint>(MDK_NS::version());
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSetListener(
        JNIEnv* env,
        jclass,
        jlong ptr,
        jobject listener
) {
    auto* value = ref(ptr);
    if (value == nullptr) return;
    std::lock_guard<std::mutex> lock(value->runtime->mutex);
    if (value->runtime->listener != nullptr) {
        env->DeleteGlobalRef(value->runtime->listener);
        value->runtime->listener = nullptr;
    }
    if (!value->runtime->closed && listener != nullptr) {
        value->runtime->listener = env->NewGlobalRef(listener);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSeek(
        JNIEnv*,
        jclass,
        jlong ptr,
        jlong positionMs
) {
    if (auto* value = player(ptr)) {
        value->seek(static_cast<int64_t>(positionMs));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSetPlaybackRate(
        JNIEnv*,
        jclass,
        jlong ptr,
        jfloat rate
) {
    if (auto* value = player(ptr)) {
        value->setPlaybackRate(rate);
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativePlaybackRate(JNIEnv*, jclass, jlong ptr) {
    auto* value = player(ptr);
    return value == nullptr ? 1.0f : value->playbackRate();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSetDecoderMode(
        JNIEnv*,
        jclass,
        jlong ptr,
        jint mode
) {
    auto* value = player(ptr);
    if (value == nullptr) {
        return;
    }
    if (mode == 0) {
        value->setDecoders(MediaType::Video, {"AMediaCodec", "FFmpeg", "dav1d"});
    } else if (mode == 1) {
        value->setDecoders(MediaType::Video, {"FFmpeg", "dav1d"});
    }
    // Automatic mode deliberately keeps MDK's own decoder selection policy.
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSetFill(
        JNIEnv*,
        jclass,
        jlong ptr,
        jboolean fill
) {
    if (auto* value = player(ptr)) {
        value->setAspectRatio(fill ? KeepAspectRatioCrop : KeepAspectRatio);
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeTracks(
        JNIEnv* env,
        jclass,
        jlong ptr,
        jint type
) {
    auto* value = ref(ptr);
    if (value == nullptr) {
        return stringArray(env, {});
    }

    std::vector<std::string> rows;
    const auto& info = value->player->mediaInfo();
    if (type == static_cast<jint>(MediaType::Audio)) {
        rows.reserve(info.audio.size());
        for (size_t index = 0; index < info.audio.size(); ++index) {
            rows.push_back(trackRow(
                    info.audio[index],
                    static_cast<int>(index),
                    static_cast<int>(index) == value->selectedAudio
            ));
        }
    } else if (type == static_cast<jint>(MediaType::Subtitle)) {
        rows.reserve(info.subtitle.size());
        for (size_t index = 0; index < info.subtitle.size(); ++index) {
            rows.push_back(trackRow(
                    info.subtitle[index],
                    static_cast<int>(index),
                    static_cast<int>(index) == value->selectedSubtitle
            ));
        }
    }
    return stringArray(env, rows);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSetActiveTrack(
        JNIEnv*,
        jclass,
        jlong ptr,
        jint type,
        jint ordinal
) {
    auto* value = ref(ptr);
    if (value == nullptr) {
        return;
    }
    std::set<int> tracks;
    if (ordinal >= 0) {
        tracks.insert(ordinal);
    }
    const auto targetType = mediaType(type);
    if (targetType == MediaType::Unknown || targetType == MediaType::Video) {
        return;
    }
    value->player->setActiveTracks(targetType, tracks);
    if (targetType == MediaType::Audio) {
        value->selectedAudio = ordinal;
    } else {
        value->selectedSubtitle = ordinal;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSetActiveTracks(
        JNIEnv* env,
        jclass,
        jlong ptr,
        jint type,
        jint primaryOrdinal,
        jintArray ordinals
) {
    auto* value = ref(ptr);
    if (value == nullptr) {
        return;
    }
    const auto targetType = mediaType(type);
    if (targetType == MediaType::Unknown || targetType == MediaType::Video) {
        return;
    }
    std::set<int> tracks;
    if (ordinals != nullptr) {
        const auto count = env->GetArrayLength(ordinals);
        std::vector<jint> values(static_cast<size_t>(count));
        if (count > 0) {
            env->GetIntArrayRegion(ordinals, 0, count, values.data());
        }
        for (const auto ordinal : values) {
            if (ordinal >= 0) tracks.insert(ordinal);
        }
    }
    value->player->setActiveTracks(targetType, tracks);
    if (targetType == MediaType::Audio) {
        value->selectedAudio = primaryOrdinal;
    } else {
        value->selectedSubtitle = primaryOrdinal;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeSetSurface(
        JNIEnv* env,
        jclass,
        jlong ptr,
        jobject surface,
        jint width,
        jint height
) {
    auto* value = ref(ptr);
    if (value == nullptr) {
        return;
    }
    if (surface == nullptr) {
        clearSurface(env, value);
        return;
    }
    if (value->surface != nullptr && env->IsSameObject(value->surface, surface)) {
        value->player->updateNativeSurface(value->surface, width, height);
        return;
    }
    clearSurface(env, value);
    value->surface = env->NewGlobalRef(surface);
    value->player->updateNativeSurface(value->surface, width, height);
}
