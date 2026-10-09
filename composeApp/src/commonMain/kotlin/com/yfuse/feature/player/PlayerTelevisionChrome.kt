package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.app.ProvideAppTips

/**
 * Whether the player's chrome is read from across a room and driven by a remote.
 *
 * The phone's keys are sized for a thumb at arm's length: a 28 dp ring is a speck at three metres.
 * And the keys that only make sense under a finger — 锁定控制, 手势说明, 小窗, the long-press tips —
 * are dead ends a remote can still land on. Where this is set, keys are drawn no smaller than
 * [TelevisionKeySize] and the chrome leaves those out.
 */
internal val LocalTelevisionChrome = staticCompositionLocalOf { false }

/** The smallest ring a player key is drawn at on a television. */
internal val TelevisionKeySize = 40.dp

/** [size] on a phone; on a television, never less than [TelevisionKeySize]. */
@Composable
@ReadOnlyComposable
internal fun chromeKeySize(size: Dp): Dp = if (LocalTelevisionChrome.current) maxOf(size, TelevisionKeySize) else size

/** The player's composition root: the tips every screen shares, and whether a remote drives it. */
@Composable
internal fun ProvidePlayerChrome(
    television: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTelevisionChrome provides television) {
        ProvideAppTips(content)
    }
}
