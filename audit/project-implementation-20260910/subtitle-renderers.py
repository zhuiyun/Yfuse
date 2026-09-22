from pathlib import Path
root=Path('composeApp/src/androidMain/kotlin/com/yfuse/feature/player')
(root/'ExoDualSubtitleCueMerger.kt').write_text('''package com.yfuse.feature.player

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

    fun secondaryOutput(): TextOutput = TextOutput { cues ->
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
''',encoding='utf-8')
p=root/'ExoVideoEngine.kt'
s=p.read_text(encoding='utf-8').replace('private val dualSubtitleCueMerger = ExoDualSubtitleCueMerger()', 'private val dualSubtitleCueMerger = ExoDualSubtitleCueMerger()\n    internal val subtitleChannels get() = dualSubtitleCueMerger.channels')
p.write_text(s,encoding='utf-8')
p=root/'ExoSecondarySubtitleController.kt'
s=p.read_text(encoding='utf-8').replace('        enabled = true\n', '        if (!enabled || desiredTrack != identity) cueMerger.setDual(true)\n        enabled = true\n')
# compare identity before overwriting, and don't clear live cues on redundant restoration
s=s.replace('        desiredTrack = identity\n        if (!enabled || desiredTrack != identity) cueMerger.setDual(true)', '        if (!enabled || desiredTrack != identity) cueMerger.setDual(true)\n        desiredTrack = identity')
s=s.replace('        enabled = false\n', '        enabled = false\n        cueMerger.setDual(false)\n')
s=s.replace('        player.release()\n        cueMerger.clearSecondary()', '        player.release()\n        cueMerger.setDual(false)')
p.write_text(s,encoding='utf-8')
p=root/'ExoSurface.kt'
s=p.read_text(encoding='utf-8').replace('import android.view.SurfaceView', 'import android.view.SurfaceView\nimport android.view.View\nimport androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.fillMaxSize\nimport androidx.compose.runtime.collectAsState\nimport androidx.compose.runtime.getValue')
s=s.replace('    subtitleScale: Float,','    subtitleScale: Float,\n    secondarySubtitleScale: Float = subtitleScale,')
s=s.replace('    AndroidView(\n', '    val channels by engine.subtitleChannels.collectAsState()\n    Box(modifier) {\n    AndroidView(\n',1)
s=s.replace('            view.subtitleView?.apply {', '            view.subtitleView?.apply {\n                visibility = if (channels.dual) View.INVISIBLE else View.VISIBLE')
s=s.replace('        modifier = modifier,\n    )\n}', '''        modifier = Modifier.fillMaxSize(),
    )
    if (channels.dual) {
        val appearance = subtitleAppearance.withBrightness(subtitleBrightness)
        BottomSubtitleStack(subtitlePosition,
            primary = { ExoSubtitleBlock(channels.primary.cues, subtitleScale, appearance) },
            secondary = { ExoSubtitleBlock(channels.secondary.cues, secondarySubtitleScale, appearance) },
        )
    }
    }
}''')
p.write_text(s,encoding='utf-8')
