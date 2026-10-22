package com.yfuse.feature.player

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.liveStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.yfuse.core.designsystem.ThemeText as Text

/** One line the player tells the viewer in passing. [id] tells two equal lines apart. */
internal data class PlayerNotice(
    val text: String,
    val id: Long,
    val longer: Boolean,
)

/** The line on show, and how many have been issued, so every new one gets its own id. */
internal data class PlayerNoticeSlot(
    val notice: PlayerNotice? = null,
    val issued: Long = 0L,
)

/**
 * The player's own notice line, for what used to go out as a system toast: an engine switch, a
 * recovery, a refusal. A toast is drawn by the system at the bottom of the screen, over the
 * subtitles and outside the player's layout and styling, queues behind the one before it and
 * outlives the player. A notice is drawn by the player below its top bar, is replaced by the next
 * one, reaches a screen reader politely, and goes with the player.
 */
internal object PlayerNotices {
    private val mutableSlot = MutableStateFlow(PlayerNoticeSlot())
    val slot: StateFlow<PlayerNoticeSlot> = mutableSlot.asStateFlow()

    /** [longer] keeps a line that has to be read, not glanced at, up for longer. */
    fun show(
        text: String,
        longer: Boolean = text.length > NOTICE_GLANCE_CHARS,
    ) {
        if (text.isBlank()) return
        mutableSlot.update { current ->
            val id = current.issued + 1L
            PlayerNoticeSlot(PlayerNotice(text, id, longer), id)
        }
    }

    fun dismiss(id: Long) {
        mutableSlot.update { current -> if (current.notice?.id == id) current.copy(notice = null) else current }
    }

    /** The player has gone: nothing it said may show up in the next one. */
    fun clear() {
        mutableSlot.update { current -> current.copy(notice = null) }
    }
}

/** Up to this many characters a line is glanced at; longer ones stay up for reading. */
private const val NOTICE_GLANCE_CHARS = 12
private const val NOTICE_SHORT_MS = 2_500L
private const val NOTICE_LONG_MS = 4_500L

/** [PlayerNotices], drawn: a pill that comes in from the top and leaves on its own. */
@Composable
internal fun PlayerNoticeLine(modifier: Modifier = Modifier) {
    val slot by PlayerNotices.slot.collectAsState()
    val notice = slot.notice
    // Kept while a line leaves, so it fades out with its words rather than as an empty pill.
    val shown = remember { arrayOfNulls<PlayerNotice>(1) }
    if (notice != null) shown[0] = notice
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        val original = if (current.longer) NOTICE_LONG_MS else NOTICE_SHORT_MS
        val timeout =
            accessibilityManager?.calculateRecommendedTimeoutMillis(
                originalTimeoutMillis = original,
                containsIcons = false,
                containsText = true,
                containsControls = false,
            ) ?: original
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        delay(timeout)
        PlayerNotices.dismiss(current.id)
    }
    DisposableEffect(Unit) { onDispose { PlayerNotices.clear() } }
    ChromeVisibility(visible = notice != null, edge = ChromeEdge.Top, modifier = modifier) {
        Text(
            shown[0]?.text.orEmpty(),
            style = AppTypography.body.strong,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .liveStatus()
                    .glass(
                        shape = AppShapes.pill,
                        fill = Color.Black.copy(alpha = 0.56f),
                        border = Color.White.copy(alpha = 0.24f),
                    ).padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}
