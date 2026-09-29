package com.yfuse.feature.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.PlayerGestureSettings
import com.yfuse.core.designsystem.ContextualTip
import com.yfuse.core.designsystem.Tips

/**
 * The player's 情境提示: the gestures nothing on screen shows, each taught once, at the top of the
 * picture where the title bar leaves room, and only while it is in reach — the chrome up, on a touch
 * screen, nothing locked. A day has at most one seen tip, so when several could appear together the
 * order here decides, most useful first.
 *
 * Each retires when its gesture is used, wherever that happens ([Tips] names them); 手势与快捷键 in
 * 更多 keeps the whole list for anyone who wants it all at once.
 */
@Composable
internal fun BoxScope.PlayerGestureTips(
    chromeUp: Boolean,
    /** A timeline to move along, and this viewer allowed to move it. */
    seekable: Boolean,
    /** Not in a room or on a cast, where playing faster is not this device's to decide. */
    speedBoostable: Boolean,
    subtitlesAvailable: Boolean,
    danmakuShowing: Boolean,
    fineScrubArmed: Boolean,
    gestures: PlayerGestureSettings,
) {
    val place = Modifier.align(Alignment.TopCenter).padding(top = 88.dp)
    ContextualTip(
        id = Tips.PLAYER_CENTER_HOLD,
        text =
            if (gestures.sideHoldScans) {
                "长按画面中间可以临时加速，按住左右滑动换挡"
            } else {
                "长按画面可以临时加速，按住左右滑动换挡"
            },
        active =
            chromeUp &&
                seekable &&
                speedBoostable &&
                (gestures.centerHoldSpeedBoost || !gestures.sideHoldScans),
        modifier = place,
    )
    ContextualTip(
        id = Tips.PLAYER_DOUBLE_TAP,
        text = "双击画面左右两侧快退、快进，接着在同一侧点按会继续累加",
        active = chromeUp && seekable && !gestures.doubleTapPausesAnywhere,
        modifier = place,
    )
    ContextualTip(
        id = Tips.PLAYER_SWIPE_SEEK,
        text = "在画面上左右滑动可以拖动进度，划得越快走得越远",
        active = chromeUp && seekable,
        modifier = place,
    )
    ContextualTip(
        id = Tips.PLAYER_SIDE_DRAG,
        text =
            if (gestures.swapBrightnessVolume) {
                "在画面左半边上下滑动调音量，右半边调亮度"
            } else {
                "在画面左半边上下滑动调亮度，右半边调音量"
            },
        active = chromeUp,
        modifier = place,
    )
    ContextualTip(
        id = Tips.PLAYER_DANMAKU_KEY,
        text = "点弹幕键开关弹幕，按住它进入弹幕设置",
        active = chromeUp,
        modifier = place,
    )
    ContextualTip(
        id = Tips.PLAYER_MISSED_LINE,
        text = "没听清？按住「后退 10 秒」倒回并临时打开字幕",
        active = chromeUp && seekable && subtitlesAvailable,
        modifier = place,
    )
    ContextualTip(
        id = Tips.PLAYER_DANMAKU_PICK,
        text = "点一下飘过的弹幕，可以复制或屏蔽它",
        active = chromeUp && danmakuShowing,
        modifier = place,
    )
    ContextualTip(
        id = Tips.PLAYER_PINCH_FILL,
        text = "双指张开让画面裁剪填满，捏合恢复适应",
        active = chromeUp,
        modifier = place,
    )
    ContextualTip(
        id = Tips.FINE_SCRUB,
        text = "拖动进度条时手指上移可以精细定位",
        active = fineScrubArmed && chromeUp && seekable,
        modifier = place,
    )
}
