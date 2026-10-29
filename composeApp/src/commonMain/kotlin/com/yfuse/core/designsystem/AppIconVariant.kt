package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Launcher identities, including retired aliases retained for installed-app migration. */
enum class AppIconVariant(
    val label: String,
    val description: String,
) {
    Default("默认 Logo · 水火既济", "浪线分开上水下火的播放键，浅色底"),
    Classic("原版 Logo", "原版水火折带标志，浅色底"),
    Graphite("原版 Logo · 石墨", "原版水火折带标志，深灰底，适合深色主屏"),

    /** The previous mark, paired with its own cloud splash. */
    CloudPlayer("云朵播放器 Logo", "云朵与播放键，浅色底"),
    AuroraDark("极光 · 深色", "青蓝紫渐变折带，深色底"),
    AuroraLight("极光 · 浅色", "青蓝紫渐变折带，浅色底"),
    Prism("汇光", "三束彩光射进播放键，汇成一束白光，深色底"),

    /** Retained so an installed water-over-fire alias can migrate to the same default artwork. */
    WaterOverFire("水火既济 Logo", "浪线分开上水下火的播放键，浅色底"),
    Overprint("叠印", "青与品红两笔叠印成 Y，白底"),
    Danmaku("弹幕", "弹幕横条拼成的 Y，深色底"),
    LiquidGlass("液态", "两滴水汇成的磨砂玻璃 Y，极光渐变底"),
    ;

    val normalized: AppIconVariant get() = takeIf { it in selectable } ?: Default

    val splashMark: SplashMark
        get() =
            when (normalized) {
                CloudPlayer -> SplashMark.CloudPlayer
                Default -> SplashMark.WaterOverFire
                else -> SplashMark.WaterFire
            }

    companion object {
        /**
         * What the settings page offers.
         *
         * This list used to hold only the three cloud-player-era marks, which left the user
         * with no second language to switch to — and, because [normalized] sends anything
         * outside this list back to [Default], it also quietly migrated everyone who had
         * already picked one of the 2026-10 marks back to the default.
         *
         * Every variant listed here has its alias, its vector layers, its monochrome layer and
         * its notification silhouette in place already; the list was the only thing hiding
         * them. [WaterOverFire] stays out because [Default] shows the same artwork — listing
         * both would put two identical icons in the picker.
         */
        val selectable: List<AppIconVariant> =
            listOf(
                // The 2026-10 marks, in the order that reads best against the current UI.
                LiquidGlass,
                Prism,
                Overprint,
                Danmaku,
                AuroraDark,
                AuroraLight,
                // The cloud-player-era marks, kept for the users who know the app by them.
                Default,
                Classic,
                Graphite,
                CloudPlayer,
            )
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
