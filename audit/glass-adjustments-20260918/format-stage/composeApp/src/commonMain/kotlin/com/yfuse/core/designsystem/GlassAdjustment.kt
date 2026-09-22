package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/** Shared by touch sliders, TV key steps and tests of recipe reconstruction. */
enum class GlassAdjustment(
    val label: String,
    val hint: String,
    val group: Int,
    val min: Float = 0f,
    val max: Float = 1f,
    val step: Float = 0.01f,
    val unit: String = "%",
) {
    Red("红色", "底色的红色分量", 1, max = 255f, step = 1f, unit = ""),
    Green("绿色", "底色的绿色分量", 1, max = 255f, step = 1f, unit = ""),
    Blue("蓝色", "底色的蓝色分量", 1, max = 255f, step = 1f, unit = ""),
    Opacity("底色不透明度", "通透 → 厚实", 1),
    Scrim("背景遮罩", "背景明亮 → 背景暗淡", 1),
    Blur("雾化半径", "清晰透景 → 细腻浓雾，无砂点", 2, 1f, 70f, 1f, "dp"),
    Saturation("背景色彩浓度", "灰阶 → 原色 → 浓郁透色", 2, max = 2f),
    Refraction("边缘折射", "平直透光 → 边缘弯折", 2, max = 12f, step = 0.1f, unit = "dp"),
    Rim("亮边强度", "柔和雾面 → 清晰亮边", 3),
    RimWidth("亮边宽度", "细薄 → 厚实", 3, 0.3f, 2f, 0.1f, "dp"),
    Prism("棱镜彩光", "无彩光 → 青紫暖色折光", 3),
    Pearl("珍珠柔光", "无珠光 → 温润柔光", 3),
    Fluted("纵纹强度", "平滑 → 竖向光影与折射", 4),
    FluteWidth("纵纹间距", "紧密细纹 → 疏朗宽纹", 4, 4f, 32f, 1f, "dp"),
    ;

    fun value(
        material: GlassMaterial,
        dark: Boolean,
    ): Float =
        when (this) {
            Red -> ((material.tint(dark).toArgb() shr 16) and 255).toFloat()
            Green -> ((material.tint(dark).toArgb() shr 8) and 255).toFloat()
            Blue -> (material.tint(dark).toArgb() and 255).toFloat()
            Opacity -> material.opacity
            Scrim -> material.scrim
            Blur -> material.blur
            Saturation -> material.saturation
            Refraction -> material.refraction
            Rim -> material.rim
            RimWidth -> material.rimWidth
            Prism -> material.prism
            Pearl -> material.pearl
            Fluted -> material.fluted
            FluteWidth -> material.fluteWidth
        }

    fun update(
        material: GlassMaterial,
        dark: Boolean,
        value: Float,
    ): GlassMaterial {
        if (!value.isFinite()) return material
        val scale = 1f / step
        val next = ((value.coerceIn(min, max) * scale).roundToInt() / scale).coerceIn(min, max)
        val color = material.tint(dark)
        val red = (color.red * 255).roundToInt()
        val green = (color.green * 255).roundToInt()
        val blue = (color.blue * 255).roundToInt()

        fun rgb(
            r: Int,
            g: Int,
            b: Int,
        ) = material.copy(tintRgb = Color(r, g, b).toArgb() and 0xFFFFFF)
        return when (this) {
            Red -> rgb(next.toInt(), green, blue)
            Green -> rgb(red, next.toInt(), blue)
            Blue -> rgb(red, green, next.toInt())
            Opacity -> material.copy(opacity = next)
            Scrim -> material.copy(scrim = next)
            Blur -> material.copy(blur = next)
            Saturation -> material.copy(saturation = next)
            Refraction -> material.copy(refraction = next)
            Rim -> material.copy(rim = next)
            RimWidth -> material.copy(rimWidth = next)
            Prism -> material.copy(prism = next)
            Pearl -> material.copy(pearl = next)
            Fluted -> material.copy(fluted = next)
            FluteWidth -> material.copy(fluteWidth = next)
        }
    }

    fun display(value: Float): String =
        when {
            unit == "%" -> "${(value * 100).roundToInt()}%"
            step < 1f -> "${(value * 10).roundToInt() / 10f} $unit"
            else -> "${value.roundToInt()} $unit".trim()
        }
}
