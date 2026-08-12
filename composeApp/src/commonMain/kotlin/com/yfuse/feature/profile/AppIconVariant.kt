package com.yfuse.feature.profile

/**
 * Which launcher icon the app presents.
 *
 * The mark is the same in all of them and only its ground changes: an alternate icon has to
 * remain recognisable as this app on a home screen the user already knows, so this is a
 * choice of colour, not of logo.
 */
enum class AppIconVariant(val label: String, val description: String) {
    Default("默认", "浅色底，随包附带的原始图标"),
    Graphite("石墨", "深灰底，适合深色主屏"),
    Indigo("靛蓝", "靛蓝底"),
    Forest("森绿", "墨绿底"),
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
