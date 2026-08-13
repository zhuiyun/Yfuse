package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.model.PlaybackQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

/** Playback settings that are independent from appearance and decoder selection. */
class PlaybackPreferences(
    private val settings: Settings,
) {
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
        const val KEY_SMART_CROSS_SERVER_SOURCE = "player.smartCrossServerSource"
        const val KEY_WIFI_QUALITY_CAP = "player.networkQuality.wifi"
        const val KEY_CELLULAR_QUALITY_CAP = "player.networkQuality.cellular"
        const val KEY_AUTO_QUALITY_DOWNGRADE = "player.networkQuality.autoDowngrade"
        const val KEY_QUALITY_LOCKED = "player.networkQuality.locked"
        const val KEY_SERVER_QUALITY_PREFIX = "player.networkQuality.server."
        const val KEY_RESUME_PROMPT = "player.resumePrompt"
    }
}
