package com.yfuse.core.designsystem

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.PI
import kotlin.math.sin

enum class DialogAnimation(
    val label: String,
    val description: String,
    val enterMillis: Int,
    val exitMillis: Int,
) {
    Lift("柔和浮起", "轻轻上浮，柔和展开", Motion.Dialog.ENTER_QUICK, Motion.Dialog.EXIT_QUICK),
    Axis("中轴展开", "从中央细缝向上下展开", Motion.Dialog.ENTER_STANDARD, Motion.Dialog.EXIT_STANDARD),
    Slide("底部升起", "从屏幕底部滑入，关闭时滑回", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_EMPHASIZED),
    Touch("触点展开", "从点击位置展开，关闭时收回", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_EXTENDED),
    Spring("柔性回弹", "轻微拉伸，带细腻回弹", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Perspective("轻透视展开", "轻微倾斜翻起，回到正面", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Hologram("全息扫描", "扫描光线经过，逐行展开玻璃面板", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Fold("空间折叠", "两侧屏幕围绕中央折线展开", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Energy("能量边框", "四角光线勾勒边框，内部随后展开", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_EMPHASIZED),
    Layers("分层悬浮", "玻璃、标题与内容从不同深度归位", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Portal("光圈展开", "从点击位置扩张光圈，收回时逆向闭合", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Reconstruct("数字重构", "横向切片错位进入，快速拼成完整面板", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Magnetic("磁悬停泊", "沿短弧线滑入，轻微倾斜后平稳归位", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Liquid("液态成形", "圆润水滴舒展成玻璃面板，文字保持原比例", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_EMPHASIZED),
    Blinds("光栅百叶", "竖向光栅依次翻开，关闭时逐条闭合", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Assemble("四角汇聚", "四块玻璃从四角靠拢，接缝平稳闭合", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Radar("雷达扫掠", "旋转光束揭开面板，反向扫掠收起", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_EMPHASIZED),
    Ribbon("丝绸揭幕", "柔软弧线自下而上揭开，像轻轻掀起丝绸", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_EMPHASIZED),
    Iris("菱镜光圈", "六边形光圈轻旋展开，露出完整玻璃面板", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Mosaic("方格织入", "细分方格沿对角线依次显现，交织成完整面板", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_EMPHASIZED),
    Orbit("轻旋入场", "卡片沿短弧线轻旋靠近，平稳落定", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Curtain("双幕展开", "两道柔弧从中央向两侧打开，关闭时合拢", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_EMPHASIZED),
    Bloom("花瓣绽放", "六片圆润花瓣从中央舒展，逐渐连成完整面板", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_STANDARD),
    CardExtract("卡片抽取", "像抽出一张卡片，轻轻翻起后落稳", Motion.Dialog.ENTER_STANDARD, Motion.Dialog.EXIT_QUICK),
    Diagonal("斜光掠入", "一束斜光从左上掠向右下，揭开面板", Motion.Dialog.ENTER_QUICK, Motion.Dialog.EXIT_QUICK),
    Capsule("胶囊舒展", "小胶囊先横向伸展，再向上下撑开", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_STANDARD),
    Steps("阶梯铺开", "横向阶梯由上至下依次铺开，收起时逐层回拢", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_STANDARD),
    PosterMorph("海报化窗", "海报延展成弹窗；没有海报时从触点展开", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_EMPHASIZED),
    Ripple("涟漪显现", "从触点泛起两道轻柔波纹，逐渐显露内容", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_STANDARD),
    PageFold("折页展开", "上页先展开，下页随后舒展，轻巧收拢", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_STANDARD),
    MagneticDrag("磁吸归位", "向下拖动弹窗，轻拉回弹，拉远后顺势关闭", Motion.Dialog.ENTER_STANDARD, Motion.Dialog.EXIT_QUICK),
    Sheen("柔光扫入", "面板轻轻浮起，海报色柔光掠过边缘", Motion.Dialog.ENTER_STANDARD, Motion.Dialog.EXIT_QUICK),
    Cascade("内容接力", "面板先落定，标题、选项与按钮依次轻盈入场", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_STANDARD),
    PaperPlane("纸飞机投递", "沿折纸航线轻巧滑入，稳稳送到眼前", Motion.Dialog.ENTER_STANDARD, Motion.Dialog.EXIT_QUICK),
    WindChime("风铃轻摆", "悬挂卡片轻摆两拍，迅速安静归位", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_STANDARD),
    InstantPhoto("拍立得冲印", "从短窄出片口展开，照片边框轻轻收稳", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_STANDARD),
    Zipper("拉链解封", "拉头向下划开，两侧顺势展开", Motion.Dialog.ENTER_STANDARD, Motion.Dialog.EXIT_QUICK),
    Ticket("票根展开", "票头先显现，正文沿齿边利落铺开", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_COMPACT),
    Envelope("信封拆启", "封口轻启，信笺从下沿舒展到眼前", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_STANDARD),
    Constellation("星图织光", "星点连成轮廓，中央光面渐渐铺满", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_STANDARD),
    PuzzleLock("拼图扣合", "两片圆弧拼图相向靠拢，接缝轻巧扣合", Motion.Dialog.ENTER_EMPHASIZED, Motion.Dialog.EXIT_COMPACT),
    Hourglass("沙漏汇流", "上下沙丘向中央汇流，细腰舒展成完整面板", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_STANDARD),
    Pinwheel("风车开页", "四叶纸风车轻转开页，边角依次舒展", Motion.Dialog.ENTER_EXTENDED, Motion.Dialog.EXIT_STANDARD),
}

val LocalDialogAnimation = staticCompositionLocalOf { DialogAnimation.Lift }

/**
 * Debug-only: 弹窗动画 offers 柔和浮起 / 触点展开 / 底部升起 unless this is on, and every implemented
 * style when it is. One polished way in is the product decision; the rest stay in the enum
 * because they are how that one was arrived at, and they still render if they are stored.
 */
val LocalDialogAnimationLab = staticCompositionLocalOf { false }

internal class DialogMotionHost {
    var origin = Offset.Zero
    var height = 0f
    var touch: Offset? = null
    var poster: DialogPosterSource? = null
}

internal val LocalDialogMotionHost = staticCompositionLocalOf { DialogMotionHost() }

/** Observe without consuming: clicks, drags and scrolling keep their existing handlers. */
internal fun Modifier.trackDialogOrigin(host: DialogMotionHost): Modifier =
    onGloballyPositioned {
        host.origin = it.positionInWindow()
        host.height = it.size.height.toFloat()
    }.onPreviewKeyEvent {
        host.touch = null
        host.poster = null
        false
    }.pointerInput(host) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            host.touch = host.origin + down.position
            host.poster = null
        }
    }

/** Normalized progress shared by the staged dialog masks and transforms. */
internal fun dialogStage(
    progress: Float,
    start: Float,
    end: Float,
): Float = ((progress - start) / (end - start)).coerceIn(0f, 1f)

internal data class DialogMotionFrame(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val offsetY: Float = 0f,
    val rotationX: Float = 0f,
    val insetX: Float = 0f,
    val insetY: Float = 0f,
    val offsetX: Float = 0f,
    val rotationZ: Float = 0f,
)

private val RestingDialogMotionFrame = DialogMotionFrame()

/** Layer properties and the reveal mask share one immutable geometry result for each progress. */
internal class DialogMotionFrameCache(
    private val animation: DialogAnimation,
) {
    private var lastProgress = Float.NaN
    private var lastFrame = RestingDialogMotionFrame

    fun frame(progress: Float): DialogMotionFrame {
        val p = progress.coerceIn(0f, 1f)
        if (p != lastProgress) {
            lastProgress = p
            lastFrame = dialogMotionFrame(animation, p)
        }
        return lastFrame
    }
}

internal fun dialogMotionFrame(
    animation: DialogAnimation,
    progress: Float,
): DialogMotionFrame {
    val p = progress.coerceIn(0f, 1f)
    if (p == 1f) return RestingDialogMotionFrame
    val hidden = 1f - p
    return when (animation) {
        DialogAnimation.Lift, DialogAnimation.Sheen, DialogAnimation.Cascade ->
            DialogMotionFrame(
                1f - 0.08f * hidden,
                1f - 0.08f * hidden,
                24f * hidden,
                insetX =
                    0.22f * hidden,
                insetY = 0.5f * hidden,
            )
        DialogAnimation.Axis -> DialogMotionFrame(scaleX = 1f - 0.04f * hidden, insetY = 0.5f * hidden)
        DialogAnimation.Slide -> DialogMotionFrame(offsetY = hidden)
        DialogAnimation.Touch -> DialogMotionFrame(scaleX = p, scaleY = p)
        DialogAnimation.MagneticDrag -> DialogMotionFrame(offsetY = 24f * hidden, insetY = 0.5f * hidden * hidden)
        DialogAnimation.Spring -> {
            val bounce = sin(PI * p).toFloat()
            DialogMotionFrame(
                1f - 0.22f * hidden + 0.1f * bounce,
                1f - 0.1f * hidden + 0.04f * bounce,
                16f * hidden - 7f * bounce,
                insetX = 0.1f * hidden,
                insetY = 0.5f * hidden * hidden,
            )
        }
        DialogAnimation.Perspective ->
            DialogMotionFrame(
                1f - 0.1f * hidden,
                1f - 0.1f * hidden,
                28f * hidden,
                -10f * hidden,
                0.08f * hidden,
                0.5f * hidden,
            )
        DialogAnimation.Layers ->
            DialogMotionFrame(
                1f - 0.06f * hidden,
                1f - 0.06f * hidden,
                12f * hidden,
                insetY = 0.5f * (1f - (p / 0.4f).coerceIn(0f, 1f)),
            )
        DialogAnimation.Magnetic ->
            DialogMotionFrame(
                scaleX = 1f - 0.05f * hidden,
                scaleY = 1f - 0.05f * hidden,
                offsetY = 26f * hidden,
                insetY = 0.5f * (1f - (p / 0.5f).coerceIn(0f, 1f)),
                offsetX = 28f * hidden * hidden - 8f * sin(PI * p).toFloat(),
                rotationZ = -3f * hidden,
            )
        DialogAnimation.Orbit ->
            DialogMotionFrame(
                scaleX = 1f - 0.08f * hidden,
                scaleY = 1f - 0.08f * hidden,
                offsetX = -30f * hidden * hidden,
                offsetY = 18f * hidden,
                rotationZ = 7f * hidden,
                insetX = 0.12f * hidden,
                insetY = 0.5f * hidden * hidden,
            )
        DialogAnimation.CardExtract ->
            DialogMotionFrame(
                scaleX = 1f - 0.04f * hidden,
                scaleY = 1f - 0.04f * hidden,
                offsetY = 32f * hidden,
                rotationX = 8f * hidden,
                offsetX = -8f * sin(PI * p).toFloat(),
                rotationZ = -5f * hidden,
                insetY = 0.5f * hidden * hidden,
            )
        DialogAnimation.Hologram, DialogAnimation.Fold, DialogAnimation.Energy,
        DialogAnimation.Portal, DialogAnimation.Reconstruct, DialogAnimation.Liquid,
        DialogAnimation.Blinds, DialogAnimation.Assemble, DialogAnimation.Radar,
        DialogAnimation.Ribbon, DialogAnimation.Iris, DialogAnimation.Mosaic, DialogAnimation.Curtain,
        DialogAnimation.Bloom, DialogAnimation.Diagonal, DialogAnimation.Capsule, DialogAnimation.Steps,
        DialogAnimation.PosterMorph, DialogAnimation.Ripple, DialogAnimation.PageFold,
        -> RestingDialogMotionFrame
        DialogAnimation.PaperPlane, DialogAnimation.WindChime, DialogAnimation.InstantPhoto,
        DialogAnimation.Zipper, DialogAnimation.Ticket,
        -> delightDialogMotionFrame(animation, p)
        DialogAnimation.Envelope, DialogAnimation.Constellation, DialogAnimation.PuzzleLock,
        DialogAnimation.Hourglass, DialogAnimation.Pinwheel,
        -> curiousDialogMotionFrame(animation, p)
    }
}

private class DialogPanelPosition {
    var origin = Offset.Zero
}

/** Layout stays fixed; only the material layer moves. No panel/text opacity animation. */
@Composable
internal fun Modifier.dialogMotion(
    animation: DialogAnimation,
    drag: DialogDragState? = null,
    progress: () -> Float,
): Modifier {
    val host = LocalDialogMotionHost.current
    val anchor = remember { host.touch }
    val position = remember { DialogPanelPosition() }
    val glow = LocalAccentColors.current.accent
    val cache = remember(glow) { DialogDrawCache(glow) }
    val frames = remember(animation) { DialogMotionFrameCache(animation) }
    val poster = remember { host.poster?.takeIf { animation == DialogAnimation.PosterMorph } }
    val posterLayer = if (animation == DialogAnimation.PosterMorph) rememberGraphicsLayer() else null
    val layerOnly = animation == DialogAnimation.Slide || animation == DialogAnimation.Touch
    // A panel pushed away leaves along the finger, not along its style. Composing the two put the
    // flight on top of an exit that was already travelling — 底部升起 went the distance twice, and
    // 全息扫描 / 菱镜光圈 / 方格织入 / 双幕展开 / 风车开页 came apart mid-air. 磁吸归位 is the one
    // style authored around this gesture, so its own exit is the one that stays.
    val neutralDragExit = animation != DialogAnimation.MagneticDrag
    val transformed =
        onGloballyPositioned { position.origin = it.positionInWindow() }
            .graphicsLayer {
                val entered = progress().coerceIn(0f, 1f)
                val flung = drag != null && drag.dismissedByDrag && neutralDragExit
                val frame = if (flung) RestingDialogMotionFrame else frames.frame(entered)
                // Binary endpoint visibility requires no translucent offscreen layer during motion.
                // The flight is the exception: it is a fade, so that the panel is gone before the
                // travel would have to be long enough to clear the screen on its own.
                alpha =
                    when {
                        flung -> entered
                        layerOnly && entered <= 0f -> 0f
                        else -> 1f
                    }
                transformOrigin =
                    when {
                        animation == DialogAnimation.Touch && anchor != null ->
                            TransformOrigin(
                                (anchor.x - position.origin.x) / size.width.coerceAtLeast(1f),
                                (anchor.y - position.origin.y) / size.height.coerceAtLeast(1f),
                            )
                        animation == DialogAnimation.Perspective -> TransformOrigin(0.5f, 0.85f)
                        animation == DialogAnimation.CardExtract -> TransformOrigin(0.15f, 0.95f)
                        animation == DialogAnimation.WindChime -> TransformOrigin(0.5f, 0f)
                        else -> TransformOrigin.Center
                    }
                scaleX = frame.scaleX
                scaleY = frame.scaleY
                translationY =
                    if (animation == DialogAnimation.Slide && !flung) {
                        (host.origin.y + host.height - position.origin.y + size.height).coerceAtLeast(size.height) *
                            frame.offsetY
                    } else {
                        frame.offsetY * density
                    }
                rotationX = frame.rotationX
                translationX = frame.offsetX * density
                rotationZ = frame.rotationZ
                cameraDistance = 1200f * density
                if (drag != null) {
                    translationY += drag.offset
                    if (drag.dismissedByDrag) {
                        translationY +=
                            if (flung) {
                                size.height * 0.3f * (1f - entered)
                            } else {
                                (host.height + size.height) * (1f - entered)
                            }
                    }
                    val stretch = (drag.offset / size.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    scaleX *= 1f - 0.035f * stretch
                    scaleY *= 1f + 0.015f * stretch
                }
            }
    // A pure layer transform reuses its recorded content without invalidating a draw modifier.
    if (layerOnly) return transformed
    return transformed
        .drawWithContent {
            val entered = progress().coerceIn(0f, 1f)
            // Both endpoints bypass effects, including when reduced motion snaps to them.
            if (entered <= 0f) return@drawWithContent
            // A panel on its way out under the finger draws whole: a style that shatters, folds or
            // wipes itself is telling a story about arriving, and it has nothing to say here.
            if (entered >= 1f || (drag != null && drag.dismissedByDrag && neutralDragExit)) {
                drawContent()
                return@drawWithContent
            }
            if (posterLayer != null &&
                drawPosterDialog(poster, posterLayer, position.origin, anchor, entered, cache)
            ) {
                return@drawWithContent
            }
            val special =
                when (animation) {
                    DialogAnimation.Ripple, DialogAnimation.PageFold ->
                        drawInteractiveDialog(animation, entered, anchor?.minus(position.origin), glow, cache)
                    DialogAnimation.Hologram, DialogAnimation.Fold, DialogAnimation.Energy,
                    DialogAnimation.Portal, DialogAnimation.Reconstruct,
                    -> drawSciFiDialog(animation, entered, anchor?.minus(position.origin), glow, cache)
                    DialogAnimation.Liquid, DialogAnimation.Blinds, DialogAnimation.Assemble, DialogAnimation.Radar ->
                        drawMaterialDialog(animation, entered, glow, cache)
                    DialogAnimation.Ribbon, DialogAnimation.Iris, DialogAnimation.Mosaic, DialogAnimation.Curtain ->
                        drawExpressiveDialog(animation, entered, glow, cache)
                    DialogAnimation.Bloom, DialogAnimation.Diagonal, DialogAnimation.Capsule, DialogAnimation.Steps ->
                        drawPlayfulDialog(animation, entered, glow, cache)
                    DialogAnimation.PaperPlane, DialogAnimation.WindChime, DialogAnimation.InstantPhoto,
                    DialogAnimation.Zipper, DialogAnimation.Ticket,
                    -> drawDelightDialog(animation, entered, glow, cache)
                    DialogAnimation.Envelope, DialogAnimation.Constellation, DialogAnimation.PuzzleLock,
                    DialogAnimation.Hourglass, DialogAnimation.Pinwheel,
                    -> drawCuriousDialog(animation, entered, glow, cache)
                    else -> false
                }
            if (special) return@drawWithContent
            val frame = frames.frame(entered)
            clipRect(
                left = size.width * frame.insetX,
                top = size.height * frame.insetY,
                right = size.width * (1f - frame.insetX),
                bottom = size.height * (1f - frame.insetY),
            ) { this@drawWithContent.drawContent() }
            if (animation == DialogAnimation.Sheen) drawDialogSheen(entered, cache, frame)
        }.graphicsLayer()
}
