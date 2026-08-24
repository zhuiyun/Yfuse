package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core.playback.DolbyVisionP7ValidationEvidence
import com.yfuse.core.playback.DolbyVisionP7ValidationResult
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackSourceRequirements
import com.yfuse.core.playback.PlaybackVideoCodec
import com.yfuse.core.playback.detectPlaybackDiscKind
import com.yfuse.core.playback.evaluateDolbyVisionP7Output

/** True when this request must begin on the server output rather than the original file. */
internal fun PlayerMediaItem.startsWithServerTranscode(): Boolean =
    transcodeUrl.isNotBlank() &&
        playMethod == PlaybackMethod.Transcode &&
        !requiresLocalDolbyPipeline

/** Dolby decode/output belongs to the app; negotiated server transcode is only an explicit choice. */
internal val PlayerMediaVersion.requiresLocalDolbyPipeline: Boolean
    get() = !discSource && (dolbyVision || dolbyAtmos)

internal val PlayerMediaItem.requiresLocalDolbyPipeline: Boolean
    get() = activeVersion?.requiresLocalDolbyPipeline == true

/** Automatic recovery may use only a server-approved representation with a concrete URL. */
internal fun PlayerMediaItem.allowsServerTranscodeFallback(reason: String?): Boolean =
    (
        serverTranscodeSupported ||
            playMethod == PlaybackMethod.Transcode ||
            activeVersion?.serverTranscodeSupported == true
    ) &&
        (transcodeUrl.isNotBlank() || fallbackTranscodeUrl.isNotBlank()) &&
        (!requiresLocalDolbyPipeline || reason?.startsWith("用户手动") == true)

/** Evaluates P7 output from source-layer facts plus explicit runtime trace evidence. */
internal fun PlayerMediaVersion.dolbyVisionP7Output(diagnostics: PlaybackDiagnostics): DolbyVisionP7ValidationResult {
    val mpvEvidence = diagnostics.mpvDolbyRuntimeEvidence()
    return evaluateDolbyVisionP7Output(
        DolbyVisionP7ValidationEvidence(
            profile = dolbyProfile,
            sourceRpuPresent = sourceDolbyRpuPresent,
            sourceEnhancementLayerPresent = sourceDolbyEnhancementLayerPresent,
            sourceBaseLayerPresent = sourceDolbyBaseLayerPresent,
            outputBaseLayerDecoded = diagnostics.videoReadiness == PlaybackOutputReadiness.Rendering,
            outputRpuApplied = diagnostics.dolbyVisionRpuApplied || mpvEvidence.rpuRendered,
            outputEnhancementLayerComposed =
                diagnostics.dolbyVisionEnhancementLayerComposed || mpvEvidence.felComposed,
        ),
    )
}

/**
 * The method shown before an engine has enough runtime facts to refine it.
 *
 * A Dolby source may arrive from PlaybackInfo with PlayMethod=Transcode, while Yfuse intentionally
 * keeps the original URL and performs Dolby decode/output locally. In that case the server value is
 * only a negotiation recommendation; showing it as "服务器转码" would contradict the URL that the
 * engine actually opens.
 */
internal fun PlayerMediaItem.effectivePlaybackMethod(): PlaybackMethod =
    when {
        startsWithServerTranscode() -> PlaybackMethod.Transcode
        requiresLocalDolbyPipeline && playMethod == PlaybackMethod.Transcode -> PlaybackMethod.DirectPlay
        else -> playMethod
    }

/** Human-readable cause paired with the actual method, never inferred from a badge. */
internal fun PlayerMediaItem.initialFallbackReason(): String? =
    when {
        url.isYfuseNativeRemoteBluRayUrl() -> "远程 Blu-ray 原盘使用客户端随机块读取"
        forcedTranscodeReason != null && transcodeUrl.isNotBlank() -> forcedTranscodeReason
        requiresLocalDolbyPipeline && playMethod == PlaybackMethod.Transcode ->
            "服务器建议转码，已保留杜比原始流并由客户端本地解码"
        playMethod == PlaybackMethod.DirectStream -> "服务器协商为直串流"
        playMethod == PlaybackMethod.Transcode -> "服务器协商要求转码"
        else -> null
    }

/** Selects the server output before a backend can render an unsupported source frame. */
internal fun PlayerMediaItem.withForcedServerTranscode(reason: String): PlayerMediaItem {
    val preparedUrl = transcodeUrl.ifBlank { fallbackTranscodeUrl }
    if (preparedUrl.isBlank()) return this
    return copy(
        transcodeUrl = preparedUrl,
        playMethod = PlaybackMethod.Transcode,
        forcedTranscodeReason = reason,
    )
}

internal fun PlayerMediaVersion.sourceRequirements(): PlaybackSourceRequirements =
    PlaybackSourceRequirements(
        dolbyVision = dolbyVision,
        needsDolbyDecoder = needsDolbyDecoder,
        dynamicRange = sourceDynamicRange,
        dolbyVisionProfile = dolbyProfile,
        dolbyRpuPresent = sourceDolbyRpuPresent,
        dolbyEnhancementLayerPresent = sourceDolbyEnhancementLayerPresent,
        dolbyBaseLayerPresent = sourceDolbyBaseLayerPresent,
        dolbyBaseLayerCompatibilityId = sourceDolbyBaseLayerCompatibility,
        videoCodec = sourceVideoCodec.toPlaybackVideoCodec(),
        width = sourceWidth,
        height = sourceHeight,
        frameRate = sourceFrameRate,
        bitrateBitsPerSecond = sourceBitrateBps,
        bitDepth = sourceBitDepth,
        videoLevel = sourceVideoLevel,
    )

/** Fast PlaybackInfo-backed probe; FFmpeg probing can enrich the same core model later. */
internal fun PlayerMediaItem?.playbackMediaProbe(usingServerTranscode: Boolean = false): PlaybackMediaProbe {
    val item = this
    val version = item?.activeVersion
    val sourceUrl =
        if (usingServerTranscode) {
            item?.transcodeUrl
        } else {
            item?.url
        }.orEmpty()
    val nativeRemoteDisc = !usingServerTranscode && sourceUrl.isYfuseNativeRemoteBluRayUrl()
    val serverResolvedDiscMainFeature =
        version?.discSource == true &&
            !usingServerTranscode &&
            item?.playMethod == PlaybackMethod.DirectStream
    return PlaybackMediaProbe(
        container = version?.container,
        discSource = version?.discSource == true,
        source =
            version?.sourceRequirements()
                ?: PlaybackSourceRequirements(
                    dolbyVision = false,
                    needsDolbyDecoder = false,
                    dynamicRange = null,
                ),
        // The custom native route deliberately keeps transcode URLs on the item as a recovery
        // chain. They must not win initial disc planning, otherwise YCore would immediately undo
        // the registered raw-ISO route before libbluray gets one attempt.
        hasServerTranscode =
            !nativeRemoteDisc &&
                version?.requiresLocalDolbyPipeline != true &&
                item?.let { media ->
                    (
                        media.serverTranscodeSupported ||
                            media.playMethod == PlaybackMethod.Transcode ||
                            version?.serverTranscodeSupported == true
                    ) &&
                        (media.transcodeUrl.isNotBlank() || media.fallbackTranscodeUrl.isNotBlank())
                } == true,
        drmProtected = item?.drmConfiguration != null || version?.drmConfiguration != null,
        usingServerTranscode = usingServerTranscode,
        discKind =
            detectPlaybackDiscKind(
                container = version?.container,
                labelHint = version?.label,
                declaredDiscSource = version?.discSource == true,
            ),
        localSource =
            sourceUrl.startsWith("file://", ignoreCase = true) ||
                sourceUrl.startsWith("content://", ignoreCase = true),
        discMainFeatureResolved = serverResolvedDiscMainFeature,
        sourceSizeBytes = version?.sourceSizeBytes,
    )
}

private fun String?.toPlaybackVideoCodec(): PlaybackVideoCodec? {
    val normalized = this?.trim()?.lowercase().orEmpty()
    return PlaybackVideoCodec.entries.firstOrNull { codec ->
        normalized in codec.embyNames || codec.embyNames.any(normalized::startsWith)
    }
}

/**
 * The two badges the player shows over someone's film, and both are claims about their
 * hardware — so both are answered by the backend that did the rendering.
 *
 * They used to be recovered by substring-matching the diagnostic labels: 首帧已输出 and
 * 未声明支持 for the picture, 源码输出 plus Atmos or TrueHD for the sound. That made the
 * badges a property of how a sentence was worded, and reordering a label or translating the
 * app would have quietly turned them on or off. The engines already hold the facts — the
 * rendered range, the display's declared formats, the encoding handed to the audio track —
 * so they report them instead.
 */
internal fun PlaybackDiagnostics.hasActiveDolbyVisionOutput(): Boolean = dolbyVisionOutput

internal fun PlaybackDiagnostics.hasActiveDolbyAtmosOutput(): Boolean = dolbyAtmosOutput

internal fun PlayerMediaItem.sourceDynamicRange(transcoding: Boolean): String =
    activeVersion
        ?.sourceDynamicRange
        .orEmpty()
        .takeUnless { transcoding }
        .orEmpty()

internal fun PlayerMediaItem.sourceAudioFormat(transcoding: Boolean): String =
    activeVersion
        ?.sourceAudio
        .orEmpty()
        .takeUnless { transcoding }
        .orEmpty()

internal fun PlayerMediaItem.sourceVideoHeight(transcoding: Boolean): Int =
    activeVersion?.sourceHeight?.takeUnless { transcoding } ?: 0

internal fun initialPlaybackDiagnostics(
    engine: String,
    decoder: String,
    item: PlayerMediaItem?,
    transcoding: Boolean = item?.startsWithServerTranscode() == true,
): PlaybackDiagnostics =
    PlaybackDiagnostics(
        engine = engine,
        decoder = decoder,
        playMethod =
            item?.effectivePlaybackMethod()?.label
                ?: PlaybackMethod.DirectPlay.label,
        videoCodec = item?.activeVersion?.sourceVideoCodec?.uppercase() ?: "未知",
        videoWidth = item?.activeVersion?.sourceWidth?.takeUnless { transcoding } ?: 0,
        dynamicRange = item?.sourceDynamicRange(transcoding).orEmpty(),
        audioFormat = item?.sourceAudioFormat(transcoding).orEmpty(),
        fallbackReason = item?.initialFallbackReason(),
        bitrateBitsPerSecond =
            item
                ?.activeVersion
                ?.sourceBitrateBps
                ?.takeUnless { transcoding }
                ?.toLong()
                ?: 0L,
    )
