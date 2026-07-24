package com.yfuse.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.shadow
import kotlinx.coroutines.delay

/** Controls fade out after this long without interaction, while playing. */
private const val AUTO_HIDE_MS = 4_000L

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** The settings panel's three tabs — 字幕·音轨 / 倍速 / 内核. */
private enum class Tab(val label: String) { Subtitle("字幕·音轨"), Speed("倍速"), Engine("内核") }

/**
 * The player chrome, transcribed from the prototype's landscape player: a gradient
 * top bar, a centred transport cluster, a gradient bottom bar with the scrubber and
 * chip row, plus the lock screen, settings panel and episode drawer.
 *
 * Everything shown comes from [state], so ExoPlayer and libmpv get the same controls.
 */
@Composable
fun PlayerControls(
    state: PlaybackState,
    titles: List<String>,
    filled: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
    onSeek: (Long) -> Unit,
    onSelectItem: (Int) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectSubtitle: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onToggleFill: () -> Unit,
    /** System volume, 0f..1f, and its setter — drives the bottom-left level chip. */
    volume: Float = 0f,
    onVolume: (Float) -> Unit = {},
    /** Engine picker rows: label to selected. */
    engineOptions: List<Pair<String, Boolean>> = emptyList(),
    onSelectEngine: (Int) -> Unit = {},
    /** Null when the active engine has no transcode fallback. */
    transcodeLabel: String? = null,
    transcodeActive: Boolean = false,
    onTranscode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var settingsTab by remember { mutableStateOf<Tab?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    // Bumped by every interaction so the auto-hide timer restarts.
    var interactions by remember { mutableIntStateOf(0) }

    fun poke() {
        interactions++
        visible = true
    }

    LaunchedEffect(visible, locked, settingsTab, drawerOpen, state.playing, interactions) {
        if (!visible || !state.playing || settingsTab != null || drawerOpen) return@LaunchedEffect
        delay(AUTO_HIDE_MS)
        visible = false
    }

    Box(modifier.fillMaxSize()) {
        // Tap catcher sits below the controls, so buttons win the gesture.
        Box(
            Modifier.fillMaxSize().noRippleClickable {
                when {
                    settingsTab != null -> settingsTab = null
                    drawerOpen -> drawerOpen = false
                    visible -> visible = false
                    else -> poke()
                }
            },
        )

        state.error?.let { message ->
            PlaybackErrorOverlay(
                message = message,
                onRetry = onRetry,
                onBack = onBack,
            )
            return@Box
        }

        if (locked) {
            LockedOverlay(onUnlock = { locked = false; poke() })
            return@Box
        }

        if (visible) {
            TopBar(
                title = titles.getOrNull(state.currentIndex).orEmpty(),
                subtitle = state.metaLine(),
                filled = filled,
                hasEpisodes = state.itemCount > 1,
                onBack = onBack,
                onLock = { locked = true; visible = true },
                onOpenDrawer = { poke(); drawerOpen = true },
                onToggleFill = { poke(); onToggleFill() },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            TransportRow(
                state = state,
                onPlayPause = { poke(); onPlayPause() },
                onRewind = { poke(); onSeek((state.positionMs - 10_000L).coerceAtLeast(0L)) },
                onForward = { poke(); onSeek(state.positionMs + 10_000L) },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            BottomBar(
                state = state,
                volume = volume,
                onVolume = { poke(); onVolume(it) },
                onSeek = { poke(); onSeek(it) },
                onScrub = { interactions++ },
                onOpenTab = { poke(); settingsTab = it },
                hasEngines = engineOptions.isNotEmpty() || transcodeLabel != null,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        settingsTab?.let { tab ->
            SettingsPanel(
                tab = tab,
                state = state,
                speeds = SPEEDS,
                engineOptions = engineOptions,
                transcodeLabel = transcodeLabel,
                transcodeActive = transcodeActive,
                onTab = { settingsTab = it },
                onSelectSubtitle = { onSelectSubtitle(it); settingsTab = null },
                onSelectAudio = { onSelectAudio(it); settingsTab = null },
                onSpeed = { onSpeed(it); settingsTab = null },
                onSelectEngine = { onSelectEngine(it); settingsTab = null },
                onTranscode = { onTranscode(); settingsTab = null },
                onDismiss = { settingsTab = null },
            )
        }

        if (drawerOpen) {
            EpisodeDrawer(
                titles = titles,
                currentIndex = state.currentIndex,
                onSelect = { onSelectItem(it); drawerOpen = false },
                onDismiss = { drawerOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
private fun PlaybackErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("播放遇到问题", style = sc(17f, 700), color = Color.White)
            Text(
                message,
                style = mr(12f, 400),
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "返回",
                    style = sc(12f, 600),
                    color = Color.White.copy(alpha = 0.82f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .noRippleClickable(onBack)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
                Text(
                    "重试",
                    style = sc(12f, 700),
                    color = Color(0xFF1B2436),
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.92f))
                        .noRippleClickable(onRetry)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** `padding:14px 22px`, `linear-gradient(180deg,rgba(0,0,0,.5),transparent)`. */
@Composable
private fun TopBar(
    title: String,
    subtitle: String,
    filled: Boolean,
    hasEpisodes: Boolean,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onOpenDrawer: () -> Unit,
    onToggleFill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(
                cssLinearGradient(
                    180f,
                    0f to Color.Black.copy(alpha = 0.5f),
                    1f to Color.Transparent,
                ),
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.ChevronLeft,
                contentDescription = "返回",
                tint = Color.White,
                modifier = Modifier.size(16.dp).noRippleClickable(onBack),
            )
            Column {
                Text(
                    title,
                    style = sc(14f, 700),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = mr(10.5f, 400),
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasEpisodes) {
                CircleControl(AppIcons.Menu, "剧集列表", 32.dp, 15.dp, onOpenDrawer)
            }
            CircleControl(
                icon = if (filled) AppIcons.Collapse else AppIcons.Expand,
                description = "画面填充",
                size = 32.dp,
                iconSize = 14.dp,
                onClick = onToggleFill,
            )
            CircleControl(AppIcons.Lock, "锁屏", 32.dp, 14.dp, onLock)
        }
    }
}

/** Centred at `top:44%`, `gap:38px`: 46 / 58 / 46 circles. */
@Composable
private fun TransportRow(
    state: PlaybackState,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleControl(AppIcons.Rewind, "快退 10 秒", 46.dp, 16.dp, onRewind)

            if (state.buffering) {
                Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else {
                // `58px` circle, `rgba(255,255,255,.92)`, `#141A26` glyph.
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(PlayerTokens.playFill)
                        .noRippleClickable(onPlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = if (state.playing) "暂停" else "播放",
                        tint = PlayerTokens.onPlay,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            CircleControl(AppIcons.Forward, "快进 10 秒", 46.dp, 16.dp, onForward)
        }
    }
}

/** `padding:14px 22px 16px`, `linear-gradient(0deg,rgba(0,0,0,.55),transparent)`. */
@Composable
private fun BottomBar(
    state: PlaybackState,
    volume: Float,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    hasEngines: Boolean,
    modifier: Modifier = Modifier,
) {
    // Non-null only while the thumb is held; the engine's position is ignored then
    // so the bar doesn't snap back between ticks.
    var scrubbed by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val fraction = scrubbed ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val shownPosition = scrubbed?.let { (it * duration).toLong() } ?: state.positionMs

    Column(
        modifier
            .fillMaxWidth()
            .background(
                cssLinearGradient(
                    0f,
                    0f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                ),
            )
            .padding(start = 22.dp, end = 22.dp, top = 14.dp, bottom = 16.dp),
    ) {
        // Progress row — `gap:10px`, `400 11px Manrope`, `rgba(255,255,255,.75)`.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(formatTime(shownPosition), style = mr(11f, 400), color = PlayerTokens.timeTextLandscape)
            SeekBar(
                fraction = fraction,
                onScrubTo = { scrubbed = it; onScrub() },
                onCommit = {
                    onSeek((it * duration).toLong())
                    scrubbed = null
                },
                modifier = Modifier.weight(1f),
            )
            Text(formatTime(state.durationMs), style = mr(11f, 400), color = PlayerTokens.timeTextLandscape)
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VolumeChip(volume, onVolume)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.subtitleTracks.isNotEmpty() || state.audioTracks.size > 1) {
                    Chip("字幕·音轨") { onOpenTab(Tab.Subtitle) }
                }
                Chip(speedLabel(state.speed)) { onOpenTab(Tab.Speed) }
                if (hasEngines) {
                    Chip("内核") { onOpenTab(Tab.Engine) }
                }
            }
        }
    }
}

/**
 * `4px` track, `radius:2px`, `rgba(255,255,255,.24)`, filled with
 * `linear-gradient(90deg,#7FA2E8,#A7C0F2)`. Tap to seek, drag to scrub.
 */
@Composable
private fun SeekBar(
    fraction: Float,
    onScrubTo: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableStateOf(1f) }
    var dragFraction by remember { mutableStateOf(0f) }

    Box(
        modifier
            // A 4px bar is unhittable; pad the touch target without moving the bar.
            .padding(vertical = 10.dp)
            .height(4.dp)
            .pointerInput(Unit) {
                width = size.width.toFloat().coerceAtLeast(1f)
                detectTapGestures { offset -> onCommit((offset.x / width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                width = size.width.toFloat().coerceAtLeast(1f)
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / width).coerceIn(0f, 1f)
                        onScrubTo(dragFraction)
                    },
                    onDragEnd = { onCommit(dragFraction) },
                    onDragCancel = { onCommit(dragFraction) },
                ) { change, dragAmount ->
                    change.consume()
                    dragFraction = (dragFraction + dragAmount / width).coerceIn(0f, 1f)
                    onScrubTo(dragFraction)
                }
            }
            .clip(RoundedCornerShape(2.dp))
            .background(PlayerTokens.trackFillLandscape),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(2.dp))
                .background(PlayerTokens.progress),
        )
    }
}

/**
 * `gap:10px`, `rgba(255,255,255,.14)` over `rgba(255,255,255,.22)`, `radius:16px`,
 * `padding:6px 12px`, with a 70×3 level track.
 */
@Composable
private fun VolumeChip(volume: Float, onVolume: (Float) -> Unit) {
    var width by remember { mutableStateOf(1f) }
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(PlayerTokens.chipFill)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Volume, null, tint = Color.White, modifier = Modifier.size(13.dp))
        Box(
            Modifier
                .width(70.dp)
                .padding(vertical = 8.dp)
                .height(3.dp)
                .pointerInput(Unit) {
                    width = size.width.toFloat().coerceAtLeast(1f)
                    detectTapGestures { offset -> onVolume((offset.x / width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) {
                    width = size.width.toFloat().coerceAtLeast(1f)
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onVolume((change.position.x / width).coerceIn(0f, 1f))
                    }
                }
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.28f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(volume.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White),
            )
        }
    }
}

/** `radius:14px`, `padding:6px 12px`, `500 11px Manrope`, `rgba(255,255,255,.9)`. */
@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = mr(11f, 500),
        color = Color.White.copy(alpha = 0.9f),
        maxLines = 1,
        modifier = Modifier
            .clip(GlassShapes.chip)
            .background(PlayerTokens.chipFill)
            .noRippleClickable(onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** `rgba(255,255,255,.16)` circle over a `rgba(255,255,255,.28)` hairline. */
@Composable
private fun CircleControl(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(PlayerTokens.controlFill)
            .noRippleClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/**
 * Lock screen — a 52px circle over `屏幕已锁定` at `gap:14px`, with the
 * `解锁` pill at `right:22px; bottom:40px`.
 */
@Composable
private fun LockedOverlay(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Lock, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text("屏幕已锁定", style = mr(12f, 500), color = Color.White.copy(alpha = 0.57f))
        }

        Text(
            "解锁",
            style = sc(12f, 600),
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.12f))
                .noRippleClickable(onUnlock)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}

/**
 * Settings panel — `right:120px; bottom:70px; width:230px`, `rgba(255,255,255,.92)`,
 * `radius:18px`, `padding:6px 0 12px`, `0 20px 50px -12px rgba(30,40,70,.3)`.
 */
@Composable
private fun SettingsPanel(
    tab: Tab,
    state: PlaybackState,
    speeds: List<Float>,
    engineOptions: List<Pair<String, Boolean>>,
    transcodeLabel: String?,
    transcodeActive: Boolean,
    onTab: (Tab) -> Unit,
    onSelectSubtitle: (String) -> Unit,
    onSelectAudio: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onSelectEngine: (Int) -> Unit,
    onTranscode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tabs = buildList {
        if (state.subtitleTracks.isNotEmpty() || state.audioTracks.isNotEmpty()) add(Tab.Subtitle)
        add(Tab.Speed)
        if (engineOptions.isNotEmpty() || transcodeLabel != null) add(Tab.Engine)
    }
    val shape = RoundedCornerShape(18.dp)

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).noRippleClickable(onDismiss))
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 120.dp, bottom = 70.dp)
                .width(230.dp)
                .shadow(Shadows.playerSheet, shape)
                .clip(shape)
                .background(PlayerTokens.sheetFillLandscape)
                .noRippleClickable { }
                .padding(top = 6.dp, bottom = 12.dp),
        ) {
            // Tab row — `padding:12px 14px 6px`, `gap:14px`, hairline underneath.
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                tabs.forEach { entry ->
                    val active = entry == tab
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            entry.label,
                            style = sc(12.5f, if (active) 700 else 500),
                            color = if (active) Brand.Primary else Color(0xFF8A93A3),
                            modifier = Modifier.noRippleClickable { onTab(entry) },
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(if (active) Brand.Primary else Color.Transparent),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.06f)))

            // `padding:10px 14px 2px; max-height:150px`.
            Column(
                Modifier
                    .heightIn(max = 150.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 2.dp),
            ) {
                when (tab) {
                    Tab.Subtitle -> {
                        if (state.subtitleTracks.isNotEmpty()) {
                            GroupLabel("字幕")
                            OptionRow("关闭", state.subtitleTracks.none { it.selected }) {
                                onSelectSubtitle(EngineTrack.OFF)
                            }
                            state.subtitleTracks.forEach { track ->
                                OptionRow(track.label, track.selected) { onSelectSubtitle(track.id) }
                            }
                        }
                        if (state.audioTracks.isNotEmpty()) {
                            GroupLabel("音轨")
                            state.audioTracks.forEach { track ->
                                OptionRow(track.label, track.selected) { onSelectAudio(track.id) }
                            }
                        }
                    }

                    Tab.Speed -> speeds.forEach { speed ->
                        OptionRow(speedLabel(speed), speed == state.speed) { onSpeed(speed) }
                    }

                    Tab.Engine -> {
                        engineOptions.forEachIndexed { index, (label, selected) ->
                            OptionRow(label, selected) { onSelectEngine(index) }
                        }
                        if (transcodeLabel != null) {
                            OptionRow(transcodeLabel, transcodeActive, onClick = onTranscode)
                        }
                    }
                }
            }
        }
    }
}

/** `600 11px Manrope`, `#8A93A3`, above each group in the 字幕·音轨 tab. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = mr(11f, 600),
        color = Color(0xFF8A93A3),
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

/**
 * `padding:9px 10px`, `radius:10px`; selected is `700 12.5px` `#3D64C9` over
 * `rgba(61,100,201,.1)`, otherwise `500 12.5px` `#151A22`.
 */
@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(GlassShapes.chipSmall)
            .background(if (selected) Brand.Primary.copy(alpha = 0.1f) else Color.Transparent)
            .noRippleClickable(onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = sc(12.5f, if (selected) 700 else 500),
            color = if (selected) Brand.Primary else Color(0xFF151A22),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (selected) {
            Icon(AppIcons.Check, null, tint = Brand.Primary, modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * Episode drawer — `width:190px`, `rgba(18,22,34,.7)` behind a
 * `rgba(255,255,255,.14)` left hairline, `padding:16px 12px`, `gap:8px`.
 */
@Composable
private fun EpisodeDrawer(
    titles: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).noRippleClickable(onDismiss))
    Column(
        modifier
            .fillMaxHeight()
            .width(190.dp)
            .background(PlayerTokens.drawerFillLandscape)
            .noRippleClickable { }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("剧集列表", style = sc(12f, 700), color = Color.White)
            Icon(
                AppIcons.Close,
                contentDescription = "关闭",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(11.dp).noRippleClickable(onDismiss),
            )
        }

        titles.forEachIndexed { index, title ->
            val current = index == currentIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(GlassShapes.chipSmall)
                    .background(
                        if (current) PlayerTokens.episodeActiveFill else PlayerTokens.episodeIdleFill,
                    )
                    .noRippleClickable { onSelect(index) }
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 48×30 thumbnail slot; the queue carries no stills, so it stays a tile.
                Box(
                    Modifier
                        .width(48.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(PlayerTokens.drawerFill),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifEmpty { "第 ${index + 1} 集" },
                        style = sc(10.5f, 600),
                        color = if (current) Color.White else Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (current) "正在播放" else "第 ${index + 1} 集",
                        style = mr(9f, 400),
                        color = if (current) {
                            PlayerTokens.episodeActiveSub
                        } else {
                            Color.White.copy(alpha = 0.4f)
                        },
                        maxLines = 1,
                    )
                }
            }
        }
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
