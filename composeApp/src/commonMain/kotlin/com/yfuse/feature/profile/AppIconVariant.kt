package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yfuse.core.designsystem.SplashMark

/** Launcher identities, including retired aliases retained for installed-app migration. */
enum class AppIconVariant(
    val label: String,
    val description: String,
) {
    Default("默认 Logo", "当前水火标志，浅色底"),
    Graphite("当前 Logo · 石墨", "当前水火标志，深灰底，适合深色主屏"),

    /** The previous mark, paired with its own cloud splash. */
    CloudPlayer("云朵播放器 Logo", "云朵与播放键，浅色底"),
    AuroraDark("极光 · 深色", "青蓝紫渐变折带，深色底"),
    AuroraLight("极光 · 浅色", "青蓝紫渐变折带，浅色底"),
    Prism("汇光", "三束彩光射进播放键，汇成一束白光，深色底"),
    WaterOverFire("水火即济 Logo", "浪线分开上水下火的播放键，浅色底"),
    Overprint("叠印", "青与品红两笔叠印成 Y，白底"),
    Danmaku("弹幕", "弹幕横条拼成的 Y，深色底"),
    LiquidGlass("液态", "两滴水汇成的磨砂玻璃 Y，极光渐变底"),
    ;

    val normalized: AppIconVariant get() = takeIf { it in selectable } ?: Default

    val splashMark: SplashMark
        get() =
            when (normalized) {
                CloudPlayer -> SplashMark.CloudPlayer
                WaterOverFire -> SplashMark.WaterOverFire
                else -> SplashMark.WaterFire
            }

    companion object {
        // Retired enum entries remain only to disable previously enabled Android aliases safely.
        val selectable: List<AppIconVariant> = listOf(Default, CloudPlayer, WaterOverFire)
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
