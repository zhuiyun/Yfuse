package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.useNavigationRail

/** Narrower than this, an upright player window is a phone's. */
private val UprightPhoneMaxWidth = 600.dp

/**
 * The player window stands upright and narrow: a phone playing an upright 短剧. The chrome was
 * drawn for a landscape strip — a top bar needing some 360dp of keys and a bottom row of 600 —
 * so here it stacks its rows and keeps only the keys a narrow bar has room for.
 */
@Composable
internal fun rememberUprightPhoneWindow(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(size, density) {
        size.height > size.width && with(density) { size.width.toDp() } < UprightPhoneMaxWidth
    }
}

@Composable
internal fun playerPanelWidth(compactWidth: Dp): Dp {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) {
        val width = size.width.toDp()
        if (useNavigationRail(width, size.height.toDp())) {
            (width * 0.32f).coerceIn(320.dp, 420.dp)
        } else {
            compactWidth
        }
    }
}
