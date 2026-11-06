package com.yfuse.core.data

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Values exposed by the pinned SenPlayer runtime; independent from YCore's Anime4K modes. */
enum class SenPlayerEnhancementLevel(
    val nativeValue: Int,
    val label: String,
) {
    Off(0, "关闭"),
    Low(1, "低"),
    Medium(2, "中"),
    High(3, "高"),
}

/** Saved intent only. The active session separately reports whether the runtime accepted it. */
data class SenPlayerPictureSettings(
    val upscaling: SenPlayerEnhancementLevel = SenPlayerEnhancementLevel.Off,
    val sdrToHdr: SenPlayerEnhancementLevel = SenPlayerEnhancementLevel.Off,
)

class SenPlayerPicturePreferences(
    private val settings: Settings,
) {
    private val mutableState =
        MutableStateFlow(
            SenPlayerPictureSettings(
                upscaling = readLevel(KEY_UPSCALING),
                sdrToHdr = readLevel(KEY_SDR_TO_HDR),
            ),
        )
    val state: StateFlow<SenPlayerPictureSettings> = mutableState.asStateFlow()

    fun setUpscaling(level: SenPlayerEnhancementLevel) {
        settings.putString(KEY_UPSCALING, level.name)
        mutableState.update { it.copy(upscaling = level) }
    }

    fun setSdrToHdr(level: SenPlayerEnhancementLevel) {
        settings.putString(KEY_SDR_TO_HDR, level.name)
        mutableState.update { it.copy(sdrToHdr = level) }
    }

    private fun readLevel(key: String): SenPlayerEnhancementLevel {
        val stored = runCatching { settings.getStringOrNull(key) }.getOrNull()
        return SenPlayerEnhancementLevel.entries.firstOrNull { it.name == stored } ?: SenPlayerEnhancementLevel.Off
    }

    private companion object {
        const val KEY_UPSCALING = "playback.senplayer.upscaling"
        const val KEY_SDR_TO_HDR = "playback.senplayer.sdr_to_hdr"
    }
}
