package com.yfuse.feature.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.yfuse.core.designsystem.LiftScrub
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.Episode
import com.yfuse.core.model.TrickplayInfo
import com.yfuse.core.network.EmbyStream
import com.yfuse.feature.player.TrickplayImage
import com.yfuse.feature.player.TrickplayStoryboard
import com.yfuse.feature.player.TrickplayStoryboardFrame
import com.yfuse.feature.player.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 按住拖看 (6) on the episode rail — YouTube's thumbnail preview. A held card lifts into its
 * 浮起菜单 (5.1); while the same finger slides across the lifted card, the card shows that
 * episode's trickplay frame for how far across it is ([LiftScrub]).
 *
 * The season's list carries no trickplay — that would be a request per episode — so an episode's
 * is fetched the first time its card lifts under a finger and kept for as long as the page is, a
 * server with none included: one request per episode, however often it is held. A request that
 * fails is forgotten, so the next lift asks again. Until the answer arrives, or when there are no
 * frames, the card keeps its artwork and nothing scrubs.
 */
@Stable
internal class EpisodeTrickplay(
    private val scope: CoroutineScope,
    private val load: suspend (serverId: String, episode: Episode) -> Result<TrickplayStoryboard?>,
) {
    /** Answers by server and episode, a server with no frames as null; absent while loading or never asked. */
    private var boards by mutableStateOf(emptyMap<String, TrickplayStoryboard?>())

    /** Asked for and not failed: loading, or answered. */
    private val asked = mutableSetOf<String>()

    /** What [episode]'s lifted card scrubs through on [serverId]. Building it fetches nothing. */
    fun scrub(
        serverId: String,
        episode: Episode,
    ): LiftScrub = EpisodeScrub("$serverId/${episode.id}", serverId, episode)

    private fun request(
        key: String,
        serverId: String,
        episode: Episode,
    ) {
        if (!asked.add(key)) return
        scope.launch {
            load(serverId, episode)
                .onSuccess { board -> boards = boards + (key to board) }
                .onFailure { failure ->
                    asked -= key
                    AppLog.warning(
                        category = "feature.detail",
                        event = "episode_trickplay_failed",
                        message = "A held episode's trickplay could not be loaded",
                        throwable = failure,
                        attributes = mapOf("itemId" to episode.id),
                    )
                }
        }
    }

    private inner class EpisodeScrub(
        private val key: String,
        private val serverId: String,
        private val episode: Episode,
    ) : LiftScrub {
        private val board: TrickplayStoryboard? get() = boards[key]

        override val frameCount: Int get() = board?.let(::storyboardFrameCount) ?: 0

        override fun prepare() = request(key, serverId, episode)

        override fun label(index: Int): String = board?.let { formatTime(storyboardFramePosition(it, index)) }.orEmpty()

        @Composable
        override fun Frame(
            index: Int,
            modifier: Modifier,
        ) {
            val shown = board ?: return
            val position = storyboardFramePosition(shown, index)
            TrickplayImage(
                storyboard = shown,
                frame = shown.frameAt(position),
                description = "${formatTime(position)} 预览",
                modifier = modifier.coverCrop(shown.width, shown.height),
            )
        }
    }
}

/**
 * [info] as the player's storyboard, built the way the player builds the one it fetches late: the
 * provider's own frame URLs when it names them, Jellyfin's tile sheets otherwise.
 */
internal fun episodeStoryboard(
    info: TrickplayInfo,
    baseUrl: String,
    token: String,
    itemId: String,
    mediaSourceId: String,
): TrickplayStoryboard =
    TrickplayStoryboard(
        urlPattern =
            info.urlPattern
                ?: info.frames.firstOrNull()?.url
                ?: EmbyStream.trickplayTilePattern(
                    baseUrl = baseUrl,
                    itemId = itemId,
                    mediaSourceId = mediaSourceId,
                    width = info.width,
                    token = token,
                ),
        width = info.width,
        height = info.height,
        tileColumns = info.tileColumns,
        tileRows = info.tileRows,
        intervalMs = info.intervalMs,
        thumbnailCount = info.thumbnailCount,
        urlIndexMultiplier = info.urlIndexMultiplier,
        frames = info.frames.map { TrickplayStoryboardFrame(it.positionMs, it.url) },
    )

/**
 * How many frames [board] can show: Emby's listed ones, or the count across the tile sheets. None
 * when it could not draw one — no size, no interval, a grid [TrickplayImage] would refuse.
 */
internal fun storyboardFrameCount(board: TrickplayStoryboard): Int =
    when {
        board.width <= 0 || board.height <= 0 -> 0
        board.frames.isNotEmpty() -> board.frames.size
        board.intervalMs <= 0L || board.tileColumns !in 1..256 || board.tileRows !in 1..256 -> 0
        else -> board.thumbnailCount.coerceAtLeast(0)
    }

/** Where frame [index] of [board] is in the episode, in milliseconds. */
internal fun storyboardFramePosition(
    board: TrickplayStoryboard,
    index: Int,
): Long =
    if (board.frames.isNotEmpty()) {
        board.frames[index.coerceIn(0, board.frames.lastIndex)].positionMs
    } else {
        index.coerceAtLeast(0) * board.intervalMs.coerceAtLeast(1L)
    }

/** However odd a frame's stated shape, it is never drawn more than this many times its box on a side. */
private const val FRAME_MAX_OVERHANG = 4L

/**
 * The size a [frameWidth] × [frameHeight] frame is drawn at to cover a [boxWidth] × [boxHeight]
 * box keeping its shape: one side fits and the other overhangs, to be cut off. A frame of no size
 * is drawn at the box's.
 */
internal fun frameCover(
    boxWidth: Int,
    boxHeight: Int,
    frameWidth: Int,
    frameHeight: Int,
): Pair<Int, Int> {
    if (boxWidth <= 0 || boxHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
        return boxWidth.coerceAtLeast(0) to boxHeight.coerceAtLeast(0)
    }
    return if (boxWidth.toLong() * frameHeight >= boxHeight.toLong() * frameWidth) {
        // A box wider than the frame: the widths match and the frame overhangs top and bottom.
        val height = boxWidth.toLong() * frameHeight / frameWidth
        boxWidth to height.coerceIn(boxHeight.toLong(), boxHeight * FRAME_MAX_OVERHANG).toInt()
    } else {
        val width = boxHeight.toLong() * frameWidth / frameHeight
        width.coerceIn(boxWidth.toLong(), boxWidth * FRAME_MAX_OVERHANG).toInt() to boxHeight
    }
}

/**
 * Fills the box with a [frameWidth] × [frameHeight] frame without stretching it — the lifted card
 * is not quite a frame's shape — cutting off what overhangs, the way the card crops its artwork.
 */
private fun Modifier.coverCrop(
    frameWidth: Int,
    frameHeight: Int,
): Modifier =
    clipToBounds().layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        } else {
            val boxWidth = constraints.maxWidth
            val boxHeight = constraints.maxHeight
            val (width, height) = frameCover(boxWidth, boxHeight, frameWidth, frameHeight)
            val placeable = measurable.measure(Constraints.fixed(width, height))
            layout(boxWidth, boxHeight) {
                placeable.place((boxWidth - width) / 2, (boxHeight - height) / 2)
            }
        }
    }
