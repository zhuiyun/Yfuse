package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Full-screen app overlay that only needs a commit-time system back callback. */
@Composable
fun BackOverlay(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    ReportOverlayVisible()
    PlatformBackHandler(onBack = onBack)
    Box(modifier.fillMaxSize(), content = content)
}
