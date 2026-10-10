package com.yfuse.core.designsystem

enum class SplashMark(
    val label: String,
) {
    /** The current mark. */
    WaterFire("默认 Logo"),

    /** The mark the app carried before it. */
    CloudPlayer("云朵播放器 Logo"),
    WaterOverFire("水火即济 Logo"),
}

enum class SplashAnimation(
    val label: String,
    val description: String,
    val mark: SplashMark,
) {
    /** The default: 速度线冲入 → 折带绕轴展开 → 渐变线拉出. */
    One("折带展开", "速度线冲入 → 折带绕轴展开 → 渐变线拉出", SplashMark.WaterFire),
    Cloud("云朵浮现", "云朵轻轻浮起，播放键渐渐清晰", SplashMark.CloudPlayer),
    Magnet("磁粉成型", "彩色微粒从四周聚拢，吸附成标志", SplashMark.WaterOverFire),
    Bloom("墨晕成型", "三团水火色墨晕相遇，收拢为清晰轮廓", SplashMark.WaterOverFire),
    Register("分色套印成型", "三块色版依次落下，微微错位后精准对齐", SplashMark.WaterOverFire),
    Pour("液态灌注成型", "一滴水落下，蓝、橙、金三色沿轮廓注满", SplashMark.WaterOverFire),
    Fold("折纸成型", "三片彩纸沿折痕翻开，合成水火标志", SplashMark.WaterOverFire),
    Stitch("针迹缝合成型", "针尖沿轮廓向下缝合，织出彩色标志", SplashMark.WaterOverFire),
    Crystal("结晶成型", "冰晶从三个晶核生长，逐渐显出完整色块", SplashMark.WaterOverFire),
    Focus("对焦成型", "朦胧光斑与色散收拢，镜头缓缓对焦", SplashMark.WaterOverFire),
    Crayon("蜡笔涂色成型", "灰色线稿上依次涂满三色，留下细腻蜡笔纹理", SplashMark.WaterOverFire),
    Beads("拼豆成型", "彩色拼豆逐颗落位，熨斗扫过后融亮定型", SplashMark.WaterOverFire),
    Sand("沙画成型", "漏斗缓缓移动，彩沙落下、轻弹，堆出标志", SplashMark.WaterOverFire),
    Rubbing("拓印成型", "纸下浮雕隐约透出，蜡笔来回擦出水火色彩", SplashMark.WaterOverFire),
    Hologram("全息扫描成型", "扫描线逐层点亮切片，红青虚影合拢成清晰标志", SplashMark.WaterOverFire),
    Marble("大理石纹成型", "梳齿拉开三色水拓纹路，水面平静后收束成标志", SplashMark.WaterOverFire),
    Fan("折扇成型", "十八折彩纸绕扇钉依次展开，落在浅色纸面上", SplashMark.WaterOverFire),
    Domino("多米诺成型", "彩色骨牌沿对角线连锁倒下，轻弹后拼出标志", SplashMark.WaterOverFire),

    /** The 减弱动态效果 variant: the resolved mark, a short hold, a short fade. */
    Still("静帧", "标志直接显示，短暂停留后淡出", SplashMark.WaterFire),
    ;

    companion object {
        val selectable: List<SplashAnimation> = entries.filter { it != Still }

        fun forMark(
            mark: SplashMark,
            preferred: SplashAnimation,
        ): SplashAnimation = preferred.takeIf { it != Still && it.mark == mark } ?: selectable.first { it.mark == mark }

        /** Which of the two plays, given the effective reduce-motion state. */
        fun forMotion(reduceMotion: Boolean): SplashAnimation = if (reduceMotion) Still else One
    }
}