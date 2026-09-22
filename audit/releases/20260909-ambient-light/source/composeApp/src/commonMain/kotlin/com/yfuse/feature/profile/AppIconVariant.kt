package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yfuse.core.designsystem.SplashMark

/**
 * Which launcher icon the app presents.
 *
 * The current water-fire mark is available on light and graphite grounds, and the previous
 * cloud-player mark remains a real alternate for people who recognise the app by that shape.
 */
enum class AppIconVariant(
    val label: String,
    val description: String,
) {
    Default("当前 Logo", "当前水火标志，浅色底"),
    Graphite("当前 Logo · 石墨", "当前水火标志，深灰底，适合深色主屏"),

    /**
     * The mark this app carried before the current one.
     *
     * Kept as a real choice rather than for nostalgia: people recognise their apps by the
     * icon, and an update that replaces it makes the app briefly disappear from a home screen
     * its owner navigates by shape. This puts the old one back for anyone who wants it — and
     * it brings that mark's own launch animations back with it.
     */
    CloudPlayer("旧版云朵播放器", "旧版云朵播放器 Logo，配水滴砸云开屏"),
}

/**
 * Which mark this icon carries.
 *
 * The single join between the launcher and 开屏动画: choosing either end of a pair moves the
 * other, and nothing else in the app has to know which animation goes with which logo.
 */
val AppIconVariant.splashMark: SplashMark
    get() =
        when (this) {
            AppIconVariant.Default, AppIconVariant.Graphite -> SplashMark.WaterFire
            AppIconVariant.CloudPlayer -> SplashMark.CloudPlayer
        }

/**
 * The icon this mark implies, given what the launcher is showing now.
 *
 * [current] is kept when it already carries this mark, so picking 水火交接 while the launcher
 * is on 石墨 does not quietly demote it to the light ground.
 */
fun SplashMark.appIconFor(current: AppIconVariant): AppIconVariant =
    if (current.splashMark == this) {
        current
    } else {
        when (this) {
            SplashMark.WaterFire -> AppIconVariant.Default
            SplashMark.CloudPlayer -> AppIconVariant.CloudPlayer
        }
    }

/** The variant the launcher is currently showing. */
expect fun currentAppIconVariant(): AppIconVariant

/**
 * Switches the launcher icon.
 *
 * On Android this enables one manifest component and disables the others, which the launcher
 * may take a moment to notice and which can briefly remove the app from the drawer on some
 * OEM launchers — so it is worth telling the user before they go looking for it.
 */
expect fun setAppIconVariant(variant: AppIconVariant)

/** The icon's own artwork on its own ground, at whatever size [modifier] gives it. */
@Composable
internal expect fun AppIconPreview(
    variant: AppIconVariant,
    modifier: Modifier = Modifier,
)
