package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackFailureRecord
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.PlaybackPerformanceRecord
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
    val audioDelayMs: Long = 0L,
    val subtitleOffsetMs: Long = 0L,
    val subtitleScale: Float = 1f,
    val subtitleBrightness: Float = 1f,
    val subtitlePosition: Float = 0.92f,
    val subtitleStylePreset: String = "Standard",
    val speed: Float = 1f,
    val aspectMode: String = "Fit",
)

@Serializable
private data class StoredSeriesPlaybackPreference(
    val serverId: String,
    val seriesId: String,
    val value: SeriesPlaybackPreference,
)

@Serializable
private data class StoredPlaybackFailureRecord(
    val signature: String,
    val engine: String,
    val count: Int,
    val lastFailureEpochMs: Long,
)

@Serializable
private data class StoredPlaybackPerformanceRecord(
    val signature: String,
    val engine: String,
    val sessions: Int,
    val averageStartupMs: Long,
    val averageRebufferEventsPerMinute: Float,
    val averageDroppedFramesPerMinute: Float,
    val lastObservedEpochMs: Long,
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

    private val _mediaVersionPreference =
        MutableStateFlow(
            MediaVersionPreference.fromStorage(
                settings.getStringOrNull(KEY_MEDIA_VERSION_PREFERENCE),
            ),
        )
    val mediaVersionPreference: StateFlow<MediaVersionPreference> =
        _mediaVersionPreference.asStateFlow()

    fun setMediaVersionPreference(preference: MediaVersionPreference) {
        _mediaVersionPreference.value = preference
        settings.putString(KEY_MEDIA_VERSION_PREFERENCE, preference.storageValue)
    }

    private val _optimizationMode =
        MutableStateFlow(
            enumSetting(KEY_OPTIMIZATION_MODE, PlaybackOptimizationMode.Balanced),
        )
    val optimizationMode: StateFlow<PlaybackOptimizationMode> = _optimizationMode.asStateFlow()

    fun setOptimizationMode(mode: PlaybackOptimizationMode) {
        _optimizationMode.value = mode
        settings.putString(KEY_OPTIMIZATION_MODE, mode.name)
    }

    private val _engineSelection =
        MutableStateFlow(
            enumSetting(KEY_ENGINE_SELECTION, PlaybackEngineSelection.Auto)
                .takeIf { it in PlaybackEngineSelection.selectable }
                ?: PlaybackEngineSelection.Auto,
        )
    val engineSelection: StateFlow<PlaybackEngineSelection> = _engineSelection.asStateFlow()

    fun setEngineSelection(selection: PlaybackEngineSelection) {
        val available =
            selection.takeIf { it in PlaybackEngineSelection.selectable }
                ?: PlaybackEngineSelection.Auto
        _engineSelection.value = available
        settings.putString(KEY_ENGINE_SELECTION, available.name)
    }

    private val _core2TrialEnabled =
        MutableStateFlow(settings.getBoolean(KEY_CORE2_TRIAL_ENABLED, true))
    val core2TrialEnabled: StateFlow<Boolean> = _core2TrialEnabled.asStateFlow()

    fun setCore2TrialEnabled(enabled: Boolean) {
        _core2TrialEnabled.value = enabled
        settings.putBoolean(KEY_CORE2_TRIAL_ENABLED, enabled)
        if (!enabled) setCore2NativeOnlyEnabled(false)
    }

    private val _core2NativeOnlyEnabled =
        MutableStateFlow(
            settings.getBoolean(KEY_CORE2_NATIVE_ONLY_ENABLED, false) && _core2TrialEnabled.value,
        )
    val core2NativeOnlyEnabled: StateFlow<Boolean> = _core2NativeOnlyEnabled.asStateFlow()

    fun setCore2NativeOnlyEnabled(enabled: Boolean) {
        val resolved = enabled && _core2TrialEnabled.value
        _core2NativeOnlyEnabled.value = resolved
        settings.putBoolean(KEY_CORE2_NATIVE_ONLY_ENABLED, resolved)
    }

    internal fun playbackFailureRecords(): List<PlaybackFailureRecord> =
        settings
            .getStringOrNull(KEY_PLAYBACK_FAILURES)
            ?.let { stored ->
                runCatching {
                    json.decodeFromString<List<StoredPlaybackFailureRecord>>(stored)
                }.getOrNull()
            }.orEmpty()
            .takeLast(MAX_PLAYBACK_FAILURE_RECORDS)
            .mapNotNull { stored ->
                val engine = PlayerEngine.entries.firstOrNull { it.name == stored.engine }
                val signature =
                    stored.signature
                        .trim()
                        .take(MAX_PLAYBACK_FAILURE_SIGNATURE_CHARS)
                        .takeIf(String::isNotEmpty)
                if (
                    engine == null ||
                    engine !in PlayerEngine.selectable ||
                    signature == null ||
                    stored.count <= 0 ||
                    stored.lastFailureEpochMs <= 0L
                ) {
                    null
                } else {
                    PlaybackFailureRecord(
                        signature = signature,
                        engine = engine,
                        count = stored.count.coerceAtMost(MAX_PLAYBACK_FAILURE_COUNT),
                        lastFailureEpochMs = stored.lastFailureEpochMs,
                    )
                }
            }

    internal fun storePlaybackFailureRecords(records: List<PlaybackFailureRecord>) {
        val stored =
            records
                .takeLast(MAX_PLAYBACK_FAILURE_RECORDS)
                .mapNotNull { record ->
                    val signature =
                        record.signature
                            .trim()
                            .take(MAX_PLAYBACK_FAILURE_SIGNATURE_CHARS)
                            .takeIf(String::isNotEmpty)
                    if (
                        signature == null ||
                        record.engine !in PlayerEngine.selectable ||
                        record.count <= 0 ||
                        record.lastFailureEpochMs <= 0L
                    ) {
                        null
                    } else {
                        StoredPlaybackFailureRecord(
                            signature = signature,
                            engine = record.engine.name,
                            count = record.count.coerceAtMost(MAX_PLAYBACK_FAILURE_COUNT),
                            lastFailureEpochMs = record.lastFailureEpochMs,
                        )
                    }
                }
        if (stored.isEmpty()) {
            settings.remove(KEY_PLAYBACK_FAILURES)
        } else {
            settings.putString(KEY_PLAYBACK_FAILURES, json.encodeToString(stored))
        }
    }

    internal fun playbackPerformanceRecords(): List<PlaybackPerformanceRecord> =
        settings
            .getStringOrNull(KEY_PLAYBACK_PERFORMANCE)
            ?.let { stored ->
                runCatching {
                    json.decodeFromString<List<StoredPlaybackPerformanceRecord>>(stored)
                }.getOrNull()
            }.orEmpty()
            .takeLast(MAX_PLAYBACK_PERFORMANCE_RECORDS)
            .mapNotNull { stored ->
                val engine = PlayerEngine.entries.firstOrNull { it.name == stored.engine }
                val signature = stored.signature.normalizedPerformanceSignature()
                if (
                    engine == null ||
                    engine !in PlayerEngine.selectable ||
                    signature == null ||
                    stored.sessions <= 0 ||
                    stored.averageStartupMs < 0L ||
                    !stored.averageRebufferEventsPerMinute.isFinite() ||
                    !stored.averageDroppedFramesPerMinute.isFinite() ||
                    stored.lastObservedEpochMs <= 0L
                ) {
                    null
                } else {
                    PlaybackPerformanceRecord(
                        signature = signature,
                        engine = engine,
                        sessions = stored.sessions.coerceAtMost(MAX_PLAYBACK_PERFORMANCE_SESSIONS),
                        averageStartupMs =
                            stored.averageStartupMs.coerceAtMost(MAX_PLAYBACK_STARTUP_MS),
                        averageRebufferEventsPerMinute =
                            stored.averageRebufferEventsPerMinute.coerceIn(
                                0f,
                                MAX_PLAYBACK_RATE_PER_MINUTE,
                            ),
                        averageDroppedFramesPerMinute =
                            stored.averageDroppedFramesPerMinute.coerceIn(
                                0f,
                                MAX_PLAYBACK_RATE_PER_MINUTE,
                            ),
                        lastObservedEpochMs = stored.lastObservedEpochMs,
                    )
                }
            }

    internal fun storePlaybackPerformanceRecords(records: List<PlaybackPerformanceRecord>) {
        val stored =
            records
                .takeLast(MAX_PLAYBACK_PERFORMANCE_RECORDS)
                .mapNotNull { record ->
                    val signature = record.signature.normalizedPerformanceSignature()
                    if (
                        signature == null ||
                        record.engine !in PlayerEngine.selectable ||
                        record.sessions <= 0 ||
                        record.averageStartupMs < 0L ||
                        !record.averageRebufferEventsPerMinute.isFinite() ||
                        !record.averageDroppedFramesPerMinute.isFinite() ||
                        record.lastObservedEpochMs <= 0L
                    ) {
                        null
                    } else {
                        StoredPlaybackPerformanceRecord(
                            signature = signature,
                            engine = record.engine.name,
                            sessions =
                                record.sessions.coerceAtMost(MAX_PLAYBACK_PERFORMANCE_SESSIONS),
                            averageStartupMs =
                                record.averageStartupMs.coerceAtMost(MAX_PLAYBACK_STARTUP_MS),
                            averageRebufferEventsPerMinute =
                                record.averageRebufferEventsPerMinute.coerceIn(
                                    0f,
                                    MAX_PLAYBACK_RATE_PER_MINUTE,
                                ),
                            averageDroppedFramesPerMinute =
                                record.averageDroppedFramesPerMinute.coerceIn(
                                    0f,
                                    MAX_PLAYBACK_RATE_PER_MINUTE,
                                ),
                            lastObservedEpochMs = record.lastObservedEpochMs,
                        )
                    }
                }
        if (stored.isEmpty()) {
            settings.remove(KEY_PLAYBACK_PERFORMANCE)
        } else {
            settings.putString(KEY_PLAYBACK_PERFORMANCE, json.encodeToString(stored))
        }
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

    private val _anonymousQoeSharing =
        MutableStateFlow(settings.getBoolean(KEY_ANONYMOUS_QOE_SHARING, false))
    val anonymousQoeSharing: StateFlow<Boolean> = _anonymousQoeSharing.asStateFlow()

    fun setAnonymousQoeSharing(enabled: Boolean) {
        _anonymousQoeSharing.value = enabled
        settings.putBoolean(KEY_ANONYMOUS_QOE_SHARING, enabled)
        if (!enabled) settings.remove(PLAYBACK_QOE_OUTBOX_KEY)
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
            audioDelayMs = audioDelayMs.coerceIn(-10_000L, 10_000L),
            subtitleOffsetMs = subtitleOffsetMs.coerceIn(-60_000L, 60_000L),
            subtitleScale = subtitleScale.coerceIn(0.6f, 1.8f),
            subtitleBrightness = subtitleBrightness.coerceIn(0.35f, 1f),
            subtitlePosition = subtitlePosition.coerceIn(0.60f, 0.96f),
            subtitleStylePreset =
                subtitleStylePreset.takeIf { stored ->
                    stored in setOf("Standard", "Cinema", "Compact", "Accessible", "Custom")
                } ?: "Standard",
            speed = speed.coerceIn(0.25f, 4f),
            aspectMode = aspectMode.takeIf { it in SERIES_ASPECT_MODES } ?: "Fit",
        )

    private fun RememberedPlaybackTrack.normalized(): RememberedPlaybackTrack =
        copy(
            language = language?.trim()?.take(MAX_TRACK_FIELD_CHARS)?.takeIf(String::isNotEmpty),
            label = label.trim().take(MAX_TRACK_FIELD_CHARS),
            codec = codec?.trim()?.take(MAX_TRACK_FIELD_CHARS)?.takeIf(String::isNotEmpty),
        )

    private fun String.normalizedPerformanceSignature(): String? =
        trim().take(MAX_PLAYBACK_PERFORMANCE_SIGNATURE_CHARS).takeIf(String::isNotEmpty)

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
        const val KEY_MEDIA_VERSION_PREFERENCE = "player.mediaVersionPreference"
        const val KEY_OPTIMIZATION_MODE = "player.optimizationMode"
        const val KEY_ENGINE_SELECTION = "player.ycore.engineSelection"
        const val KEY_CORE2_TRIAL_ENABLED = "player.ycore2.trialEnabled"
        const val KEY_CORE2_NATIVE_ONLY_ENABLED = "player.ycore2.nativeOnlyEnabled"
        const val KEY_PLAYBACK_FAILURES = "player.ycore.failures.v1"
        const val KEY_PLAYBACK_PERFORMANCE = "player.ycore.performance.v1"
        const val KEY_SMART_CROSS_SERVER_SOURCE = "player.smartCrossServerSource"
        const val KEY_ANONYMOUS_QOE_SHARING = "player.ycore.qoeSharing"
        const val KEY_RESUME_PROMPT = "player.resumePrompt"
        const val KEY_SERIES_PLAYBACK = "player.seriesPlayback.v1"
        const val MAX_SERIES_KEY_CHARS = 256
        const val MAX_TRACK_FIELD_CHARS = 128
        val SERIES_ASPECT_MODES = setOf("Fit", "Fill", "Stretch")
    }
}

internal const val PLAYBACK_QOE_OUTBOX_KEY = "player.ycore.qoe.outbox.v1"

internal const val MAX_SERIES_PLAYBACK_PREFERENCES = 32
internal const val MAX_PLAYBACK_FAILURE_RECORDS = 96
internal const val MAX_PLAYBACK_PERFORMANCE_RECORDS = 96
private const val MAX_PLAYBACK_FAILURE_SIGNATURE_CHARS = 256
private const val MAX_PLAYBACK_FAILURE_COUNT = 100
private const val MAX_PLAYBACK_PERFORMANCE_SIGNATURE_CHARS = 320
private const val MAX_PLAYBACK_PERFORMANCE_SESSIONS = 1_000
private const val MAX_PLAYBACK_STARTUP_MS = 120_000L
private const val MAX_PLAYBACK_RATE_PER_MINUTE = 10_000f
