from pathlib import Path
root=Path('composeApp/src/androidMain/kotlin/com/yfuse/feature/player')
p=root/'PlayerRoot.kt'
s=p.read_text(encoding='utf-8')
start=s.index('    // 氛围光. Live frames')
end=s.index('\n    Box(',start)
block=s[start:end]
block=block.replace('    val ambientPowerLimited = runtimeEnvironment.pressure != PlaybackResourcePressure.Normal ||\n        resolvedOptimization.mode == com.yfuse.core.playback.PlaybackOptimizationMode.PowerSaver\n','')
imports='''package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.PlaybackPreferences
'''
# Pull exact project symbol imports from original to avoid assuming package names.
names=['LocalAccessibilityOptions','rememberDominantColor','AmbientLight','rememberAmbientLight','toneAmbientLight','PlaybackOutputReadiness']
for line in s.splitlines():
    if line.startswith('import ') and line.rsplit('.',1)[-1] in names: imports+=line+'\n'
header='''
internal data class PlayerAmbientBinding(
    val enabled: Boolean,
    val sampler: AmbientFrameSampler,
    val videoSize: IntSize,
    val light: AmbientLight?,
    val onContainerSize: (IntSize) -> Unit,
    val onChromeVisible: (Boolean) -> Unit,
)

/** Owns ambient sampling, route visibility and pressure fallback for one player composition. */
@Composable
internal fun rememberPlayerAmbient(
    playbackPreferences: PlaybackPreferences,
    engine: VideoEngine,
    currentItem: PlayerMediaItem?,
    state: PlaybackState,
    scaleMode: VideoScaleMode,
    inPictureInPicture: Boolean,
    ambientPowerLimited: Boolean,
): PlayerAmbientBinding {
'''
footer='''
    return PlayerAmbientBinding(ambientLightEnabled, ambientSampler, ambientVideoSize, ambientLight,
        onContainerSize = { ambientContainer = it }, onChromeVisible = { ambientChromeVisible = it })
}
'''
(root/'PlayerAmbientBinding.kt').write_text(imports+header+block+footer,encoding='utf-8')
replacement='''    val ambient = rememberPlayerAmbient(
        playbackPreferences, engine, currentItem, state, scaleMode, inPictureInPicture,
        ambientPowerLimited = runtimeEnvironment.pressure != PlaybackResourcePressure.Normal ||
            resolvedOptimization.mode == com.yfuse.core.playback.PlaybackOptimizationMode.PowerSaver,
    )'''
s=s[:start]+replacement+s[end:]
s=s.replace('ambientContainer = coordinates.size','ambient.onContainerSize(coordinates.size)')
s=s.replace('ambientSampler = ambientSampler','ambientSampler = ambient.sampler')
s=s.replace('light = ambientLight,','light = ambient.light,').replace('sampler = ambientSampler,','sampler = ambient.sampler,').replace('videoSize = ambientVideoSize,','videoSize = ambient.videoSize,')
s=s.replace('ambientLight = ambientLight.takeIf { ambientLightEnabled }','ambientLight = ambient.light.takeIf { ambient.enabled }').replace('ambientLightEnabled = ambientLightEnabled','ambientLightEnabled = ambient.enabled').replace('setAmbientLight(!ambientLightEnabled)','setAmbientLight(!ambient.enabled)').replace('onAmbientChromeVisibleChange = { ambientChromeVisible = it }','onAmbientChromeVisibleChange = ambient.onChromeVisible')
# Remove imports only if symbol is now absent from body.
for line in list(s.splitlines()):
    if line.startswith('import '):
        name=line.rsplit('.',1)[-1]
        if name in names+['lerp','IntSize'] and s.count(name)==1: s=s.replace(line+'\n','')
p.write_text(s,encoding='utf-8')
