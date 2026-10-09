package com.yfuse.feature.player

/**
 * How an engine turns what it saw of its own output (a first frame drawn, an AudioTrack opened, a
 * sink released, frames dropped) into the readiness and evidence it reports. Exo, mpv and MDK each
 * observe their backend in their own way and feed the observation here, so that "verified" means
 * the same thing, and a new load attempt starts from the same evidence, whichever engine plays.
 *
 * Labels stay with the engines: they describe the backend, and only the machine-readable fields
 * are shared.
 */
internal object PlaybackRenderEvidence {
    /** An output the engine has seen working is rendering; any other it still waits for. */
    fun readiness(verified: Boolean): PlaybackOutputReadiness =
        if (verified) PlaybackOutputReadiness.Rendering else PlaybackOutputReadiness.Waiting

    /**
     * As [readiness], for a player that can also say its output was torn down: an idle YCore
     * session has released its sinks, so it waits for nothing.
     */
    fun readiness(
        verified: Boolean,
        released: Boolean,
    ): PlaybackOutputReadiness =
        when {
            verified -> PlaybackOutputReadiness.Rendering
            released -> PlaybackOutputReadiness.Released
            else -> PlaybackOutputReadiness.Waiting
        }

    /** A verified output is confirmed; anything short of that was only requested. */
    fun confidence(verified: Boolean): PlaybackEvidenceConfidence =
        if (verified) PlaybackEvidenceConfidence.Confirmed else PlaybackEvidenceConfidence.Requested
}

/**
 * The evidence of a new load attempt on [renderApi]: a fresh [PlaybackOutputEvidence.sessionRevision]
 * (always one past this one, so it only grows within a playback session), no output claimed, and
 * both outputs requested. [audioObservable] is false for an engine that cannot see its audio sink,
 * whose audio confidence stays Unknown rather than Requested.
 */
internal fun PlaybackOutputEvidence.nextLoadAttempt(
    renderApi: PlaybackVideoRenderApi,
    audioObservable: Boolean = true,
): PlaybackOutputEvidence =
    nextSession().copy(
        videoConfidence = PlaybackEvidenceConfidence.Requested,
        audioConfidence =
            if (audioObservable) PlaybackEvidenceConfidence.Requested else PlaybackEvidenceConfidence.Unknown,
        renderApi = renderApi,
    )

/**
 * The video sink was torn down, labelled [videoOutput]. The release is itself observed, so it is
 * confirmed, and nothing drawn before it still counts as output.
 */
internal fun PlaybackDiagnostics.withVideoOutputReleased(videoOutput: String): PlaybackDiagnostics =
    copy(
        videoOutput = videoOutput,
        videoReadiness = PlaybackOutputReadiness.Released,
        dolbyVisionOutput = false,
        outputEvidence =
            outputEvidence.copy(
                videoReadiness = PlaybackOutputReadiness.Released,
                videoConfidence = PlaybackEvidenceConfidence.Confirmed,
                outputDynamicRange = "",
            ),
    )

/** The audio sink was torn down, labelled [audioOutput]; every claim about what reached it goes too. */
internal fun PlaybackDiagnostics.withAudioOutputReleased(audioOutput: String): PlaybackDiagnostics =
    copy(
        audioOutput = audioOutput,
        audioReadiness = PlaybackOutputReadiness.Released,
        // The label rule cleared this implicitly, because the released sentence no longer said
        // 源码输出. A flag has to be told.
        immersiveAudioCarrierOutput = false,
        dolbyAtmosOutput = false,
        spatialAudioOutput = false,
        headTrackingAvailable = false,
        outputEvidence =
            outputEvidence.copy(
                audioReadiness = PlaybackOutputReadiness.Released,
                audioMode = PlaybackAudioOutputMode.Unknown,
            ),
    )

/** [droppedFrames] counted by the renderer itself, which is what makes the count evidence. */
internal fun PlaybackDiagnostics.withDroppedFrames(droppedFrames: Int): PlaybackDiagnostics =
    copy(
        droppedFrames = droppedFrames,
        outputEvidence = outputEvidence.copy(droppedFramesMeasured = true),
    )

/**
 * Bits per component of a decoded pixel format as FFmpeg-based renderers name it (p010,
 * yuv420p12le, nv12); 0 when there is no format to read.
 */
internal fun String.pixelFormatBitDepth(): Int =
    lowercase().let { format ->
        when {
            format.isBlank() -> 0
            format.startsWith("p016") || "p16" in format || format in setOf("rgb48", "rgba64") -> 16
            format.startsWith("p014") || "p14" in format -> 14
            format.startsWith("p012") || "p12" in format -> 12
            format.startsWith("p010") || "p10" in format -> 10
            format.startsWith("p009") || "p9" in format -> 9
            else -> 8
        }
    }
