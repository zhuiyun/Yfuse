#include <jni.h>

#include <mdk/MediaInfo.h>
#include <mdk/Player.h>
#include <mdk/global.h>

#include <algorithm>
#include <memory>
#include <set>
#include <string>
#include <vector>

using namespace MDK_NS;

namespace {

struct PlayerRef {
    std::unique_ptr<Player> player = std::make_unique<Player>();
    jobject surface = nullptr;
    int selectedAudio = 0;
    int selectedSubtitle = 0;
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

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    SetGlobalOption("JavaVM", reinterpret_cast<void*>(vm));
    SetGlobalOption("log", LogLevel::Warning);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeCreate(JNIEnv*, jclass) {
    auto* value = new PlayerRef();
    value->player->setTimeout(20'000);
    value->player->setProperty("subtitle", "1");
    return reinterpret_cast<jlong>(value);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeDestroy(JNIEnv* env, jclass, jlong ptr) {
    auto* value = ref(ptr);
    if (value == nullptr) {
        return;
    }
    clearSurface(env, value);
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
    value->selectedAudio = 0;
    value->selectedSubtitle = 0;
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

extern "C" JNIEXPORT jint JNICALL
Java_com_mediadevkit_sdk_MDKPlayer_nativeVideoHeight(JNIEnv*, jclass, jlong ptr) {
    auto* value = player(ptr);
    if (value == nullptr) {
        return 0;
    }
    const auto& videos = value->mediaInfo().video;
    return videos.empty() ? 0 : videos.front().codec.height;
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
