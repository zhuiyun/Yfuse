package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * Stable storage IDs; the numbered choices match the approved material studies. Every knob a
 * recipe starts from lives here, so a new material is one entry - nothing keys on its identity.
 */
enum class GlassMaterialPreset(
    val id: String,
    val number: String,
    val label: String,
    val description: String,
    val blur: Float,
    val saturation: Float,
    val refraction: Float,
    val rim: Float,
    private val lightOpacity: Float,
    private val darkOpacity: Float,
    internal val rimWidth: Float = 0.6f,
    internal val prism: Float = 0f,
    internal val pearl: Float = 0f,
    internal val fluted: Float = 0f,
    internal val fluteWidth: Float = 12f,
    internal val ink: GlassInk = GlassInk.Theme,
    /** Where the light theme starts on [tints]; the dark theme always starts at 0. */
    private val lightTone: Float = 1f,
    private val tints: GlassTints = GlassTints.Cool,
) {
    Default("default", "", "经典玻璃", "原有玻璃效果", 22f, 1.12f, 3f, 0.30f, 0.82f, 0.72f, tints = GlassTints.Classic),
    Clear("clear", "01", "清水薄璃", "低雾度 · 极薄亮边", 5f, 1.22f, 3f, 0.56f, 0.18f, 0.24f),
    Silver("silver", "02", "银雾磨砂", "柔和漫射 · 银灰雾面", 24f, 0.75f, 1f, 0.46f, 0.72f, 0.70f),
    Prism("prism", "03", "棱镜流光", "清透底色 · 彩色亮边", 9f, 1.55f, 5f, 0.65f, 0.24f, 0.24f, rimWidth = 1f, prism = 1f),
    Pearl(
        id = "pearl",
        number = "04",
        label = "月光珍珠",
        description = "温润乳白 · 柔和珠光",
        blur = 20f,
        saturation = 0.80f,
        refraction = 2f,
        rim = 0.48f,
        lightOpacity = 0.78f,
        darkOpacity = 0.72f,
        pearl = 1f,
        tints = GlassTints.Pearl,
    ),
    Smoke(
        id = "smoke",
        number = "05",
        label = "烟熏墨晶",
        description = "深色染透 · 克制高光",
        blur = 15f,
        saturation = 0.70f,
        refraction = 2f,
        rim = 0.22f,
        lightOpacity = 0.82f,
        darkOpacity = 0.84f,
        ink = GlassInk.Light,
        lightTone = 0f,
        tints = GlassTints.Smoke,
    ),
    Fluted(
        id = "fluted",
        number = "06",
        label = "纵纹水晶",
        description = "竖向纹理 · 折光层次",
        blur = 7f,
        saturation = 0.95f,
        refraction = 3f,
        rim = 0.60f,
        lightOpacity = 0.32f,
        darkOpacity = 0.30f,
        fluted = 1f,
        tints = GlassTints.Fluted,
    ),
    SoftMist("soft-mist", "S1", "柔雾", "柔和透色 · 轻盈雾面", 30f, 0.82f, 0f, 0.20f, 0.22f, 0.22f),
    DenseMist("dense-mist", "S2", "凝雾", "均匀漫射 · 细腻温润", 44f, 0.82f, 0f, 0.17f, 0.40f, 0.40f),
    MilkyMist("milky-mist", "S3", "乳雾", "浓润雾面 · 微透底色", 60f, 0.82f, 0f, 0.14f, 0.60f, 0.60f),

    // L series - liquid glass: little frost, strong edge lensing and vivid colour through the body.
    Lens(
        id = "lens",
        number = "L1",
        label = "液态透镜",
        description = "近乎无雾 · 边缘强折射",
        blur = 3f,
        saturation = 1.80f,
        refraction = 10f,
        rim = 0.70f,
        lightOpacity = 0.18f,
        darkOpacity = 0.18f,
        rimWidth = 1.2f,
        prism = 0.12f,
        tints = GlassTints.Lens,
    ),
    Dew(
        id = "dew",
        number = "L2",
        label = "晨露水珠",
        description = "水润透亮 · 饱满高光",
        blur = 8f,
        saturation = 1.45f,
        refraction = 7f,
        rim = 0.62f,
        lightOpacity = 0.24f,
        darkOpacity = 0.26f,
        rimWidth = 0.9f,
        pearl = 0.30f,
        tints = GlassTints.Dew,
    ),
    Aurora(
        id = "aurora",
        number = "L3",
        label = "极光釉彩",
        description = "虹彩流转 · 青紫双色亮边",
        blur = 12f,
        saturation = 1.70f,
        refraction = 6f,
        rim = 0.72f,
        lightOpacity = 0.28f,
        darkOpacity = 0.30f,
        rimWidth = 1.1f,
        prism = 0.85f,
        pearl = 0.20f,
        tints = GlassTints.Aurora,
    ),
    Glacier(
        id = "glacier",
        number = "L4",
        label = "冰川流璃",
        description = "厚冰质感 · 疏朗冰纹",
        blur = 28f,
        saturation = 0.92f,
        refraction = 8f,
        rim = 0.52f,
        lightOpacity = 0.68f,
        darkOpacity = 0.52f,
        rimWidth = 1.4f,
        fluted = 0.22f,
        fluteWidth = 26f,
        tints = GlassTints.Glacier,
    ),
    Amber(
        id = "amber",
        number = "L5",
        label = "琥珀蜜糖",
        description = "暖色流金 · 蜜糖柔光",
        blur = 18f,
        saturation = 1.25f,
        refraction = 5f,
        rim = 0.50f,
        lightOpacity = 0.68f,
        darkOpacity = 0.58f,
        rimWidth = 0.8f,
        pearl = 0.55f,
        tints = GlassTints.Amber,
    ),
    Obsidian(
        id = "obsidian",
        number = "L6",
        label = "黑曜液镜",
        description = "深色镜面 · 锐利折光",
        blur = 10f,
        saturation = 1.35f,
        refraction = 9f,
        rim = 0.40f,
        lightOpacity = 0.82f,
        darkOpacity = 0.80f,
        rimWidth = 0.8f,
        prism = 0.18f,
        ink = GlassInk.Light,
        lightTone = 0f,
        tints = GlassTints.Obsidian,
    ),
    ;

    fun material(dark: Boolean): GlassMaterial =
        GlassMaterial(
            tone = if (dark) 0f else lightTone,
            opacity = if (dark) darkOpacity else lightOpacity,
            // 0.30 is the light scrim every dialog got in 1.0.69; a preset must not undo it.
            scrim = if (dark) 0.28f else 0.30f,
            preset = this,
        ).let { if (this == Default) GlassMaterial.defaults(dark) else it }

    /** The colours the 色调 slider moves between: dark theme at 0, light theme at [lightTone]. */
    internal fun tintRange(dark: Boolean): Pair<Color, Color> = tints.range(dark)

    companion object {
        val selectable: List<GlassMaterialPreset> = entries.filter { it != Default }

        fun fromId(id: String): GlassMaterialPreset = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** Tint endpoints per theme, as ARGB. A range that ignores the theme repeats itself. */
internal enum class GlassTints(
    private val darkStart: Long,
    private val darkEnd: Long,
    private val lightStart: Long,
    private val lightEnd: Long,
) {
    Classic(0xFF191E27, 0xFF878F9B, 0xFF878F9B, 0xFFE4E9F0),
    Cool(0xFF222D3A, 0xFF778998, 0xFFA5B5C3, 0xFFEFF4F6),
    Pearl(0xFF423C51, 0xFF746573, 0xFFBBBACB, 0xFFF2ECF0),
    Smoke(0xFF152330, 0xFF344856, 0xFF152330, 0xFF344856),
    Fluted(0xFF1C435D, 0xFF7294A6, 0xFF8CAAB7, 0xFFD1E2E4),
    Lens(0xFF1A2230, 0xFF6E7F90, 0xFFB4C4D2, 0xFFF6F9FC),
    Dew(0xFF163440, 0xFF5D8A96, 0xFF9CC4CC, 0xFFE6F6F6),
    Aurora(0xFF241F3E, 0xFF6E6894, 0xFFB9B4D8, 0xFFEEEBFA),
    Glacier(0xFF12283A, 0xFF577A93, 0xFFA2BFD2, 0xFFE8F2F8),
    Amber(0xFF36261A, 0xFF7C5F45, 0xFFD8B68A, 0xFFFAF0E2),
    Obsidian(0xFF0B141D, 0xFF2A3845, 0xFF0B141D, 0xFF2A3845),
    ;

    fun range(dark: Boolean): Pair<Color, Color> =
        if (dark) Color(darkStart) to Color(darkEnd) else Color(lightStart) to Color(lightEnd)
}
