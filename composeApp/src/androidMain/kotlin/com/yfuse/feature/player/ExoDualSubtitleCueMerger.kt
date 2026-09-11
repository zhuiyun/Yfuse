package com.yfuse.feature.player

import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@UnstableApi
internal data class ExoSubtitleChannels(
    val primary: CueGroup = CueGroup.EMPTY_TIME_ZERO,
    val secondary: CueGroup = CueGroup.EMPTY_TIME_ZERO,
    val dual: Boolean = false,
)

/** Single subtitles retain authored layout; dual subtitles are measured as separate blocks. */
@UnstableApi
internal class ExoDualSubtitleCueMerger {
    private val mutableChannels = MutableStateFlow(ExoSubtitleChannels())
    val channels = mutableChannels.asStateFlow()
    private var downstream: TextOutput? = null

    fun primaryOutput(output: TextOutput): TextOutput {
        synchronized(this) { downstream = output }
        return TextOutput { cues -> updateAndDispatch { it.copy(primary = cues) } }
    }

    fun secondaryOutput(): TextOutput =
        TextOutput { cues ->
            updateAndDispatch { if (it.dual) it.copy(secondary = cues) else it }
        }

    fun setDual(enabled: Boolean) {
        updateAndDispatch { it.copy(dual = enabled, secondary = CueGroup.EMPTY_TIME_ZERO) }
    }

    fun clearSecondary() {
        updateAndDispatch { it.copy(secondary = CueGroup.EMPTY_TIME_ZERO) }
    }

    private fun updateAndDispatch(update: (ExoSubtitleChannels) -> ExoSubtitleChannels) {
        synchronized(this) {
            val snapshot = update(mutableChannels.value)
            mutableChannels.value = snapshot
            downstream?.onCues(if (snapshot.dual) CueGroup.EMPTY_TIME_ZERO else snapshot.primary)
        }
    }
}
