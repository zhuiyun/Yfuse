package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Which launcher icon the app presents.
 *
 * The current water-fire mark is available on light and graphite grounds, and the previous
 * cloud-player mark remains available alongside the light and dark Aurora artwork. The last
 * five are the 2026-10 concepts, drawn as vectors by scripts/launcher_icons/generate.py; each
 * mark is made for its own ground, so they are always shown whole, ground included.
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
     * its owner navigates by shape. This puts the old one back for anyone who wants it — the
     * icon only: every launch plays the water-fire ribbon, whichever icon is chosen.
     */
    CloudPlayer("旧版云朵播放器", "旧版云朵播放器 Logo，浅色底"),
    AuroraDark("极光 · 深色", "青蓝紫渐变折带，深色底"),
    AuroraLight("极光 · 浅色", "青蓝紫渐变折带，浅色底"),
    Prism("汇光", "三束彩光射进播放键，汇成一束白光，深色底"),
    WaterOverFire("水火既济", "浪线分开上水下火的播放键，浅色底"),
    Overprint("叠印", "青与品红两笔叠印成 Y，白底"),
    Danmaku("弹幕", "弹幕横条拼成的 Y，深色底"),
    LiquidGlass("液态", "两滴水汇成的磨砂玻璃 Y，极光渐变底"),
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
