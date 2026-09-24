package com.yfuse.core.designsystem

/**
 * How the player opens from a page and closes back into it — the six sets of the approved study
 * (设置 → 外观 → 播放器进出场).
 *
 * Stable names are persisted; labels and descriptions are the study's own, so the row in
 * settings reads the same as the page the choice was made on. Every set degrades to the plain
 * window fade under 减弱动态效果, in 画中画 and on a television.
 */
enum class PlayerTransitionStyle(
    val label: String,
    val description: String,
) {
    Turn("转身", "大图原地抬起，跟着手腕转成横屏；关闭时落回原处"),
    Curtain("开幕", "页面熄灯收成一点光，转到横屏后开闸放映"),
    Glass("玻璃舱", "播放键化成玻璃撑开成画框；关闭时还给播放键"),
    PushIn("推近", "镜头钻进大图，铺满屏幕转过来后落进画框"),
    Tide("潮汐", "一道波从播放键荡开，画面从暗处浮上来"),
    Defocus("虚焦", "画面化成自己的颜色，转过来后重新对焦"),
}
