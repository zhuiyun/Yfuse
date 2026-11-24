package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core.logging.diagnosticRootCause
import com.yfuse.core.logging.diagnosticTypeName
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerFactory
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.learning.YPlaybackLearningKey
import com.yfuse.core2.quirk.YCore2FailureKey
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal class AndroidCore2PlayerFactory(
    private val context: Context,
) : YPlayerFactory {
    override fun create(request: YPlayerOpenRequest): YPlayer {
        val discFactory =
            AndroidYCoreDiscRouteFactory(
                context = context,
                allowAudioPassthrough = true,
                frameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
            )
        return AndroidAdaptiveCore2YPlayer(
            context = context,
            request = request,
            discRouteFactory = discFactory,
        )
    }
}

internal const val TUNNEL_SPEED_EPSILON = 0.001f
internal const val NO_PENDING_SEEK_MS = -1L
internal const val RELEASE_JOIN_TIMEOUT_MS = 5_000L

/**
 * What a recovery attempt of a start that has not rendered is guaranteed, however little of the
 * start's own deadline is left. The failed route has just read the source, and probe results are
 * cached for the session, so this covers the new route's open rather than a second full probe.
 */
internal const val RECOVERY_PROBE_ALLOWANCE_MS = 20_000L

/** Throwable names survive release obfuscation (see diagnosticTypeName); the message never enters. */
internal fun core2RouterFailureReason(failure: Throwable): String =
    "Core2 router failed at ${failure.diagnosticTypeName()}"

/** True when a recovery attempt with [remainingStartMs] of the start's deadline left needs its own. */
internal fun yCoreRecoveryNeedsFreshProbeBudget(
    remainingStartMs: Long,
    allowanceMs: Long = RECOVERY_PROBE_ALLOWANCE_MS,
): Boolean = remainingStartMs < allowanceMs

/**
 * Whether a failed attempt ended because its start ran out of time rather than on the media.
 *
 * A reported failure is decided by its root cause. Without one, only an unclassified failure after
 * the attempt's deadline stopped counts: a typed failure says what went wrong, deadline or not.
 */
internal fun yCoreAttemptRanOutOfTime(
    reported: Throwable?,
    category: YPlaybackFailureCategory?,
    attemptDeadlineStopped: Boolean,
): Boolean =
    if (reported != null) {
        reported.diagnosticRootCause().let { it is AndroidProbeAbortedException && it.reason == "deadline" }
    } else {
        attemptDeadlineStopped && (category == null || category == YPlaybackFailureCategory.Unknown)
    }

/** Failure categories that say something about this media or device, unlike transport or deadline failures. */
private val CONCRETE_ROUTE_FAILURES =
    setOf(
        YPlaybackFailureCategory.Authorization,
        YPlaybackFailureCategory.Drm,
        YPlaybackFailureCategory.Container,
        YPlaybackFailureCategory.Decoder,
        YPlaybackFailureCategory.Renderer,
        YPlaybackFailureCategory.AudioSink,
    )

/**
 * One route's failure as the router keeps it once the rebuild after it cleared the visible error.
 *
 * [message] is the localized text the route published, never a throwable message; [reason] names
 * the route, the stage and the typed safe detail, which by contract carries no URL or credential.
 */
internal data class YCoreRouteFailure(
    val route: YPlaybackRoute,
    val category: YPlaybackFailureCategory,
    val message: String,
    val reason: String,
) {
    val concrete: Boolean get() = category in CONCRETE_ROUTE_FAILURES
}

/** Builds the kept failure from a child's Failed state and, when the child reports it, the typed failure. */
internal fun yCoreRouteFailure(
    route: YPlaybackRoute,
    category: YPlaybackFailureCategory?,
    message: String?,
    reason: String?,
    reported: Throwable?,
): YCoreRouteFailure? {
    val typed = reported as? YPlaybackException
    val resolvedCategory = typed?.category ?: category ?: return null
    val resolvedMessage =
        when {
            typed.isHiddenServerAudioTrackFailure() -> YCORE_HIDDEN_AUDIO_TRACK_MESSAGE
            !message.isNullOrBlank() -> message
            else -> yCoreEnhancedFailureMessage(typed)
        }
    val resolvedReason =
        typed?.let { failure ->
            buildString {
                append(route.name)
                append(" failed at ")
                append(failure.stage.name)
                failure.safeDetail?.takeIf(String::isNotBlank)?.let { detail ->
                    append(": ")
                    append(detail)
                }
            }
        } ?: reason?.takeIf(String::isNotBlank) ?: "${route.name} failed"
    return YCoreRouteFailure(
        route = route,
        category = resolvedCategory,
        message = resolvedMessage,
        reason = resolvedReason,
    )
}

/**
 * The state a start that stops on a failed attempt publishes.
 *
 * A previous demuxer's limitation does not prove that the enhanced path cannot decode the file.
 * Keep that evidence in diagnostics, but report the deadline that ended the current attempt.
 */
internal fun YPlayerState.withStartFailure(
    attemptRanOutOfTime: Boolean,
    kept: YCoreRouteFailure?,
): YPlayerState {
    if (phase != YPlaybackPhase.Failed || !attemptRanOutOfTime) return this
    return copy(
        error = YCORE_STARTUP_TIMEOUT_MESSAGE,
        errorCategory = YPlaybackFailureCategory.Network,
        diagnostics =
            diagnostics.copy(
                reason =
                    listOfNotNull(
                        kept?.reason,
                        "${diagnostics.route.name} ran out of its startup deadline",
                    ).joinToString("; "),
            ),
    )
}

internal data class YCoreUnavailableError(
    val message: String,
    val category: YPlaybackFailureCategory,
    val reason: String,
)

/**
 * What a start publishes when the router itself cannot produce another route.
 *
 * A typed source failure the router hit is current evidence and wins. A deadline remains a
 * deadline even when an earlier demuxer hid a track. Failure remains in YCore and retains the
 * most actionable category; it never silently hands the item to an external playback engine.
 */
internal fun yCoreUnavailableError(
    routerReason: String,
    sourceFailure: YPlaybackException?,
    keptFailure: YCoreRouteFailure?,
    protectedContent: Boolean,
    probeTimedOut: Boolean,
    currentError: String?,
    currentCategory: YPlaybackFailureCategory?,
): YCoreUnavailableError {
    val kept = keptFailure?.takeIf { it.concrete }
    return when {
        sourceFailure != null ->
            YCoreUnavailableError(yCoreEnhancedFailureMessage(sourceFailure), sourceFailure.category, routerReason)
        probeTimedOut && !protectedContent ->
            YCoreUnavailableError(
                message = YCORE_STARTUP_TIMEOUT_MESSAGE,
                category = YPlaybackFailureCategory.Network,
                reason = listOfNotNull(kept?.reason, "startup deadline: $routerReason").joinToString("; "),
            )
        kept != null ->
            YCoreUnavailableError(kept.message, kept.category, "${kept.reason}; recovery ended: $routerReason")
        protectedContent ->
            YCoreUnavailableError(
                message =
                    if (probeTimedOut) {
                        "受保护片源起播超时，请检查服务器或网络后重试"
                    } else {
                        "YCore 2.0 无法打开当前受保护片源，设备未提供可执行的安全解码路径"
                    },
                category =
                    when {
                        probeTimedOut -> YPlaybackFailureCategory.Network
                        else -> currentCategory ?: YPlaybackFailureCategory.Unknown
                    },
                reason = routerReason,
            )
        else ->
            YCoreUnavailableError(
                message = currentError ?: "YCore 2.0 纯内核路径无法打开当前片源",
                category = currentCategory ?: YPlaybackFailureCategory.Unknown,
                reason = routerReason,
            )
    }
}

/**
 * Whether FallbackToSoftware has a route to build for this input (see createInternalSoftwareRoute).
 * Dolby input needs a compatible base or an identified single-layer Profile 5 stream for RPU conversion.
 */
internal fun yCoreSoftwareRecoveryAvailable(
    protectedContent: Boolean,
    inputHdrType: YHdrType,
    dolbyConfig: YDolbyVisionConfig? = null,
): Boolean =
    !protectedContent &&
        yCoreInternalSoftwareRecoveryPlan(inputHdrType, dolbyConfig) != null

internal fun AndroidProbeBudget.remainingMsOrZero(): Long =
    try {
        remainingMs()
    } catch (_: AndroidProbeAbortedException) {
        0L
    }

/** Stopped for any reason, deadline included; a budget completed by rendered output never is. */
internal fun AndroidProbeBudget.hasStopped(): Boolean =
    try {
        ensureActive()
        false
    } catch (_: AndroidProbeAbortedException) {
        true
    }

/** A router's own view of Default: its coroutines run one at a time, so its plain state has one owner. */
internal fun androidCore2RouterDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

internal fun shouldRetryActiveNativeChildInPlace(
    phase: YPlaybackPhase,
    route: YPlaybackRoute,
    enhancedChild: Boolean = false,
): Boolean =
    phase != YPlaybackPhase.Failed &&
        canRetryCore2RouteInPlace(route, enhancedChild)

internal fun canRetryCore2RouteInPlace(
    route: YPlaybackRoute,
    enhancedChild: Boolean = false,
): Boolean =
    (route == YPlaybackRoute.SoftwareFallback && enhancedChild) ||
        route in
        setOf(
            YPlaybackRoute.NativeTunnel,
            YPlaybackRoute.NativeDirect,
            YPlaybackRoute.NativeEnhanced,
            YPlaybackRoute.GpuEnhanced,
        )

internal const val MIN_LEARNING_PLAYBACK_MS = 30_000L
internal const val MAX_CONSECUTIVE_NETWORK_RECOVERY_ATTEMPTS = 2
internal const val MICROSECONDS_PER_MILLISECOND = 1_000L

internal fun YCore2FailureKey.toLearningKey(): YPlaybackLearningKey =
    YPlaybackLearningKey(
        route = route,
        container = container,
        videoCodec = videoCodec,
        hdrType = hdrType,
        decoderName = decoderName,
    )

internal fun YPlaybackFailureCategory?.allowsCore2LocalSoftwareFallback(): Boolean =
    this == null ||
        this == YPlaybackFailureCategory.Container ||
        this == YPlaybackFailureCategory.Decoder ||
        this == YPlaybackFailureCategory.Renderer ||
        this == YPlaybackFailureCategory.AudioSink ||
        this == YPlaybackFailureCategory.Unknown

internal fun YPlaybackPlan.toSoftwareFallbackPlan(reason: String): YPlaybackPlan {
    val needsOwnedToneMap =
        !usesHdrFallback && inputHdrType in setOf(YHdrType.Hdr10, YHdrType.Hdr10Plus, YHdrType.Hlg)
    return copy(
        route = YPlaybackRoute.SoftwareFallback,
        demuxPath = YDemuxPath.Enhanced,
        decodePath = YDecodePath.Software,
        renderPath = YRenderPath.Gpu,
        outputHdrType = if (needsOwnedToneMap) YHdrType.Sdr else outputHdrType,
        decoderName = null,
        audioPath = if (audioPath == YAudioOutputPath.None) YAudioOutputPath.None else YAudioOutputPath.DecodePcm,
        softwareAudioDecode = softwareAudioDecode || !nativeAudio,
        softwareVideoToneMap = needsOwnedToneMap,
        reason = reason,
    )
}

internal fun yCoreInternalEnhancedRecoveryPlan(
    inputHdrType: YHdrType,
    decoderName: String? = null,
    audioPath: YAudioOutputPath = YAudioOutputPath.DecodePcm,
): YPlaybackPlan =
    YPlaybackPlan(
        route = YPlaybackRoute.NativeEnhanced,
        demuxPath = YDemuxPath.Enhanced,
        decodePath = YDecodePath.Hardware,
        renderPath = YRenderPath.SurfaceDirect,
        outputHdrType = inputHdrType,
        inputHdrType = inputHdrType,
        decoderName = decoderName,
        nativeAudio = true,
        audioPath = audioPath,
        reason = "YCore internal enhanced demux and MediaCodec recovery",
    )

internal fun yCoreInternalSoftwareRecoveryPlan(
    inputHdrType: YHdrType,
    dolbyConfig: YDolbyVisionConfig? = null,
): YPlaybackPlan? {
    val compatibleBase = dolbyConfig?.takeIf { it.profile in setOf(7, 8, 10) }?.compatibleBaseHdr
    val decodeHdr =
        when {
            inputHdrType != YHdrType.DolbyVision -> inputHdrType
            dolbyConfig?.singleLayerProfile5WithRpu == true -> YHdrType.DolbyVision
            else -> compatibleBase ?: return null
        }
    return YPlaybackPlan(
        route = YPlaybackRoute.SoftwareFallback,
        demuxPath = YDemuxPath.Enhanced,
        decodePath = YDecodePath.Software,
        renderPath = YRenderPath.Gpu,
        outputHdrType = YHdrType.Sdr,
        inputHdrType = decodeHdr,
        nativeAudio = false,
        audioPath = YAudioOutputPath.DecodePcm,
        softwareAudioDecode = true,
        softwareVideoToneMap = decodeHdr != YHdrType.Sdr,
        usesHdrFallback = inputHdrType == YHdrType.DolbyVision,
        reason =
            if (decodeHdr == YHdrType.DolbyVision) {
                "YCore HEVC software decode with Profile 5 RPU colour conversion to SDR"
            } else {
                "YCore internal FFmpeg software decode recovery"
            },
    )
}

internal fun YMediaItem.hintedHdrType(): YHdrType {
    val range =
        sourceHints
            ?.dynamicRange
            .orEmpty()
            .trim()
            .lowercase()
    return when {
        sourceHints?.dolbyVision == true || "dolby" in range || range.startsWith("dv") ->
            YHdrType.DolbyVision
        "hdr10+" in range || "hdr10plus" in range -> YHdrType.Hdr10Plus
        "hlg" in range -> YHdrType.Hlg
        "hdr" in range -> YHdrType.Hdr10
        else -> YHdrType.Sdr
    }
}

internal fun yCoreSoftwarePlanExecutable(
    plan: YPlaybackPlan,
    dolbyConfig: YDolbyVisionConfig? = null,
): Boolean {
    if (!yCoreSoftwarePlanPassesDolbyGuard(plan, dolbyConfig)) return false
    return runCatching {
        AndroidFfmpegDemuxer().let { demuxer ->
            demuxer.available &&
                demuxer.softwareDecodeAvailable &&
                (plan.inputHdrType != YHdrType.DolbyVision || FfmpegNativeBridge.softwareProfile5Available)
        }
    }.getOrDefault(false)
}

/**
 * Software output uses either a confirmed compatible base or per-frame Profile 5 RPU conversion.
 * A hardware video decoder with software audio follows the platform's Dolby path.
 */
internal fun yCoreSoftwarePlanPassesDolbyGuard(
    plan: YPlaybackPlan,
    dolbyConfig: YDolbyVisionConfig? = null,
): Boolean {
    if (plan.decodePath != YDecodePath.Software) return true
    if (plan.outputHdrType == YHdrType.DolbyVision) return false
    if (plan.inputHdrType == YHdrType.DolbyVision) {
        return dolbyConfig?.singleLayerProfile5WithRpu == true &&
            plan.usesHdrFallback &&
            plan.softwareVideoToneMap &&
            plan.outputHdrType == YHdrType.Sdr
    }
    if (!plan.usesHdrFallback) return true
    return dolbyConfig?.profile in setOf(7, 8, 10) &&
        dolbyConfig?.compatibleBaseHdr == plan.inputHdrType &&
        plan.outputHdrType == YHdrType.Sdr &&
        plan.softwareVideoToneMap == (plan.inputHdrType != YHdrType.Sdr)
}
