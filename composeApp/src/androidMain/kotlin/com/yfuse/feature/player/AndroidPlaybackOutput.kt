package com.yfuse.feature.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.text.TextOutput
import com.yfuse.core.data.PlaybackPreferences
import org.koin.core.context.GlobalContext
import java.util.ArrayList

/**
 * Media3 owns seamless frame-rate hints because it knows the decoded output rate. The explicit
 * non-seamless mode must instead disable Media3's hint and let the app call Surface.setFrameRate;
 * this is the contract documented by ExoPlayer.Builder.setVideoChangeFrameRateStrategy.
 */
@UnstableApi
internal fun exoVideoChangeFrameRateStrategy(mode: FrameRateMatchMode): Int =
    when (mode) {
        FrameRateMatchMode.SeamlessOnly -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        FrameRateMatchMode.Disabled,
        FrameRateMatchMode.Always,
        -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
    }

internal fun exoNeedsAppSurfaceFrameRate(mode: FrameRateMatchMode): Boolean = mode == FrameRateMatchMode.Always

/**
 * Selects between Media3's route-aware encoded sink and a PCM-only sink, and optionally wraps the
 * primary text renderer so PlayerView receives merged primary/secondary cues.
 *
 * The compatible audio path delegates to Media3, which refreshes AudioCapabilities when Android
 * changes the routed device. The disabled path deliberately uses the context-free builder whose
 * documented default capabilities contain no encoded passthrough formats, forcing normal
 * decoder-to-PCM audio.
 */
@UnstableApi
internal class ExoOutputRenderersFactory(
    context: Context,
    private val audioPassthroughMode: AudioPassthroughMode,
    private val dualSubtitleCueMerger: ExoDualSubtitleCueMerger? = null,
) : DefaultRenderersFactory(context) {
    init {
        val frameRateMode =
            runCatching {
                GlobalContext
                    .get()
                    .get<PlaybackPreferences>()
                    .frameRateMatch.value
                    .toPlayerMode()
            }.getOrDefault(FrameRateMatchMode.Disabled)
        if (exoNeedsAppSurfaceFrameRate(frameRateMode)) {
            ExoAlwaysFrameRateSurfaceBinder(context, frameRateMode)
        }
    }

    @Suppress("DEPRECATION")
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        if (audioPassthroughMode == AudioPassthroughMode.Compatible) {
            return super.buildAudioSink(
                context,
                enableFloatOutput,
                enableAudioTrackPlaybackParams,
            )
        }
        return DefaultAudioSink
            .Builder()
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        super.buildTextRenderers(
            context,
            dualSubtitleCueMerger?.primaryOutput(output) ?: output,
            outputLooper,
            extensionRendererMode,
            out,
        )
    }
}

/**
 * Requests a content frame rate from Android. [PlaybackOutputStatus.Requested] is intentional:
 * Surface.setFrameRate is a scheduler hint and the platform explicitly makes no activation promise.
 */
@SuppressLint("NewApi")
internal fun requestSurfaceFrameRate(
    surface: Surface,
    mode: FrameRateMatchMode,
    contentFrameRate: Float,
    androidApiLevel: Int = Build.VERSION.SDK_INT,
): PlaybackOutputStatus =
    when (val plan = surfaceFrameRatePlan(mode, contentFrameRate, androidApiLevel)) {
        SurfaceFrameRatePlan.Disabled -> PlaybackOutputStatus.Disabled
        is SurfaceFrameRatePlan.Unsupported -> PlaybackOutputStatus.Unsupported(plan.reason)
        is SurfaceFrameRatePlan.Invalid -> PlaybackOutputStatus.Rejected(plan.detail)
        is SurfaceFrameRatePlan.Apply ->
            runCatching {
                if (plan.useExplicitStrategyApi) {
                    surface.setFrameRate(
                        plan.frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        if (plan.allowNonSeamlessSwitch) {
                            Surface.CHANGE_FRAME_RATE_ALWAYS
                        } else {
                            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                        },
                    )
                } else {
                    surface.setFrameRate(
                        plan.frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    )
                }
            }.fold(
                onSuccess = {
                    PlaybackOutputStatus.Requested(
                        "Surface.setFrameRate(${plan.frameRate}) accepted; Android chooses the display rate",
                    )
                },
                onFailure = { error ->
                    PlaybackOutputStatus.Rejected(
                        error.message ?: error::class.simpleName ?: "Surface.setFrameRate failed",
                    )
                },
            )
    }

/** Releases a previous hint when a surface is detached or playback is rebuilt. */
@SuppressLint("NewApi")
internal fun clearSurfaceFrameRate(
    surface: Surface,
    androidApiLevel: Int = Build.VERSION.SDK_INT,
): PlaybackOutputStatus {
    if (androidApiLevel < ANDROID_FRAME_RATE_API) return PlaybackOutputStatus.Disabled
    return runCatching {
        if (androidApiLevel >= ANDROID_EXPLICIT_FRAME_RATE_STRATEGY_API) {
            surface.setFrameRate(
                0f,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
        } else {
            surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }.fold(
        onSuccess = { PlaybackOutputStatus.Disabled },
        onFailure = { error ->
            PlaybackOutputStatus.Rejected(
                error.message ?: error::class.simpleName ?: "clearing Surface frame rate failed",
            )
        },
    )
}

/** Interprets Media3's real AudioTrack configuration, not just the requested preference. */
@UnstableApi
internal fun exoAudioPassthroughStatus(
    mode: AudioPassthroughMode,
    audioTrackConfig: AudioSink.AudioTrackConfig,
): PlaybackOutputStatus {
    if (mode == AudioPassthroughMode.Disabled) return PlaybackOutputStatus.Disabled
    if (audioTrackConfig.offload) {
        return PlaybackOutputStatus.Inactive("Media3 selected audio offload, not route passthrough")
    }
    return if (Util.isEncodingLinearPcm(audioTrackConfig.encoding)) {
        PlaybackOutputStatus.Inactive("Media3 is outputting decoded PCM")
    } else {
        PlaybackOutputStatus.Active("Media3 encoded AudioTrack (${audioTrackConfig.encoding})")
    }
}

internal fun playbackOutputDiagnosticLabel(
    status: PlaybackOutputStatus,
    activeLabel: String? = null,
): String =
    when (status) {
        PlaybackOutputStatus.Disabled -> "PCM 解码输出 · 源码输出已关闭"
        is PlaybackOutputStatus.Active -> activeLabel ?: status.detail
        is PlaybackOutputStatus.Inactive ->
            if (status.detail.contains("PCM", ignoreCase = true)) {
                "PCM 解码输出"
            } else {
                status.detail
            }
        is PlaybackOutputStatus.Configured -> status.detail
        is PlaybackOutputStatus.Requested -> status.detail
        is PlaybackOutputStatus.Rejected -> "输出被拒绝 · ${status.detail}"
        is PlaybackOutputStatus.Unsupported -> "当前平台不支持"
    }

@OptIn(UnstableApi::class)
internal fun exoAudioEncodingLabel(encoding: Int): String =
    when (encoding) {
        C.ENCODING_AC3 -> "Dolby Digital"
        C.ENCODING_E_AC3 -> "Dolby Digital Plus"
        C.ENCODING_E_AC3_JOC -> "Dolby Atmos / E-AC-3 JOC"
        C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_AC4 -> "Dolby AC-4"
        C.ENCODING_AAC_LC,
        C.ENCODING_AAC_HE_V1,
        C.ENCODING_AAC_HE_V2,
        -> "AAC"
        else -> if (Util.isEncodingLinearPcm(encoding)) "PCM" else "编码 $encoding"
    }

/**
 * Encodings that carry Dolby object audio when passed through untouched.
 *
 * AC-4 can also carry objects, but the label rule these replaced only ever recognised JOC and
 * TrueHD, so the badge's meaning is left exactly where it was rather than widened silently.
 */
internal val DOLBY_OBJECT_ENCODINGS = setOf(C.ENCODING_E_AC3_JOC, C.ENCODING_DOLBY_TRUEHD)

/**
 * The same set as [DOLBY_OBJECT_ENCODINGS] for a backend that names its codec instead of
 * handing over an Android encoding constant. These are codec identifiers from the backend,
 * not text written for a person to read.
 */
internal fun isDolbyObjectAudioCodec(identifier: String?): Boolean {
    val normalized = identifier?.trim()?.lowercase().orEmpty()
    if (normalized.isEmpty()) return false
    return "truehd" in normalized || "atmos" in normalized
}
