package com.yfuse.core.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DanmakuDisplayArea(
    val label: String,
    val fraction: Float,
) {
    Quarter("顶部 1/4", 0.25f),
    Half("顶部 1/2", 0.5f),
    ThreeQuarters("顶部 3/4", 0.75f),
    Full("全屏", 1f),
}

enum class DanmakuFontSize(
    val label: String,
    val scale: Float,
) {
    Small("小", 0.82f),
    Standard("标准", 1f),
    Large("大", 1.2f),
    ExtraLarge("特大", 1.4f),
}

enum class DanmakuSpeed(
    val label: String,
    val durationMs: Long,
) {
    Slow("慢", 12_000L),
    Standard("标准", 8_000L),
    Fast("快", 5_500L),
}

enum class DanmakuOpacity(
    val label: String,
    val alpha: Float,
) {
    Low("50%", 0.5f),
    Standard("75%", 0.75f),
    High("100%", 1f),
}

/** Persistent source and rendering preferences shared by Profile and the player activity. */
class DanmakuPreferences(private val settings: Settings) {

    private companion object {
        const val KEY_URL_TEMPLATE = "danmaku.urlTemplate"
        const val KEY_ENABLED = "danmaku.enabled"
        const val KEY_DISPLAY_AREA = "danmaku.displayArea"
        const val KEY_FONT_SIZE = "danmaku.fontSize"
        const val KEY_SPEED = "danmaku.speed"
        const val KEY_OPACITY = "danmaku.opacity"
    }

    private val _urlTemplate = MutableStateFlow(
        settings.getStringOrNull(KEY_URL_TEMPLATE).orEmpty(),
    )
    val urlTemplate: StateFlow<String> = _urlTemplate.asStateFlow()

    private val _enabled = MutableStateFlow(settings.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _displayArea = MutableStateFlow(
        load(KEY_DISPLAY_AREA, DanmakuDisplayArea.entries, DanmakuDisplayArea.Half),
    )
    val displayArea: StateFlow<DanmakuDisplayArea> = _displayArea.asStateFlow()

    private val _fontSize = MutableStateFlow(
        load(KEY_FONT_SIZE, DanmakuFontSize.entries, DanmakuFontSize.Standard),
    )
    val fontSize: StateFlow<DanmakuFontSize> = _fontSize.asStateFlow()

    private val _speed = MutableStateFlow(
        load(KEY_SPEED, DanmakuSpeed.entries, DanmakuSpeed.Standard),
    )
    val speed: StateFlow<DanmakuSpeed> = _speed.asStateFlow()

    private val _opacity = MutableStateFlow(
        load(KEY_OPACITY, DanmakuOpacity.entries, DanmakuOpacity.Standard),
    )
    val opacity: StateFlow<DanmakuOpacity> = _opacity.asStateFlow()

    fun setUrlTemplate(value: String) {
        val normalized = value.trim()
        _urlTemplate.value = normalized
        settings.putString(KEY_URL_TEMPLATE, normalized)
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        settings.putBoolean(KEY_ENABLED, enabled)
    }

    fun setDisplayArea(area: DanmakuDisplayArea) {
        _displayArea.value = area
        settings.putString(KEY_DISPLAY_AREA, area.name)
    }

    fun setFontSize(size: DanmakuFontSize) {
        _fontSize.value = size
        settings.putString(KEY_FONT_SIZE, size.name)
    }

    fun setSpeed(speed: DanmakuSpeed) {
        _speed.value = speed
        settings.putString(KEY_SPEED, speed.name)
    }

    fun setOpacity(opacity: DanmakuOpacity) {
        _opacity.value = opacity
        settings.putString(KEY_OPACITY, opacity.name)
    }

    private fun <T : Enum<T>> load(key: String, values: List<T>, fallback: T): T {
        val stored = settings.getStringOrNull(key) ?: return fallback
        return values.firstOrNull { it.name == stored } ?: fallback
    }
}
