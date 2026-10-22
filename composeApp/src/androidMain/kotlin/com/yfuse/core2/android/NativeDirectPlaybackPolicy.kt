package com.yfuse.core2.android

import com.yfuse.core2.api.YOutputEvidenceResetReason
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.invalidateOutputEvidence
import com.yfuse.core2.api.yPlaybackStage
import kotlinx.coroutines.CancellationException

internal fun invalidateNativeDirectAudioRoute(
    diagnostics: YPlayerDiagnostics,
    preservePausedVideo: Boolean,
): YPlayerDiagnostics {
    val reset = diagnostics.invalidateOutputEvidence(YOutputEvidenceResetReason.AudioRouteChanged)
    return if (preservePausedVideo) {
        reset.copy(
            videoOutputVerified = diagnostics.videoOutputVerified,
            dolbyVisionOutput = diagnostics.dolbyVisionOutput,
            dolbyVisionRpuApplied = diagnostics.dolbyVisionRpuApplied,
            dolbyVisionEnhancementLayerDelivered = diagnostics.dolbyVisionEnhancementLayerDelivered,
            dolbyVisionFelComposed = diagnostics.dolbyVisionFelComposed,
        )
    } else {
        reset
    }
}

internal enum class YDecodedAudioDrainProgress {
    Backpressured,
    Pending,
    Complete,
}

internal fun decodedAudioDrainProgress(
    writtenBytes: Int,
    remainingBytes: Int,
): YDecodedAudioDrainProgress {
    require(writtenBytes >= 0)
    require(remainingBytes >= 0)
    return when {
        remainingBytes == 0 -> YDecodedAudioDrainProgress.Complete
        writtenBytes == 0 -> YDecodedAudioDrainProgress.Backpressured
        else -> YDecodedAudioDrainProgress.Pending
    }
}

internal fun validateNativeDirectDolbyIdentity(
    required: Boolean,
    extractedMime: String?,
) {
    if (!required || extractedMime.equals(DOLBY_VISION_MIME, ignoreCase = true)) return
    throw YPlaybackException(
        category = YPlaybackFailureCategory.Container,
        stage = YPlaybackFailureStage.Bitstream,
        safeDetail = "NativeDirect source did not expose a Dolby Vision track identity",
    )
}

internal fun yCoreNativeDirectFailureMessage(failure: YPlaybackException?): String =
    when (failure?.category) {
        YPlaybackFailureCategory.Authorization -> "YCore 2.0 片源授权已失效，请刷新播放地址后重试"
        YPlaybackFailureCategory.Drm -> "YCore 2.0 无法建立当前片源的 DRM 会话"
        YPlaybackFailureCategory.Network -> "YCore 2.0 无法连接片源，请检查服务器或网络"
        YPlaybackFailureCategory.Container ->
            when {
                failure.stage == YPlaybackFailureStage.Bitstream ->
                    "YCore 2.0 无法验证当前片源的杜比视界配置"
                failure.isHiddenServerAudioTrackFailure() -> YCORE_HIDDEN_AUDIO_TRACK_MESSAGE
                else -> "YCore 2.0 原生解封装无法识别当前片源"
            }
        YPlaybackFailureCategory.Decoder -> "YCore 2.0 无法启动当前视频解码器"
        YPlaybackFailureCategory.Renderer -> "YCore 2.0 无法建立视频输出"
        YPlaybackFailureCategory.AudioSink -> "YCore 2.0 无法建立音频输出"
        YPlaybackFailureCategory.Unknown,
        null,
        -> "YCore 2.0 原生播放失败，请导出诊断日志"
    }

/**
 * [yPlaybackStage] for NativeDirect's codec and sink calls, except that the timestamp-identity
 * signal passes through untouched: the pump catches it by type to retry the decoder with original
 * timestamps, and a wrapped one would fail playback instead.
 */
internal inline fun <T> nativeDirectStage(
    category: YPlaybackFailureCategory,
    stage: YPlaybackFailureStage,
    safeDetail: String,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: YPlaybackException) {
        throw failure
    } catch (failure: CodecTimestampIdentityException) {
        throw failure
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        throw YPlaybackException(
            category = category,
            stage = stage,
            safeDetail = safeDetail,
            cause = failure,
        )
    }

/**
 * Whether attaching a Surface to a prepared NativeDirect session has to seek the source.
 *
 * The Surface usually arrives just after prepare(), before anything was read. 1.0.83 still flushed
 * both never-fed decoders, re-seeked the extractor to where it already stood and cancelled its
 * reads; 14 ms later the tablet in incident E failed startup with an IllegalStateException, the
 * same sequence 1.0.81 logged three times. A seek is needed only once a sample was consumed after
 * the source was last positioned, or when the position to resume from has moved.
 */
internal fun nativeDirectSurfaceAttachNeedsSeek(
    inputConsumedSinceReposition: Boolean,
    sourcePositionedAtUs: Long,
    resumeUs: Long,
): Boolean = inputConsumedSinceReposition || sourcePositionedAtUs != resumeUs

/**
 * Whether a failed NativeDirect session may keep its extractor and transport for a same-item retry.
 *
 * Only a failure typed to the decode or output side qualifies: the source demonstrably worked. An
 * untyped failure, a source, demux, bitstream or seek stage, and a network, container or DRM
 * category all rebuild the source as before.
 */
internal fun nativeDirectFailureKeepsSource(failure: Throwable): Boolean {
    val typed = failure as? YPlaybackException ?: return false
    if (typed.stage in NATIVE_DIRECT_SOURCE_STAGES) return false
    return typed.category in NATIVE_DIRECT_DECODE_SIDE_CATEGORIES
}

private val NATIVE_DIRECT_SOURCE_STAGES =
    setOf(
        YPlaybackFailureStage.SourceOpen,
        YPlaybackFailureStage.Demux,
        YPlaybackFailureStage.Bitstream,
        YPlaybackFailureStage.Seek,
        YPlaybackFailureStage.Unknown,
    )

private val NATIVE_DIRECT_DECODE_SIDE_CATEGORIES =
    setOf(
        YPlaybackFailureCategory.Decoder,
        YPlaybackFailureCategory.Renderer,
        YPlaybackFailureCategory.AudioSink,
    )

internal fun secureSurfaceRequirementSatisfied(
    protectedContent: Boolean,
    outputSecure: Boolean,
): Boolean = !protectedContent || outputSecure

/**
 * Drops superseded control work before it reaches MediaExtractor/MediaCodec. This turns a scrub
 * gesture into one seek/flush operation while preserving barriers such as pause, item switch and
 * track selection.
 */
internal fun coalesceNativeDirectCommands(
    commands: List<AndroidNativeDirectYPlayer.Command>,
): List<AndroidNativeDirectYPlayer.Command> =
    commands.fold(mutableListOf()) { result, command ->
        val previous = result.lastOrNull()
        if (previous != null && previous.canBeReplacedBy(command)) {
            result[result.lastIndex] = command
        } else {
            result += command
        }
        result
    }

private fun AndroidNativeDirectYPlayer.Command.canBeReplacedBy(next: AndroidNativeDirectYPlayer.Command): Boolean =
    when (this) {
        is AndroidNativeDirectYPlayer.Command.ExternalSubtitleReady -> false
        is AndroidNativeDirectYPlayer.Command.Seek -> next is AndroidNativeDirectYPlayer.Command.Seek
        is AndroidNativeDirectYPlayer.Command.SetSpeed -> next is AndroidNativeDirectYPlayer.Command.SetSpeed
        is AndroidNativeDirectYPlayer.Command.SetAudioDelay -> next is AndroidNativeDirectYPlayer.Command.SetAudioDelay
        is AndroidNativeDirectYPlayer.Command.SetAudioEffect ->
            next is AndroidNativeDirectYPlayer.Command.SetAudioEffect
        is AndroidNativeDirectYPlayer.Command.SetVideoOutput ->
            next is AndroidNativeDirectYPlayer.Command.SetVideoOutput && detached == null
        is AndroidNativeDirectYPlayer.Command.SelectAudioTrack ->
            next is AndroidNativeDirectYPlayer.Command.SelectAudioTrack
        is AndroidNativeDirectYPlayer.Command.SelectSubtitleTrack ->
            next is AndroidNativeDirectYPlayer.Command.SelectSubtitleTrack && secondary == next.secondary
        is AndroidNativeDirectYPlayer.Command.SelectItem -> next is AndroidNativeDirectYPlayer.Command.SelectItem
        AndroidNativeDirectYPlayer.Command.Prepare -> next == AndroidNativeDirectYPlayer.Command.Prepare
        AndroidNativeDirectYPlayer.Command.Play -> next == AndroidNativeDirectYPlayer.Command.Play
        AndroidNativeDirectYPlayer.Command.Pause -> next == AndroidNativeDirectYPlayer.Command.Pause
    }

internal fun YVideoDecoderAttemptFailure.safeDiagnosticLabel(): String =
    buildString {
        append(decoderName)
        append(':')
        append(diagnosticInfo ?: errorType)
        errorCode?.let { code ->
            append(':')
            append(code)
        }
        if (recoverable) append(":recoverable")
        if (transient) append(":transient")
    }
