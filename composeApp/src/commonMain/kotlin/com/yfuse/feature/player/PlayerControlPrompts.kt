package com.yfuse.feature.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.tv.player.TvPlayerChromeBridge
import com.yfuse.tv.player.TvPlayerPrompt
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * The pills that offer the next move on the timeline — an automatic skip's countdown, 跳过片头 /
 * 跳过片尾 — and, for a remote, which prompt OK acts on while the controls are down.
 */
@Composable
internal fun BoxScope.PlayerSkipPrompts(
    chrome: PlayerChromeState,
    skip: SkipSegmentState,
    skipActions: SkipSegmentActions,
    hintProgress: State<Float>,
    remoteChrome: TvPlayerChromeBridge?,
    /** Playback has just entered the segment: its pill is up on its own for a few seconds. */
    segmentJustEntered: Boolean,
    creditsTakeover: Boolean,
    errorMessage: String?,
    nextUpCardShowing: State<Boolean>,
    creditsPhase: State<CreditsTakeoverPhase>,
) {
    // Auto-skip is a small floating status chip. It is intentionally outside BottomBar's
    // Column so the progress rail never moves when the countdown appears or disappears.
    val lastAutoSkip = remember { arrayOf("", "") }
    skip.countdownSeconds?.let {
        lastAutoSkip[0] = skipCountdownLabel(skip.segmentLabel, it, remote = remoteChrome != null)
        lastAutoSkip[1] = skipCountdownAnnouncement(skip.segmentLabel)
    }
    ChromeVisibility(
        visible = skip.countdownSeconds != null,
        edge = ChromeEdge.Bottom,
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .playerHintOffset(hintProgress, (-60).dp)
                .padding(end = 22.dp, bottom = 24.dp),
    ) {
        CompactAutoSkipPill(
            label = lastAutoSkip[0],
            announcement = lastAutoSkip[1],
            onCancel = {
                if (skip.countdownSeconds != null) {
                    chrome.poke()
                    skipActions.onCancelAuto()
                }
            },
        )
    }
    val lastSkipLabel = remember { arrayOf("") }
    skip.segmentLabel?.let { lastSkipLabel[0] = it }
    val manualSkip =
        shouldShowManualSkipPill(
            segmentLabel = skip.segmentLabel,
            countdownSeconds = skip.countdownSeconds,
            controlsVisible = chrome.visible,
            segmentJustEntered = segmentJustEntered,
        ) &&
            !creditsTakeover
    ChromeVisibility(
        visible = manualSkip,
        edge = ChromeEdge.Bottom,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 92.dp),
    ) {
        SkipPill(
            label = lastSkipLabel[0],
            onClick = {
                if (manualSkip) {
                    chrome.poke()
                    skipActions.onSkip()
                }
            },
        )
    }
    // A remote cannot reach the skip pills or the end-of-episode cards while the controls
    // are down, so OK over the picture acts on whichever is showing (TvRemoteInputController
    // reads this). A skip speaks first: it is the more urgent of the two.
    val remotePrompt =
        when {
            chrome.locked || errorMessage != null -> null
            manualSkip || skip.countdownSeconds != null -> TvPlayerPrompt.Skip
            nextUpCardShowing.value || creditsPhase.value == CreditsTakeoverPhase.Card -> TvPlayerPrompt.NextUp
            else -> null
        }
    DisposableEffect(remoteChrome, remotePrompt) {
        remoteChrome?.publishPrompt(remotePrompt)
        onDispose { remoteChrome?.publishPrompt(null) }
    }
    // Said beside the card, since nothing on it can take focus to say it.
    ChromeVisibility(
        visible = remoteChrome != null && remotePrompt == TvPlayerPrompt.NextUp && !chrome.visible,
        edge = ChromeEdge.End,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 60.dp),
    ) {
        Text(
            "按确定播放下一集 · 按返回看完片尾",
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.72f),
        )
    }
}

/**
 * The end of an episode: 片尾接管's card beside the picture in its corner, then the ordinary next-up
 * card, which takes over from it for the last seconds.
 */
@Composable
internal fun BoxScope.PlayerEndOfItemCards(
    chrome: PlayerChromeState,
    playback: State<PlaybackState>,
    controlState: State<PlaybackState>,
    creditsPhase: State<CreditsTakeoverPhase>,
    /** 取消 has been pressed on this episode's card; see PlayerControls. */
    nextUpDismissed: Boolean,
    transport: PlayerTransportState,
    transportActions: PlayerTransportActions,
    /** 看片尾 on the takeover card: the picture comes back for the rest of this episode. */
    onWatchCredits: () -> Unit,
    /** 取消 on the next-up card, for the caller to remember for this episode. */
    onNextUpDismissed: () -> Unit,
) {
    val state by controlState
    // Where the ordinary card appears, which takes over from this one for the last seconds.
    ChromeVisibility(
        visible = creditsPhase.value == CreditsTakeoverPhase.Card,
        edge = ChromeEdge.End,
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 96.dp),
    ) {
        CreditsTakeoverCard(
            title =
                transport.episodes
                    .getOrNull(state.currentIndex + 1)
                    ?.title
                    .orEmpty(),
            // The picture comes back and the credits play on; the ordinary card still
            // counts down at the very end.
            onWatchCredits = onWatchCredits,
            onPlayNext = {
                if (creditsPhase.value == CreditsTakeoverPhase.Card) {
                    chrome.poke()
                    transportActions.onNextItem()
                }
            },
        )
    }

    PlayerNextUpOverlay(
        playback,
        transport.episodes,
        nextUpDismissed,
        onPlayNow = {
            chrome.poke()
            transportActions.onNextItem()
        },
        onDismiss = {
            chrome.poke()
            onNextUpDismissed()
            transportActions.onDismissNextUp()
        },
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 96.dp),
        autoAdvance = transport.autoNext,
    )
}
