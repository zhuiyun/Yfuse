package com.yfuse.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.Dimens

/**
 * Bottom space required while the compact floating navigation dock is visible.
 *
 * The dock is laid out above the system navigation bar, then adds its own bottom margin and
 * its controls' height. Keeping that geometry here prevents pages from guessing a device-
 * independent padding that is too small with three-button navigation and unnecessarily large
 * on gesture-navigation devices. [Dimens.sectionGap] leaves the final row visibly separate
 * from the glass instead of merely moving its baseline to the dock's top edge.
 */
@Composable
fun floatingNavigationContentInset(): Dp =
    floatingNavigationContentInset(
        systemNavigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        // The dock grows with the font scale; the clearance has to grow with it.
        dock = dockHeight(),
    )

internal fun floatingNavigationContentInset(
    systemNavigationInset: Dp,
    dock: Dp = Dimens.tabBarHeight,
): Dp = systemNavigationInset + Dimens.tabBarInset + dock + Dimens.sectionGap

/**
 * Where a toast rests while the dock is up: clear of the dock, and while the activity capsule
 * stands on it — [capsule] tall, zero while it is away — clear of that too. The capsule is drawn
 * over the page, so a toast left under it lost its 撤销 to the capsule's own tap.
 */
@Composable
internal fun floatingNavigationToastInset(capsule: Dp): Dp =
    floatingNavigationToastInset(
        systemNavigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        dock = dockHeight(),
        capsule = capsule,
    )

internal fun floatingNavigationToastInset(
    systemNavigationInset: Dp,
    dock: Dp = Dimens.tabBarHeight,
    capsule: Dp = 0.dp,
): Dp {
    val clearOfDock = floatingNavigationContentInset(systemNavigationInset, dock)
    if (capsule <= 0.dp) return clearOfDock
    val capsuleTop = systemNavigationInset + activityCapsuleOffset(dock) + capsule
    return maxOf(clearOfDock, capsuleTop + Dimens.space.sm)
}

/**
 * How far above the system navigation bar the activity capsule's slot begins: the dock's height,
 * its margin below, and a step of air above it.
 */
internal fun activityCapsuleOffset(dock: Dp): Dp = dock + Dimens.tabBarInset + Dimens.space.sm

/** Bottom space for full-screen child pages where the shell has already hidden its dock. */
@Composable
fun systemNavigationContentInset(): Dp =
    systemNavigationContentInset(
        systemNavigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
    )

internal fun systemNavigationContentInset(systemNavigationInset: Dp): Dp = systemNavigationInset + Dimens.sectionGap
