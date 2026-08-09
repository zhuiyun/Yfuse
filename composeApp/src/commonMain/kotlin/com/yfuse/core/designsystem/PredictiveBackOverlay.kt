package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Predictive-back host for a full-screen page that is drawn as an overlay above a page which
 * remains composed underneath it.
 *
 * This is intentionally different from [SharedElementTransitionContainer]: the destination is
 * already alive below this overlay, so only the top page needs to follow the finger. Using the
 * same [PredictiveBackState] keeps the gesture shape, easing and commit throw identical to routed
 * pages while avoiding a second copy of the underlying page.
 */
@Composable
fun PredictiveBackOverlay(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPredictiveBackState()
    // The caller only uses this host when a real page is already mounted underneath, so a peek is
    // always meaningful while the overlay can be dismissed.
    state.canPeek = enabled

    PlatformPredictiveBackHandler(
        enabled = enabled,
        onProgress = state::onProgress,
        onCancel = state::onCancel,
        onBack = { state.onStandaloneCommit(onBack) },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .predictiveBackPeek(state),
        content = content,
    )
}
