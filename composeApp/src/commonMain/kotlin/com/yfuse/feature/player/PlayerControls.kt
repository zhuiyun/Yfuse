package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalGlass
import com.yfuse.core.designsystem.glass
import kotlinx.coroutines.delay

/** Controls fade out after this long without interaction, while playing. */
private const val AUTO_HIDE_MS = 4_000L

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Which picker panel is open, if any. */
private enum class Sheet { None, Audio, Subtitle, Episodes, Speed }

/**
 * The glass control layer, drawn over whichever engine is playing. Everything
 * it shows comes from [state], so ExoPlayer and libmpv get the same controls.
 *
 * [titles] is the queue's static metadata — the engine only reports which entry
 * is current, not what it is called.
 */
@Composable
fun PlayerControls(
    state: PlaybackState,
    titles: List<String>,
    filled: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectItem: (Int) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onToggleFill: () -> Unit,
    modifier: Modifier = Modifier,
    topBarExtras: @Composable RowScope.() -> Unit = {},
) {
    var visible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(Sheet.None) }
    // Bumped by every interaction so the auto-hide timer restarts.
    var interactions by remember { mutableIntStateOf(0) }

    fun poke() {
        interactions++
        visible = true
    }

    LaunchedEffect(visible, locked, sheet, state.playing, interactions) {
        if (!visible || !state.playing || sheet != Sheet.None) return@LaunchedEffect
        delay(AUTO_HIDE_MS)
        visible = false
    }

    Box(modifier.fillMaxSize()) {
        // Tap catcher sits below the controls, so buttons win the gesture.
        Box(
            Modifier
                .fillMaxSize()
                .noRippleClickable {
                    if (sheet != Sheet.None) sheet = Sheet.None
                    else if (visible) visible = false
                    else poke()
                },
        )

        if (locked) {
            if (visible) {
                GlassIconButton(
                    icon = Icons.Rounded.Lock,
                    description = "解除锁定",
                    onClick = { locked = false; poke() },
                    modifier = Modifier.align(Alignment.CenterStart).padding(20.dp),
                )
            }
            return@Box
        }

        if (visible) {
            TopBar(
                state = state,
                title = titles.getOrNull(state.currentIndex).orEmpty(),
                onBack = onBack,
                onLock = { locked = true; visible = true },
                extras = topBarExtras,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            TransportRow(
                state = state,
                onPlayPause = { poke(); onPlayPause() },
                onPrevious = { poke(); onSelectItem(state.currentIndex - 1) },
                onNext = { poke(); onSelectItem(state.currentIndex + 1) },
                modifier = Modifier.align(Alignment.Center),
            )

            BottomPanel(
                state = state,
                filled = filled,
                onSeek = { poke(); onSeek(it) },
                onScrub = { interactions++ },
                onOpenSheet = { sheet = it },
                onToggleFill = { poke(); onToggleFill() },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (sheet != Sheet.None) {
            SidePanel(
                title = when (sheet) {
                    Sheet.Audio -> "音轨"
                    Sheet.Subtitle -> "字幕"
                    Sheet.Episodes -> "剧集列表"
                    else -> "倍速"
                },
                onDismiss = { sheet = Sheet.None },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                when (sheet) {
                    Sheet.Audio -> TrackRows(state.audioTracks, allowOff = false) {
                        onSelectAudio(it); sheet = Sheet.None
                    }

                    Sheet.Subtitle -> TrackRows(state.subtitleTracks, allowOff = true) {
                        onSelectSubtitle(it); sheet = Sheet.None
                    }

                    Sheet.Episodes -> EpisodeRows(titles, state.currentIndex) {
                        onSelectItem(it); sheet = Sheet.None
                    }

                    else -> SpeedRows(state.speed) { onSpeed(it); sheet = Sheet.None }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    state: PlaybackState,
    title: String,
    onBack: () -> Unit,
    onLock: () -> Unit,
    extras: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlass.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack)

        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = glass.onGlass,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = state.metaLine()
            if (meta.isNotEmpty()) {
                Text(meta, color = glass.onGlassMuted, style = MaterialTheme.typography.labelMedium)
            }
        }

        extras()
        GlassIconButton(Icons.Rounded.LockOpen, "锁屏", onLock)
    }
}

@Composable
private fun TransportRow(
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(
            icon = Icons.Rounded.SkipPrevious,
            description = "上一集",
            onClick = onPrevious,
            enabled = state.hasPrevious,
            size = 52.dp,
        )

        if (state.buffering) {
            Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            GlassIconButton(
                icon = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                description = if (state.playing) "暂停" else "播放",
                onClick = onPlayPause,
                size = 68.dp,
                strong = true,
            )
        }

        GlassIconButton(
            icon = Icons.Rounded.SkipNext,
            description = "下一集",
            onClick = onNext,
            enabled = state.hasNext,
            size = 52.dp,
        )
    }
}

@Composable
private fun BottomPanel(
    state: PlaybackState,
    filled: Boolean,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit,
    onOpenSheet: (Sheet) -> Unit,
    onToggleFill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlass.current
    val accent = MaterialTheme.colorScheme.primary
    // Non-null only while the thumb is held; the engine's position is ignored
    // then so the thumb doesn't snap back between ticks.
    var scrubbed by remember { mutableStateOf<Float?>(null) }

    val duration = state.durationMs.coerceAtLeast(1L)
    val fraction = scrubbed ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val shownPosition = scrubbed?.let { (it * duration).toLong() } ?: state.positionMs

    Column(
        modifier
            .fillMaxWidth()
            .padding(16.dp)
            .glass(GlassShapes.panel, strong = true)
            .noRippleClickable { }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatTime(shownPosition),
                color = glass.onGlass,
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = fraction,
                onValueChange = { scrubbed = it; onScrub() },
                onValueChangeFinished = {
                    scrubbed?.let { onSeek((it * duration).toLong()) }
                    scrubbed = null
                },
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = glass.borderStrong,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(
                formatTime(state.durationMs),
                color = glass.onGlassMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassPill("CC", enabled = state.subtitleTracks.isNotEmpty()) {
                onOpenSheet(Sheet.Subtitle)
            }
            GlassPill("音轨", enabled = state.audioTracks.size > 1) {
                onOpenSheet(Sheet.Audio)
            }
            GlassPill("剧集列表", enabled = state.itemCount > 1) {
                onOpenSheet(Sheet.Episodes)
            }
            GlassIconButton(
                icon = if (filled) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                description = "全屏",
                onClick = onToggleFill,
                size = 34.dp,
            )
            GlassPill(speedLabel(state.speed)) { onOpenSheet(Sheet.Speed) }

            Spacer(Modifier.weight(1f))

            if (state.hasNext && state.durationMs > 0) {
                Text(
                    "下一集 ${formatTime(state.remainingMs)} 后自动播放",
                    color = glass.onGlassMuted,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Right-anchored picker panel — landscape has height to spare, not width. */
@Composable
private fun SidePanel(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val glass = LocalGlass.current
    Box(Modifier.fillMaxSize().background(glass.scrim.copy(alpha = 0.4f)).noRippleClickable(onDismiss))
    Column(
        modifier
            .fillMaxHeight()
            .widthIn(min = 240.dp, max = 340.dp)
            .padding(12.dp)
            .glass(GlassShapes.panel, strong = true)
            .noRippleClickable { }
            .padding(16.dp),
    ) {
        Text(
            title,
            color = glass.onGlass,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun TrackRows(tracks: List<EngineTrack>, allowOff: Boolean, onSelect: (String) -> Unit) {
    LazyColumn {
        if (allowOff) {
            item {
                PickerRow(
                    label = "关闭",
                    selected = tracks.none { it.selected },
                    onClick = { onSelect(EngineTrack.OFF) },
                )
            }
        }
        itemsIndexed(tracks) { _, track ->
            PickerRow(
                label = track.label,
                trailing = track.language,
                selected = track.selected,
                onClick = { onSelect(track.id) },
            )
        }
    }
}

@Composable
private fun EpisodeRows(titles: List<String>, currentIndex: Int, onSelect: (Int) -> Unit) {
    LazyColumn {
        itemsIndexed(titles) { index, title ->
            PickerRow(
                label = title.ifEmpty { "第 ${index + 1} 集" },
                selected = index == currentIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun SpeedRows(current: Float, onSelect: (Float) -> Unit) {
    Column {
        SPEEDS.forEach { speed ->
            PickerRow(
                label = speedLabel(speed),
                selected = speed == current,
                onClick = { onSelect(speed) },
            )
        }
    }
}

@Composable
private fun PickerRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val glass = LocalGlass.current
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) accent else glass.onGlass,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(trailing, color = glass.onGlassMuted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(8.dp))
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 38.dp,
    strong: Boolean = false,
) {
    val glass = LocalGlass.current
    Box(
        modifier
            .size(size)
            .glass(GlassShapes.pill, strong = strong)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) glass.onGlass else glass.onGlassMuted.copy(alpha = 0.4f),
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun GlassPill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val glass = LocalGlass.current
    Box(
        Modifier
            .glass(GlassShapes.pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (enabled) glass.onGlass else glass.onGlassMuted.copy(alpha = 0.4f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Taps on the overlay shouldn't flash a ripple over the picture. */
@Composable
private fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

/** "1080P · 音轨 2 · 字幕 3" under the title. */
private fun PlaybackState.metaLine(): String = listOfNotNull(
    resolutionLabel(videoHeight),
    audioTracks.size.takeIf { it > 0 }?.let { "音轨 $it" },
    subtitleTracks.size.takeIf { it > 0 }?.let { "字幕 $it" },
).joinToString(" · ")

private fun resolutionLabel(height: Int): String? = when {
    height <= 0 -> null
    height >= 2000 -> "4K"
    height >= 1400 -> "2K"
    height >= 1000 -> "1080P"
    height >= 700 -> "720P"
    else -> "${height}P"
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}
