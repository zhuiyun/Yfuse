package com.yfuse.feature.profile

/**
 * Which launcher icon the app presents.
 *
 * The mark is the same in all of them and only its ground changes: an alternate icon has to
 * remain recognisable as this app on a home screen the user already knows, so this is a
 * choice of colour, not of logo.
 */
enum class AppIconVariant(val label: String, val description: String) {
    Default("当前标志", "随包附带的水火标志，浅色底"),
    Graphite("当前标志 · 石墨", "同一标志，深灰底，适合深色主屏"),

    /**
     * The mark this app carried before the current one.
     *
     * Kept as a real choice rather than for nostalgia: people recognise their apps by the
     * icon, and an update that replaces it makes the app briefly disappear from a home screen
     * its owner navigates by shape. This puts the old one back for anyone who wants it — and
     * it pairs with 开屏动画's 折带展开, which is that mark's own choreography.
     */
    CloudPlayer("云朵播放器", "旧版云朵标志，与「折带展开」开屏配套"),
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
