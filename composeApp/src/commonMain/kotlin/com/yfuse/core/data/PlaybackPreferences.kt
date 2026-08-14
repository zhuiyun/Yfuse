package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.model.PlaybackQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Amount of on-device storage available to the streaming video cache. */
enum class VideoCacheSize(
    val label: String,
    val bytes: Long,
) {
    Off("关闭", 0L),
    Small("256 MB", 256L * 1024L * 1024L),
    Medium("512 MB", 512L * 1024L * 1024L),
    Large("1 GB", 1024L * 1024L * 1024L),
    ExtraLarge("2 GB", 2L * 1024L * 1024L * 1024L),
}

/** Persisted display refresh-rate intent; backend support is resolved by the player feature. */
enum class PlaybackFrameRateMatch(
    val storageValue: String,
) {
    Disabled("off"),
    SeamlessOnly("seamless_only"),
    Always("always"),
    ;

    companion object {
        fun fromStorage(value: String?): PlaybackFrameRateMatch =
            entries.firstOrNull { it.storageValue == value } ?: Disabled
    }
}

/** Persisted encoded-audio intent. Compatible never means forcing an unsupported route. */
enum class PlaybackAudioPassthrough(
    val storageValue: String,
) {
    Disabled("off"),
    Compatible("compatible"),
    ;

    companion object {
        fun fromStorage(value: String?): PlaybackAudioPassthrough =
            entries.firstOrNull { it.storageValue == value } ?: Disabled
    }
}

/** Engine-independent track identity; numeric track ids change between files and backends. */
@Serializable
data class RememberedPlaybackTrack(
    val language: String? = null,
    val label: String = "",
    val codec: String? = null,
)

/** Per-series enthusiast controls, isolated by server and bounded in [PlaybackPreferences]. */
@Serializable
data class SeriesPlaybackPreference(
    val audio: RememberedPlaybackTrack? = null,
    /** Null means no remembered choice, true means explicitly off, false means [primarySubtitle]. */
    val primarySubtitlesOff: Boolean? = null,
    val primarySubtitle: RememberedPlaybackTrack? = null,
    val secondarySubtitle: RememberedPlaybackTrack? = null,
    val subtitleOffsetMs: Long = 0L,
    val subtitleScale: Float = 1f,
    val subtitleBrightness: Float = 1f,
    val speed: Float = 1f,
    val aspectMode: String = "Fit",
)

@Serializable
private data class StoredSeriesPlaybackPreference(
    val serverId: String,
    val seriesId: String,
    val value: SeriesPlaybackPreference,
)

/** Playback settings that are independent from appearance and decoder selection. */
class PlaybackPreferences(
    private val settings: Settings,
) {
    private val seriesLock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val _videoCacheSize =
        MutableStateFlow(
            settings
                .getStringOrNull(KEY_VIDEO_CACHE_SIZE)
                ?.let { stored -> VideoCacheSize.entries.firstOrNull { it.name == stored } }
                ?: VideoCacheSize.Medium,
        )
    val videoCacheSize: StateFlow<VideoCacheSize> = _videoCacheSize.asStateFlow()

    fun setVideoCacheSize(size: VideoCacheSize) {
        _videoCacheSize.value = size
        settings.putString(KEY_VIDEO_CACHE_SIZE, size.name)
    }

    private val _frameRateMatch =
        MutableStateFlow(
            PlaybackFrameRateMatch.fromStorage(settings.getStringOrNull(KEY_FRAME_RATE_MATCH)),
        )
    val frameRateMatch: StateFlow<PlaybackFrameRateMatch> = _frameRateMatch.asStateFlow()

    fun setFrameRateMatch(mode: PlaybackFrameRateMatch) {
        _frameRateMatch.value = mode
        settings.putString(KEY_FRAME_RATE_MATCH, mode.storageValue)
    }

    private val _audioPassthrough =
        MutableStateFlow(
            PlaybackAudioPassthrough.fromStorage(settings.getStringOrNull(KEY_AUDIO_PASSTHROUGH)),
        )
    val audioPassthrough: StateFlow<PlaybackAudioPassthrough> = _audioPassthrough.asStateFlow()

    fun setAudioPassthrough(mode: PlaybackAudioPassthrough) {
        _audioPassthrough.value = mode
        settings.putString(KEY_AUDIO_PASSTHROUGH, mode.storageValue)
    }

    private val _smartCrossServerSource =
        MutableStateFlow(
            settings.getBoolean(KEY_SMART_CROSS_SERVER_SOURCE, true),
        )

    /**
     * Groups identical titles from different servers and lets playback use a ranked fallback.
     *
     * Enabled by default: it changes neither the library nor the chosen file, and the complete
     * source list stays available in the detail page. Turning it off restores server-scoped
     * search cards and disables automatic cross-server fallback planning.
     */
    val smartCrossServerSource: StateFlow<Boolean> = _smartCrossServerSource.asStateFlow()

    fun setSmartCrossServerSource(enabled: Boolean) {
        _smartCrossServerSource.value = enabled
        settings.putBoolean(KEY_SMART_CROSS_SERVER_SOURCE, enabled)
    }

    private val _wifiQualityCap =
        MutableStateFlow(
            enumSetting(KEY_WIFI_QUALITY_CAP, PlaybackQuality.Original),
        )
    val wifiQualityCap: StateFlow<PlaybackQuality> = _wifiQualityCap.asStateFlow()

    fun setWifiQualityCap(quality: PlaybackQuality) {
        _wifiQualityCap.value = quality
        settings.putString(KEY_WIFI_QUALITY_CAP, quality.name)
    }

    private val _cellularQualityCap =
        MutableStateFlow(
            enumSetting(KEY_CELLULAR_QUALITY_CAP, PlaybackQuality.Hd),
        )
    val cellularQualityCap: StateFlow<PlaybackQuality> = _cellularQualityCap.asStateFlow()

    fun setCellularQualityCap(quality: PlaybackQuality) {
        _cellularQualityCap.value = quality
        settings.putString(KEY_CELLULAR_QUALITY_CAP, quality.name)
    }

    private val _autoQualityDowngrade =
        MutableStateFlow(
            settings.getBoolean(KEY_AUTO_QUALITY_DOWNGRADE, true),
        )
    val autoQualityDowngrade: StateFlow<Boolean> = _autoQualityDowngrade.asStateFlow()

    fun setAutoQualityDowngrade(enabled: Boolean) {
        _autoQualityDowngrade.value = enabled
        settings.putBoolean(KEY_AUTO_QUALITY_DOWNGRADE, enabled)
    }

    private val _qualityLocked = MutableStateFlow(settings.getBoolean(KEY_QUALITY_LOCKED, false))
    val qualityLocked: StateFlow<Boolean> = _qualityLocked.asStateFlow()

    fun setQualityLocked(locked: Boolean) {
        _qualityLocked.value = locked
        settings.putBoolean(KEY_QUALITY_LOCKED, locked)
    }

    fun rememberedQuality(serverId: String): PlaybackQuality? {
        val id = serverId.trim().takeIf { it.isNotEmpty() } ?: return null
        return settings
            .getStringOrNull("$KEY_SERVER_QUALITY_PREFIX$id")
            ?.let { stored -> PlaybackQuality.entries.firstOrNull { it.name == stored } }
    }

    fun rememberQuality(
        serverId: String,
        quality: PlaybackQuality,
    ) {
        val id = serverId.trim().takeIf { it.isNotEmpty() } ?: return
        settings.putString("$KEY_SERVER_QUALITY_PREFIX$id", quality.name)
    }

    private val _resumePrompt = MutableStateFlow(settings.getBoolean(KEY_RESUME_PROMPT, true))

    /**
     * Whether a cold start offers to resume the checkpoint left by the previous process.
     *
     * On by default, because a process that died mid-film is exactly the case the checkpoint
     * exists for. It is a dialog in front of the app on launch, though, and the answer to
     * "do you want to carry on watching?" is often no — so it has to be possible to say so
     * once. Turning it off does not stop the checkpoint being *taken*: 我的 → 播放恢复与同步
     * still has it, which is where someone who declined the prompt goes looking.
     */
    val resumePrompt: StateFlow<Boolean> = _resumePrompt.asStateFlow()

    fun setResumePrompt(enabled: Boolean) {
        _resumePrompt.value = enabled
        settings.putBoolean(KEY_RESUME_PROMPT, enabled)
    }

    fun rememberedSeriesPlayback(
        serverId: String?,
        seriesId: String?,
    ): SeriesPlaybackPreference? {
        val key = seriesPreferenceKey(serverId, seriesId) ?: return null
        return synchronized(seriesLock) {
            readSeriesPreferences()
                .lastOrNull { it.serverId == key.first && it.seriesId == key.second }
                ?.value
        }
    }

    /**
     * Updates and moves one series to the newest end of a compact persisted LRU-like list.
     * Reads do not rewrite storage; choices do, which keeps a hot playback screen write-light.
     */
    fun updateSeriesPlayback(
        serverId: String?,
        seriesId: String?,
        transform: (SeriesPlaybackPreference) -> SeriesPlaybackPreference,
    ) {
        val key = seriesPreferenceKey(serverId, seriesId) ?: return
        synchronized(seriesLock) {
            val entries = readSeriesPreferences()
            val current =
                entries
                    .lastOrNull { it.serverId == key.first && it.seriesId == key.second }
                    ?.value
                    ?: SeriesPlaybackPreference()
            val updated = transform(current).normalized()
            val next =
                (
                    entries.filterNot { it.serverId == key.first && it.seriesId == key.second } +
                        StoredSeriesPlaybackPreference(key.first, key.second, updated)
                ).takeLast(MAX_SERIES_PLAYBACK_PREFERENCES)
            settings.putString(KEY_SERIES_PLAYBACK, json.encodeToString(next))
        }
    }

    internal fun rememberedSeriesPlaybackCount(): Int = synchronized(seriesLock) { readSeriesPreferences().size }

    private fun readSeriesPreferences(): List<StoredSeriesPlaybackPreference> =
        settings
            .getStringOrNull(KEY_SERIES_PLAYBACK)
            ?.let { stored ->
                runCatching {
                    json.decodeFromString<List<StoredSeriesPlaybackPreference>>(stored)
                }.getOrNull()
            }.orEmpty()
            .takeLast(MAX_SERIES_PLAYBACK_PREFERENCES)

    private fun seriesPreferenceKey(
        serverId: String?,
        seriesId: String?,
    ): Pair<String, String>? {
        val server = serverId?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_SERIES_KEY_CHARS) ?: return null
        val series = seriesId?.trim()?.takeIf(String::isNotEmpty)?.take(MAX_SERIES_KEY_CHARS) ?: return null
        return server to series
    }

    private fun SeriesPlaybackPreference.normalized(): SeriesPlaybackPreference =
        copy(
            audio = audio?.normalized(),
            primarySubtitle = primarySubtitle?.normalized(),
            secondarySubtitle = secondarySubtitle?.normalized(),
            subtitleOffsetMs = subtitleOffsetMs.coerceIn(-60_000L, 60_000L),
            subtitleScale = subtitleScale.coerceIn(0.6f, 1.8f),
            subtitleBrightness = subtitleBrightness.coerceIn(0.35f, 1f),
            speed = speed.coerceIn(0.25f, 4f),
            aspectMode = aspectMode.takeIf { it in SERIES_ASPECT_MODES } ?: "Fit",
        )

    private fun RememberedPlaybackTrack.normalized(): RememberedPlaybackTrack =
        copy(
            language = language?.trim()?.take(MAX_TRACK_FIELD_CHARS)?.takeIf(String::isNotEmpty),
            label = label.trim().take(MAX_TRACK_FIELD_CHARS),
            codec = codec?.trim()?.take(MAX_TRACK_FIELD_CHARS)?.takeIf(String::isNotEmpty),
        )

    private inline fun <reified T : Enum<T>> enumSetting(
        key: String,
        fallback: T,
    ): T =
        settings
            .getStringOrNull(key)
            ?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } }
            ?: fallback

    private companion object {
        const val KEY_VIDEO_CACHE_SIZE = "player.videoCacheSize"
        const val KEY_FRAME_RATE_MATCH = "player.output.frameRateMatch"
        const val KEY_AUDIO_PASSTHROUGH = "player.output.audioPassthrough"
        const val KEY_SMART_CROSS_SERVER_SOURCE = "player.smartCrossServerSource"
        const val KEY_WIFI_QUALITY_CAP = "player.networkQuality.wifi"
        const val KEY_CELLULAR_QUALITY_CAP = "player.networkQuality.cellular"
        const val KEY_AUTO_QUALITY_DOWNGRADE = "player.networkQuality.autoDowngrade"
        const val KEY_QUALITY_LOCKED = "player.networkQuality.locked"
        const val KEY_SERVER_QUALITY_PREFIX = "player.networkQuality.server."
        const val KEY_RESUME_PROMPT = "player.resumePrompt"
        const val KEY_SERIES_PLAYBACK = "player.seriesPlayback.v1"
        const val MAX_SERIES_KEY_CHARS = 256
        const val MAX_TRACK_FIELD_CHARS = 128
        val SERIES_ASPECT_MODES = setOf("Fit", "Fill", "Stretch")
    }
}

internal const val MAX_SERIES_PLAYBACK_PREFERENCES = 32
