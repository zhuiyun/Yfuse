package com.yfuse.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import com.yfuse.core.designsystem.Dimens

/**
 * Bottom space required while the compact floating navigation dock is visible.
 *
 * The dock is laid out above the system navigation bar, then adds its own bottom margin and
 * fixed-height controls. Keeping that geometry here prevents pages from guessing a device-
 * independent padding that is too small with three-button navigation and unnecessarily large
 * on gesture-navigation devices. [Dimens.sectionGap] leaves the final row visibly separate
 * from the glass instead of merely moving its baseline to the dock's top edge.
 */
@Composable
fun floatingNavigationContentInset(): Dp =
    floatingNavigationContentInset(
        systemNavigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    )

internal fun floatingNavigationContentInset(systemNavigationInset: Dp): Dp =
    systemNavigationInset + Dimens.tabBarInset + Dimens.tabBarHeight + Dimens.sectionGap

/** Bottom space for full-screen child pages where the shell has already hidden its dock. */
@Composable
fun systemNavigationContentInset(): Dp =
    systemNavigationContentInset(
        systemNavigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    )

internal fun systemNavigationContentInset(systemNavigationInset: Dp): Dp = systemNavigationInset + Dimens.sectionGap
