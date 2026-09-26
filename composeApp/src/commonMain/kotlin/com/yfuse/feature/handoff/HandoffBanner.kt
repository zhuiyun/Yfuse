package com.yfuse.feature.handoff

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.DragProgress
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.designsystem.liquidGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rubberBand
import com.yfuse.core.designsystem.shadow
import com.yfuse.core.designsystem.solidGlass
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.handoff.HandoffElsewhere
import com.yfuse.core.handoff.HandoffUiState
import com.yfuse.watch.protocol.HandoffRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/** How long 在此继续 stays offered before the banner steps aside by itself. */
private const val ELSEWHERE_BANNER_MS = 15_000L

/** How long a failed transfer's reason stays up when nobody closes it. */
private const val FAILURE_BANNER_MS = 8_000L

/** The account client's own request limit; past it the reject has answered or never will. */
private const val BANNER_REJECT_ANSWER_MS = 15_000L

/** How far up the banner travels to be gone; a flick commits sooner (see [DragProgress]). */
private val BannerDismissDistance = 72.dp
private const val BANNER_DISMISS_FRACTION = 0.5f

/** What the 接力横幅 is showing. [key] tells one banner from the next. */
internal sealed interface HandoffBannerContent {
    val key: String

    /** Another device offers what it is playing: 接收 / 忽略. */
    data class Incoming(
        val request: HandoffRequest,
        /** Why the last 忽略 did not reach the service; the request is still waiting. */
        val rejectFailure: String? = null,
    ) : HandoffBannerContent {
        override val key: String get() = "incoming:${request.id}"
    }

    /** 在此继续 on its way; [preparing] once the other device has answered and this one is loading. */
    data class Continuing(
        val device: HandoffElsewhere,
        val preparing: Boolean,
    ) : HandoffBannerContent {
        override val key: String get() = "continuing:${device.sessionId}"
    }

    /** Why the last transfer to this device did not happen. */
    data class Failure(
        val reason: String,
    ) : HandoffBannerContent {
        override val key: String get() = "failure:$reason"
    }

    /** Another device of the account is playing something this one could continue: 在此继续 / 关闭. */
    data class Elsewhere(
        val device: HandoffElsewhere,
    ) : HandoffBannerContent {
        override val key: String get() = elsewhereBannerKey(device)
    }
}

/** A title closed on one device stays closed for that device; the next title it plays is offered again. */
internal fun elsewhereBannerKey(device: HandoffElsewhere): String = "elsewhere:${device.sessionId}:${device.mediaKey}"

/**
 * Which banner, if any. A transfer offered to this device comes first, then 在此继续 under way, then
 * why the last transfer failed, then another device's playback — only while this device could take
 * it, and not a title the viewer closed or [canContinue] rules out (another profile's). While an
 * offer is being refused ([hiddenRequestId]) nothing else takes its place.
 */
internal fun handoffBannerContent(
    state: HandoffUiState,
    closed: Set<String>,
    hiddenRequestId: String?,
    rejectFailure: String?,
    canContinue: (HandoffElsewhere) -> Boolean,
): HandoffBannerContent? {
    state.incoming?.takeIf { !state.busy }?.let { offered ->
        return if (offered.id == hiddenRequestId) null else HandoffBannerContent.Incoming(offered, rejectFailure)
    }
    state.continuing?.let { return HandoffBannerContent.Continuing(it, preparing = state.busy) }
    state.receiveFailure?.takeIf { state.incoming == null }?.let { return HandoffBannerContent.Failure(it) }
    if (state.busy || !state.canReceive) return null
    return state.playingElsewhere
        .firstOrNull { canContinue(it) && elsewhereBannerKey(it) !in closed }
        ?.let { HandoffBannerContent.Elsewhere(it) }
}

/** 「32:10」 or 「1:02:03」, where another device has got to. */
internal fun handoffBannerClock(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = seconds % 3_600L / 60L
    val tail = (seconds % 60L).toString().padStart(2, '0')
    return if (hours > 0L) "$hours:${minutes.toString().padStart(2, '0')}:$tail" else "$minutes:$tail"
}

/**
 * The 接力横幅's state, kept apart from what draws it so the platform can hold the drawing in a
 * window of its own and create that window only while [present].
 */
@Stable
class HandoffBannerState internal constructor(
    internal val controller: HandoffController,
) {
    internal val visibility = MutableTransitionState(false)
    internal var closed by mutableStateOf(emptySet<String>())
    internal var rejecting by mutableStateOf<String?>(null)
    internal var rejectFailure by mutableStateOf<Pair<String, String>?>(null)
    internal var errorBeforeReject: String? = null

    /** The banner being drawn; kept through its exit, after what it showed has gone. */
    internal var content by mutableStateOf<HandoffBannerContent?>(null)

    /** Up, or still on its way out. */
    val present: Boolean get() = visibility.currentState || visibility.targetState

    /**
     * 忽略 is a request to the account service, so the banner steps aside at once and comes back,
     * with the reason, only if the service did not take it.
     */
    internal fun reject(request: HandoffRequest) {
        if (rejecting != null) return
        val state = controller.state.value
        errorBeforeReject = state.error
        rejectFailure = null
        rejecting = request.id
        // Refused from that device, what it plays is not offered straight back as 在此继续.
        closed =
            closed + state.playingElsewhere.filter { it.sessionId == request.sourceSessionId }.map(::elsewhereBannerKey)
        controller.reject(request)
    }

    /** 忽略, 关闭, 取消 or 知道了 — what swiping the banner away means for what it shows. */
    internal fun dismiss(shown: HandoffBannerContent) {
        when (shown) {
            is HandoffBannerContent.Incoming -> reject(shown.request)
            is HandoffBannerContent.Continuing -> controller.cancelContinue()
            is HandoffBannerContent.Failure -> controller.dismissReceiveFailure()
            is HandoffBannerContent.Elsewhere -> closed = closed + shown.key
        }
    }

    /** 接收 or 在此继续; nothing for a banner that only reports. */
    internal fun confirm(shown: HandoffBannerContent) {
        when (shown) {
            is HandoffBannerContent.Incoming -> controller.accept(shown.request)
            is HandoffBannerContent.Elsewhere -> controller.continueHere(shown.device)
            is HandoffBannerContent.Continuing, is HandoffBannerContent.Failure -> Unit
        }
    }
}

/**
 * Follows [controller] for the banner. [canContinue] leaves out what this device could not take
 * over anyway, such as a title another profile is watching.
 */
@Composable
fun rememberHandoffBanner(
    controller: HandoffController,
    canContinue: (HandoffElsewhere) -> Boolean = { true },
): HandoffBannerState {
    val banner = remember(controller) { HandoffBannerState(controller) }
    val state by controller.state.collectAsState()
    val content =
        handoffBannerContent(
            state = state,
            closed = banner.closed,
            hiddenRequestId = banner.rejecting,
            rejectFailure = banner.rejectFailure?.takeIf { it.first == state.incoming?.id }?.second,
            canContinue = canContinue,
        )
    // The last banner is kept so it can still be drawn while it leaves.
    if (content != null) banner.content = content
    banner.visibility.targetState = content != null
    LaunchedEffect(banner.rejecting) {
        val id = banner.rejecting ?: return@LaunchedEffect
        // A confirmed reject takes the request away; a failed one writes the controller's error.
        val answer =
            withTimeoutOrNull(BANNER_REJECT_ANSWER_MS) {
                controller.state.first { it.incoming?.id != id || it.error != banner.errorBeforeReject }
            }
        if (answer != null && answer.incoming?.id == id) {
            banner.rejectFailure = id to (answer.error ?: "未能拒绝请求，请重试")
        }
        banner.rejecting = null
    }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(content?.key) {
        val shown = content ?: return@LaunchedEffect
        // Offers and reports step aside by themselves; a transfer waiting for an answer does not.
        val base =
            when (shown) {
                is HandoffBannerContent.Elsewhere -> ELSEWHERE_BANNER_MS
                is HandoffBannerContent.Failure -> FAILURE_BANNER_MS
                is HandoffBannerContent.Incoming, is HandoffBannerContent.Continuing -> return@LaunchedEffect
            }
        val recommended =
            accessibility?.calculateRecommendedTimeoutMillis(base, containsText = true, containsControls = true) ?: base
        if (recommended == Long.MAX_VALUE) return@LaunchedEffect
        delay(maxOf(base, recommended))
        banner.dismiss(shown)
    }
    return banner
}

/**
 * 接力横幅 — a transfer offered to this device, or another device's playback to continue here. It
 * takes nothing from the page: no scrim, no focus. It leaves by its own buttons, by a swipe up, or
 * when its moment has passed, and a screen reader reaches the same two actions from the banner.
 * Arriving is silent; 接收 and 在此继续 answer with [HapticSignal.Confirm].
 */
@Composable
fun HandoffBanner(
    banner: HandoffBannerState,
    modifier: Modifier = Modifier,
) {
    val shown = banner.content ?: return
    val still = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val fade = if (still) Motion.REDUCED_FADE else Motion.STANDARD
    AnimatedVisibility(
        visibleState = banner.visibility,
        modifier = modifier,
        enter =
            if (still) {
                fadeIn(Motion.tween(fade))
            } else {
                fadeIn(Motion.tween(fade)) + slideInVertically(Motion.tween(Motion.EMPHASIZED)) { -it }
            },
        exit =
            if (still) {
                fadeOut(Motion.tween(fade))
            } else {
                fadeOut(Motion.tween(fade)) + slideOutVertically(Motion.tween(Motion.STANDARD)) { -it }
            },
    ) {
        HandoffBannerCard(banner, shown, still)
    }
}

private class BannerAction(
    val label: String,
    val confirms: Boolean,
    val run: () -> Unit,
)

@Composable
private fun HandoffBannerCard(
    banner: HandoffBannerState,
    shown: HandoffBannerContent,
    still: Boolean,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    val haptics = LocalHaptics.current
    val icon: ImageVector
    val title: String
    val detail: String?
    val secondary: BannerAction
    val primary: BannerAction?
    when (shown) {
        is HandoffBannerContent.Incoming -> {
            icon = AppIcons.Cast
            title = "${shown.request.sourceName}请求在此继续观看"
            detail = shown.rejectFailure ?: "接收后会先检查影片是否可用"
            secondary = BannerAction("忽略", confirms = false) { banner.dismiss(shown) }
            primary = BannerAction("接收", confirms = true) { banner.confirm(shown) }
        }
        is HandoffBannerContent.Continuing -> {
            icon = AppIcons.Cast
            title =
                if (shown.preparing) {
                    "正在接过《${shown.device.title}》"
                } else {
                    "正在请求${shown.device.deviceName}接力"
                }
            detail = if (shown.preparing) "这里准备好后，${shown.device.deviceName}才会暂停" else "《${shown.device.title}》"
            secondary = BannerAction("取消", confirms = false) { banner.dismiss(shown) }
            primary = null
        }
        is HandoffBannerContent.Failure -> {
            icon = AppIcons.Info
            title = "未能接收播放"
            detail = shown.reason
            secondary = BannerAction("知道了", confirms = false) { banner.dismiss(shown) }
            primary = null
        }
        is HandoffBannerContent.Elsewhere -> {
            icon = AppIcons.Cast
            title = "${shown.device.deviceName}正在播放《${shown.device.title}》"
            detail = "看到 ${handoffBannerClock(shown.device.positionMs)}"
            secondary = BannerAction("关闭", confirms = false) { banner.dismiss(shown) }
            primary = BannerAction("在此继续", confirms = true) { banner.confirm(shown) }
        }
    }
    val warning =
        shown is HandoffBannerContent.Failure || (shown as? HandoffBannerContent.Incoming)?.rejectFailure != null

    // Follows the finger up; downwards it only gives a little, on the rubber band.
    var raw by remember(shown.key) { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val extent = with(LocalDensity.current) { BannerDismissDistance.toPx() }
    val offset by animateFloatAsState(
        if (raw < 0f) raw else rubberBand(raw, extent),
        if (dragging) snap() else Motion.settle(still),
        label = "handoffBannerDrag",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp)
            .padding(horizontal = Dimens.pageHorizontal)
            .graphicsLayer {
                translationY = offset
                alpha = (1f + offset / (extent * 2f)).coerceIn(0.3f, 1f)
            }.draggable(
                state = rememberDraggableState { raw += it },
                orientation = Orientation.Vertical,
                onDragStarted = { dragging = true },
                onDragStopped = { velocity ->
                    dragging = false
                    // Upwards is towards dismissing, so both are measured that way; a flick counts.
                    val release = DragProgress(offset = -raw, velocity = -velocity, extent = extent)
                    if (release.commits(BANNER_DISMISS_FRACTION)) {
                        banner.dismiss(shown)
                    } else {
                        raw = 0f
                    }
                },
            ).semantics(mergeDescendants = true) {
                paneTitle = "设备接力"
                liveRegion = LiveRegionMode.Polite
                customActions =
                    listOfNotNull(primary, secondary).map { action ->
                        CustomAccessibilityAction(action.label) {
                            if (action.confirms) haptics.play(HapticSignal.Confirm)
                            action.run()
                            true
                        }
                    }
                dismiss(secondary.label) {
                    secondary.run()
                    true
                }
            }.shadow(Shadows.tabBar, AppShapes.card)
            .solidGlass(AppShapes.card, palette.card, palette.border),
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent.accent, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = AppTypography.body.strong,
                    color = palette.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                detail?.let {
                    Text(
                        it,
                        style = AppTypography.caption.regular,
                        color = if (warning) palette.error else palette.sub2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            BannerButton(secondary, prominent = false)
            primary?.let { BannerButton(it, prominent = true) }
        }
        // The grip says the banner can be pushed away.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 5.dp)
                .width(28.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(palette.hint.copy(alpha = 0.45f)),
        )
    }
}

@Composable
private fun BannerButton(
    action: BannerAction,
    prominent: Boolean,
) {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current
    Text(
        action.label,
        style = AppTypography.caption.strong,
        color = if (prominent) accent.accent else palette.text,
        maxLines = 1,
        modifier =
            Modifier
                .pressable(
                    haptic = if (action.confirms) HapticSignal.Confirm else null,
                    onClick = action.run,
                ).touchTarget()
                .liquidGlass(
                    shape = AppShapes.pill,
                    fill = if (prominent) accent.container else palette.glassStrong,
                    border = if (prominent) accent.border else palette.border,
                ).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
