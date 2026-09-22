package com.yfuse.core.designsystem

/** Stable storage IDs; the numbered choices match the approved material studies. */
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
) {
    Default("default", "", "经典玻璃", "原有玻璃效果", 22f, 1.12f, 3f, 0.30f, 0.82f, 0.72f),
    Clear("clear", "01", "清水薄璃", "低雾度 · 极薄亮边", 5f, 1.22f, 3f, 0.56f, 0.18f, 0.24f),
    Silver("silver", "02", "银雾磨砂", "柔和漫射 · 银灰雾面", 24f, 0.75f, 1f, 0.46f, 0.72f, 0.70f),
    Prism("prism", "03", "棱镜流光", "清透底色 · 彩色亮边", 9f, 1.55f, 5f, 0.65f, 0.24f, 0.24f),
    Pearl("pearl", "04", "月光珍珠", "温润乳白 · 柔和珠光", 20f, 0.80f, 2f, 0.48f, 0.78f, 0.72f),
    Smoke("smoke", "05", "烟熏墨晶", "深色染透 · 克制高光", 15f, 0.70f, 2f, 0.22f, 0.82f, 0.84f),
    Fluted("fluted", "06", "纵纹水晶", "竖向纹理 · 折光层次", 7f, 0.95f, 3f, 0.60f, 0.32f, 0.30f),
    SoftMist("soft-mist", "S1", "柔雾", "柔和透色 · 轻盈雾面", 30f, 0.82f, 0f, 0.20f, 0.22f, 0.22f),
    DenseMist("dense-mist", "S2", "凝雾", "均匀漫射 · 细腻温润", 44f, 0.82f, 0f, 0.17f, 0.40f, 0.40f),
    MilkyMist("milky-mist", "S3", "乳雾", "浓润雾面 · 微透底色", 60f, 0.82f, 0f, 0.14f, 0.60f, 0.60f),
    ;

    fun material(dark: Boolean): GlassMaterial =
        GlassMaterial(
            tone = if (dark || this == Smoke) 0f else 1f,
            opacity = if (dark) darkOpacity else lightOpacity,
            scrim = if (dark) 0.28f else 0.16f,
            preset = this,
        ).let { if (this == Default) GlassMaterial.defaults(dark) else it }

    companion object {
        val selectable: List<GlassMaterialPreset> = entries.filter { it != Default }

        fun fromId(id: String): GlassMaterialPreset = entries.firstOrNull { it.id == id } ?: Default
    }
}
