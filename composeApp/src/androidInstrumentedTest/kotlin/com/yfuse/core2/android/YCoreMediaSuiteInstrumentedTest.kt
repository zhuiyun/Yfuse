package com.yfuse.core2.android

import android.graphics.ImageFormat
import android.media.ImageReader
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core2.api.YDiscKind
import com.yfuse.core2.api.YDiscMedia
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.dolby.YDolbyVisionEnhancementLayerKind
import com.yfuse.core2.dolby.YDolbyVisionStreamEvidence
import com.yfuse.core2.quirk.InMemoryYCore2FailureStore
import com.yfuse.core2.quirk.YCore2FailureLedger
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.test.YMediaObservedFacts
import com.yfuse.core2.test.YMediaTestCase
import com.yfuse.core2.test.YMediaTestObservation
import com.yfuse.core2.test.YMediaTestSuite
import com.yfuse.core2.test.observedMetadataErrors
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device lane for the external, licensed YCore media corpus. */
@RunWith(AndroidJUnit4::class)
class YCoreMediaSuiteInstrumentedTest {
    @Test
    fun baseline_media_survives_core_playback_lifecycle() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val arguments = InstrumentationRegistry.getArguments()
            val mediaPath = arguments.getString(SMOKE_MEDIA_ARGUMENT)
            assumeTrue(
                "Push a baseline media file and pass -e $SMOKE_MEDIA_ARGUMENT <absolute-device-path>",
                !mediaPath.isNullOrBlank(),
            )
            val mediaFile = File(requireNotNull(mediaPath)).canonicalFile
            assertTrue("YCore smoke media is missing", mediaFile.isFile)
            val smokeItem =
                YMediaItem(
                    id = "baseline-smoke",
                    uri = Uri.fromFile(mediaFile).toString(),
                    title = "baseline-smoke",
                )
            val probe = AndroidCore2MediaProbe(instrumentation.targetContext).probe(smokeItem)
            val decision = AndroidCore2RouteEvaluator(instrumentation.targetContext).evaluate(smokeItem)
            assertTrue("YCore smoke route unavailable: probe=$probe", decision != null)
            val h264Capabilities =
                AndroidYCapabilityProvider(instrumentation.targetContext)
                    .current()
                    .videoDecoders
                    .filter { it.codec == YVideoCodec.H264 }
            assertTrue(
                "YCore smoke route is not natively executable: $decision; H264 decoders=$h264Capabilities",
                requireNotNull(decision).let {
                    it.nativeTunnelExecutable || it.nativeDirectExecutable || it.nativeEnhancedExecutable
                },
            )
            exerciseCase(
                context = instrumentation.targetContext,
                testCase =
                    YMediaTestCase(
                        id = "baseline-smoke",
                        relativePath = mediaFile.name,
                        videoCodec = "unknown",
                        bitDepth = 8,
                        frameRate = 1.0,
                        container = mediaFile.extension,
                        audioCodec = "unknown",
                        height = 1,
                        bitrateBitsPerSecond = 1L,
                    ),
                item = smokeItem,
                verifyNextEpisode = true,
                seekStartIteration = arguments.intArgument(SEEK_START_ARGUMENT, 0),
                seekIterations = arguments.intArgument(SEEK_ITERATIONS_ARGUMENT, BASELINE_SEEK_ITERATIONS),
                surfaceRecreationIterations =
                    arguments.intArgument(SURFACE_ITERATIONS_ARGUMENT, BASELINE_SURFACE_RECREATIONS),
            )
        }

    @Test
    fun full_media_matrix_survives_core_playback_lifecycle() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val manifestPath: String? = InstrumentationRegistry.getArguments().getString(MANIFEST_ARGUMENT)
            assumeTrue(
                "Push a corpus manifest and pass -e $MANIFEST_ARGUMENT <absolute-device-path>",
                !manifestPath.isNullOrBlank(),
            )
            val manifest = File(requireNotNull(manifestPath)).canonicalFile
            assertTrue("YCore media manifest is missing", manifest.isFile)
            val suite = Json.decodeFromString<YMediaTestSuite>(manifest.readText())
            val validationErrors = suite.validationErrors()
            assertTrue(validationErrors.joinToString("\n"), validationErrors.isEmpty())
            val mediaRoot = requireNotNull(manifest.parentFile).canonicalFile

            suite.cases.forEachIndexed { index, testCase ->
                val mediaFile = File(mediaRoot, testCase.relativePath).canonicalFile
                assertTrue("${testCase.id}: path escaped corpus root", mediaFile.isInside(mediaRoot))
                assertTrue("${testCase.id}: media file is missing", mediaFile.isFile)
                val item = testCase.mediaItem(mediaFile)
                verifyObservedMetadata(testCase, item)
                exerciseCase(
                    context = instrumentation.targetContext,
                    testCase = testCase,
                    item = item,
                    verifyNextEpisode = index == 0,
                    seekStartIteration = 0,
                    seekIterations = MATRIX_SEEK_ITERATIONS,
                    surfaceRecreationIterations = MATRIX_SURFACE_RECREATIONS,
                )
            }
        }

    @Test
    fun configured_long_running_soak_keeps_output_and_health_stable() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val arguments = InstrumentationRegistry.getArguments()
            val mediaPath = arguments.getString(SOAK_MEDIA_ARGUMENT)
            val durationMinutes = arguments.longArgument(SOAK_DURATION_MINUTES_ARGUMENT, 0L)
            assumeTrue(
                "Pass -e $SOAK_MEDIA_ARGUMENT <absolute-device-path> and " +
                    "-e $SOAK_DURATION_MINUTES_ARGUMENT <minutes>",
                !mediaPath.isNullOrBlank() && durationMinutes > 0L,
            )
            val mediaFile = File(requireNotNull(mediaPath)).canonicalFile
            assertTrue("YCore soak media is missing", mediaFile.isFile)
            val queueSoak = arguments.booleanArgument(SOAK_QUEUE_ARGUMENT, false)
            val item =
                YMediaItem(
                    id = "configured-soak",
                    uri = Uri.fromFile(mediaFile).toString(),
                    title = "configured-soak",
                )
            exerciseCase(
                context = instrumentation.targetContext,
                testCase =
                    YMediaTestCase(
                        id = if (queueSoak) "soak-queue" else "soak-single-item",
                        relativePath = mediaFile.name,
                        videoCodec = "unknown",
                        bitDepth = 8,
                        frameRate = 1.0,
                        container = mediaFile.extension,
                        audioCodec = "unknown",
                        height = 1,
                        bitrateBitsPerSecond = 1L,
                    ),
                item = item,
                verifyNextEpisode = queueSoak,
                seekStartIteration = arguments.intArgument(SEEK_START_ARGUMENT, 0),
                seekIterations = arguments.intArgument(SEEK_ITERATIONS_ARGUMENT, 0),
                surfaceRecreationIterations = arguments.intArgument(SURFACE_ITERATIONS_ARGUMENT, 0),
                soakDurationMs = durationMinutes * 60_000L,
                soakQueue = queueSoak,
            )
        }

    private suspend fun exerciseCase(
        context: android.content.Context,
        testCase: YMediaTestCase,
        item: YMediaItem,
        verifyNextEpisode: Boolean,
        seekStartIteration: Int,
        seekIterations: Int,
        surfaceRecreationIterations: Int,
        soakDurationMs: Long = 0L,
        soakQueue: Boolean = false,
    ) {
        val request =
            YPlayerOpenRequest(
                items = if (verifyNextEpisode) listOf(item, item.copy(id = "${item.id}:next")) else listOf(item),
                autoPlay = true,
                autoNext = false,
            )
        val player =
            AndroidAdaptiveCore2YPlayer(
                context = context,
                request = request,
                allowAudioPassthrough = true,
                frameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
                failureLedger =
                    YCore2FailureLedger(
                        store = InMemoryYCore2FailureStore(),
                        nowEpochMs = System::currentTimeMillis,
                    ),
            )
        var output = TestSurfaceOutput()
        val health = DeviceHealthSampler(context, testCase)
        var completed = false
        var timedOut = false
        var completedSeekCycles = 0
        var completedSurfaceRecreations = 0
        var queueTransitions = 0
        var completedSoakMinutes = 0
        try {
            assertTrue(player.setVideoOutput(output.output))
            player.prepare()
            player.play()
            awaitPlayable(player, testCase.id)
            health.sample(player.state.value)

            val state = player.state.value
            repeat(seekIterations) { iteration ->
                val absoluteIteration = seekStartIteration + iteration
                val seekTarget = stressSeekTarget(state.durationMs, absoluteIteration)
                awaitFreshVideoOutput(player, "${testCase.id}:seek-${absoluteIteration + 1}") {
                    player.seekTo(seekTarget)
                    // A short sample can naturally reach Ended between stress iterations.
                    // Seeking clears Ended but intentionally does not imply autoplay.
                    player.play()
                }
                completedSeekCycles += 1
                health.sample(player.state.value)
                if ((iteration + 1) % PROGRESS_INTERVAL == 0 || iteration + 1 == seekIterations) {
                    reportProgress(
                        "${testCase.id}: completed seek ${absoluteIteration + 1} " +
                            "(${iteration + 1}/$seekIterations in this run)",
                    )
                }
            }
            player.pause()
            player.play()
            awaitPlayable(player, "${testCase.id}:seek-resume")
            health.sample(player.state.value)

            state.audioTracks.getOrNull(1)?.let { track ->
                player.selectTrack(YTrackType.Audio, track.id)
                awaitTrackSelected(player, YTrackType.Audio, track.id, "${testCase.id}:audio-switch")
                awaitPlayable(player, "${testCase.id}:audio-switch-output")
                reportProgress("${testCase.id}: completed audio track switch")
            }
            state.subtitleTracks.getOrNull(1)?.let { track ->
                player.selectTrack(YTrackType.Subtitle, track.id)
                awaitTrackSelected(player, YTrackType.Subtitle, track.id, "${testCase.id}:subtitle-switch")
                reportProgress("${testCase.id}: completed subtitle track switch")
            }

            repeat(surfaceRecreationIterations) { iteration ->
                player.pause()
                assertTrue(player.setVideoOutput(null))
                awaitVideoOutputDetached(player, "${testCase.id}:surface-detach-${iteration + 1}")
                output.close()
                delay(SURFACE_DETACH_SETTLE_MS)
                output =
                    if (iteration % 2 == 0) {
                        TestSurfaceOutput(width = 1_920, height = 1_080)
                    } else {
                        TestSurfaceOutput(width = 1_080, height = 1_920)
                    }
                awaitFreshVideoOutput(player, "${testCase.id}:surface-recreate-${iteration + 1}") {
                    assertTrue(player.setVideoOutput(output.output))
                    player.play()
                }
                completedSurfaceRecreations += 1
                health.sample(player.state.value)
                reportProgress("${testCase.id}: completed Surface recreation ${iteration + 1}")
            }

            player.pause()
            assertTrue(player.setVideoOutput(null))
            awaitVideoOutputDetached(player, "${testCase.id}:background")
            health.sample(player.state.value)
            delay(SURFACE_DETACH_SETTLE_MS)
            awaitFreshVideoOutput(player, "${testCase.id}:foreground") {
                assertTrue(player.setVideoOutput(output.output))
                player.play()
            }
            health.sample(player.state.value)
            reportProgress("${testCase.id}: completed background/foreground cycle")

            if (soakDurationMs > 0L) {
                val soakStartedAtMs = SystemClock.elapsedRealtime()
                var nextProgressAtMs = SOAK_PROGRESS_INTERVAL_MS
                var nextQueueTransitionAtMs = QUEUE_TRANSITION_INTERVAL_MS
                while (SystemClock.elapsedRealtime() - soakStartedAtMs < soakDurationMs) {
                    val soakState = player.state.value
                    assertFalse(
                        failureMessage("${testCase.id}:soak", soakState),
                        soakState.phase == YPlaybackPhase.Failed,
                    )
                    val elapsedBeforeActionMs = SystemClock.elapsedRealtime() - soakStartedAtMs
                    val scheduledQueueTransition =
                        soakQueue && elapsedBeforeActionMs >= nextQueueTransitionAtMs
                    if (scheduledQueueTransition) {
                        awaitFreshVideoOutput(player, "${testCase.id}:soak-queue-transition") {
                            player.selectItem(if (soakState.currentIndex == 0) 1 else 0)
                            player.play()
                        }
                        queueTransitions += 1
                        nextQueueTransitionAtMs += QUEUE_TRANSITION_INTERVAL_MS
                    } else if (soakState.phase == YPlaybackPhase.Ended) {
                        awaitFreshVideoOutput(player, "${testCase.id}:soak-loop") {
                            if (soakQueue && soakState.itemCount > 1) {
                                player.selectItem(if (soakState.currentIndex == 0) 1 else 0)
                                queueTransitions += 1
                            } else {
                                player.seekTo(0L)
                            }
                            player.play()
                        }
                    } else {
                        val remainingMs =
                            (soakDurationMs - (SystemClock.elapsedRealtime() - soakStartedAtMs))
                                .coerceAtLeast(0L)
                        delay(minOf(SOAK_SAMPLE_INTERVAL_MS, remainingMs))
                    }
                    health.sample(player.state.value)
                    val elapsedMs = SystemClock.elapsedRealtime() - soakStartedAtMs
                    if (elapsedMs >= nextProgressAtMs || elapsedMs >= soakDurationMs) {
                        reportProgress(
                            "${testCase.id}: soak ${elapsedMs / 60_000L}/${soakDurationMs / 60_000L} minutes",
                        )
                        nextProgressAtMs += SOAK_PROGRESS_INTERVAL_MS
                    }
                }
                completedSoakMinutes = (soakDurationMs / 60_000L).toInt()
            }

            if (verifyNextEpisode) {
                awaitFreshVideoOutput(player, "${testCase.id}:next-episode") {
                    player.selectItem(1)
                    player.play()
                }
                assertTrue(player.state.value.currentIndex == 1)
                queueTransitions += 1
                awaitFreshVideoOutput(player, "${testCase.id}:previous-episode") {
                    player.selectItem(0)
                    player.play()
                }
                assertTrue(player.state.value.currentIndex == 0)
                queueTransitions += 1
                reportProgress("${testCase.id}: completed next/previous episode round trip")
            }
            val finishFromMs = (player.state.value.durationMs - FINISH_END_GUARD_MS).coerceAtLeast(0L)
            player.seekTo(finishFromMs)
            player.play()
            awaitEnded(player, "${testCase.id}:finish")
            reportProgress("${testCase.id}: completed natural finish verification")
            completed = true
        } catch (failure: Throwable) {
            timedOut = failure.hasTimeoutCause()
            throw failure
        } finally {
            health.sample(player.state.value)
            reportObservation(
                health.finish(
                    state = player.state.value,
                    completed = completed,
                    timedOut = timedOut,
                    seekCycles = completedSeekCycles,
                    surfaceRecreations = completedSurfaceRecreations,
                    queueTransitions = queueTransitions,
                    continuousSoakMinutes = if (soakQueue) 0 else completedSoakMinutes,
                    queueSoakMinutes = if (soakQueue) completedSoakMinutes else 0,
                ),
            )
            player.release()
            output.close()
        }
    }

    private fun verifyObservedMetadata(
        testCase: YMediaTestCase,
        item: YMediaItem,
    ) {
        // Disc images are verified by the real libbluray route below. FFmpeg cannot truthfully
        // describe the selected MPLS before libbluray resolves the authored main feature.
        if (item.disc != null) return

        val demuxer = AndroidFfmpegDemuxer()
        assertTrue("${testCase.id}: FFmpeg metadata probe unavailable", demuxer.available)
        try {
            val result = demuxer.open(YDemuxSource(item.uri, item.headers))
            val video =
                result.tracks
                    .firstOrNull { it.type == YDemuxTrackType.Video }
                    ?.video
            assertTrue("${testCase.id}: no video metadata observed", video != null)
            val observedVideo = requireNotNull(video)
            val deepProbe = AndroidEnhancedMediaProbe().probe(item) as? YCore2ProbeResult.Success
            val dolbyConfig = deepProbe?.dolbyVisionConfig ?: observedVideo.dolbyVisionConfig
            val dolbyEvidence = deepProbe?.dolbyVisionStreamEvidence
            val observed =
                YMediaObservedFacts(
                    videoCodec = observedVideo.codec.name,
                    bitDepth = observedVideo.bitDepth,
                    hdr = observedVideo.hdrType.name,
                    dolbyVisionProfile = dolbyConfig?.manifestProfile(dolbyEvidence),
                    frameRate = observedVideo.frameRate.toDouble(),
                    container = result.container.name,
                    audioCodecs =
                        result.tracks
                            .asSequence()
                            .filter { it.type == YDemuxTrackType.Audio }
                            .mapNotNull { it.audio?.codec?.name }
                            .toSet(),
                    subtitleFormats =
                        result.tracks
                            .asSequence()
                            .filter { it.type == YDemuxTrackType.Subtitle }
                            .mapNotNull { it.subtitle?.format?.name }
                            .toSet(),
                    height = observedVideo.height,
                    bitrateBitsPerSecond = result.bitRateBitsPerSecond,
                )
            val errors = testCase.observedMetadataErrors(observed)
            assertTrue(errors.joinToString("\n"), errors.isEmpty())
        } finally {
            demuxer.close()
        }
    }

    private suspend fun awaitPlayable(
        player: AndroidAdaptiveCore2YPlayer,
        label: String,
    ) {
        try {
            withTimeout(PLAYBACK_TIMEOUT_MS) {
                while (true) {
                    val state = player.state.value
                    assertFalse(failureMessage(label, state), state.phase == YPlaybackPhase.Failed)
                    val audioOutputReady =
                        state.audioTracks.isEmpty() || state.diagnostics.audioOutputVerified
                    if (state.diagnostics.videoOutputVerified && audioOutputReady) {
                        assertPureNativeRoute(label, state)
                        return@withTimeout
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError(timeoutMessage(label, player.state.value), failure)
        }
    }

    private suspend fun awaitTrackSelected(
        player: AndroidAdaptiveCore2YPlayer,
        type: YTrackType,
        id: String,
        label: String,
    ) {
        try {
            withTimeout(PLAYBACK_TIMEOUT_MS) {
                player.state.first { state ->
                    assertFalse(failureMessage(label, state), state.phase == YPlaybackPhase.Failed)
                    val tracks = if (type == YTrackType.Audio) state.audioTracks else state.subtitleTracks
                    tracks.any { it.id == id && it.selected }.also { selected ->
                        if (selected) assertPureNativeRoute(label, state)
                    }
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError(timeoutMessage(label, player.state.value), failure)
        }
    }

    private suspend fun awaitEnded(
        player: AndroidAdaptiveCore2YPlayer,
        label: String,
    ) {
        try {
            withTimeout(PLAYBACK_TIMEOUT_MS) {
                player.state.first { state ->
                    assertFalse(failureMessage(label, state), state.phase == YPlaybackPhase.Failed)
                    (state.phase == YPlaybackPhase.Ended).also { ended ->
                        if (ended) assertPureNativeRoute(label, state)
                    }
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError(timeoutMessage(label, player.state.value), failure)
        }
    }

    private suspend fun awaitFreshVideoOutput(
        player: AndroidAdaptiveCore2YPlayer,
        label: String,
        action: () -> Unit,
    ) = coroutineScope {
        var resetObserved = false
        val verificationCycle =
            async(start = CoroutineStart.UNDISPATCHED) {
                try {
                    withTimeout(PLAYBACK_TIMEOUT_MS) {
                        player.state.first { state ->
                            assertFalse(failureMessage(label, state), state.phase == YPlaybackPhase.Failed)
                            if (!state.diagnostics.videoOutputVerified) resetObserved = true
                            val outputReady =
                                resetObserved &&
                                    state.diagnostics.videoOutputVerified &&
                                    (state.audioTracks.isEmpty() || state.diagnostics.audioOutputVerified)
                            if (outputReady) assertPureNativeRoute(label, state)
                            outputReady
                        }
                    }
                } catch (failure: TimeoutCancellationException) {
                    throw AssertionError(timeoutMessage(label, player.state.value), failure)
                }
            }
        action()
        verificationCycle.await()
    }

    private suspend fun awaitVideoOutputDetached(
        player: AndroidAdaptiveCore2YPlayer,
        label: String,
    ) {
        try {
            withTimeout(PLAYBACK_TIMEOUT_MS) {
                player.state.first { state ->
                    assertFalse(failureMessage(label, state), state.phase == YPlaybackPhase.Failed)
                    !state.diagnostics.videoOutputVerified
                }
            }
        } catch (failure: TimeoutCancellationException) {
            throw AssertionError(timeoutMessage(label, player.state.value), failure)
        }
    }

    private fun timeoutMessage(
        label: String,
        state: com.yfuse.core2.api.YPlayerState,
    ): String =
        "${failureMessage(label, state)}; timed out after ${PLAYBACK_TIMEOUT_MS}ms, " +
            "phase=${state.phase}, playing=${state.playing}, buffering=${state.buffering}, " +
            "positionMs=${state.positionMs}, durationMs=${state.durationMs}, " +
            "videoVerified=${state.diagnostics.videoOutputVerified}, " +
            "audioVerified=${state.diagnostics.audioOutputVerified}"

    private fun failureMessage(
        label: String,
        state: com.yfuse.core2.api.YPlayerState,
    ): String =
        buildString {
            append(label)
            append(" failed: category=")
            append(state.errorCategory)
            append(", route=")
            append(state.diagnostics.route)
            append(", reason=")
            append(state.diagnostics.reason)
            append(", error=")
            append(state.error)
            append(", demuxer=")
            append(state.diagnostics.demuxer)
            append(", decoder=")
            append(state.diagnostics.decoder)
            append(", audio=")
            append(state.diagnostics.audioOutput)
        }

    private fun assertPureNativeRoute(
        label: String,
        state: com.yfuse.core2.api.YPlayerState,
    ) {
        assertTrue(
            "$label used non-native route ${state.diagnostics.route}: ${state.diagnostics.reason}",
            state.diagnostics.route in PURE_NATIVE_ROUTES,
        )
    }

    private fun stressSeekTarget(
        durationMs: Long,
        iteration: Int,
    ): Long {
        val playableDurationMs = durationMs.takeIf { it > MIN_STRESS_MEDIA_DURATION_MS } ?: FALLBACK_DURATION_MS
        val upperBoundMs = (playableDurationMs - SEEK_END_GUARD_MS).coerceAtLeast(MIN_SEEK_TARGET_MS)
        val spanMs = (upperBoundMs - MIN_SEEK_TARGET_MS + 1L).coerceAtLeast(1L)
        return MIN_SEEK_TARGET_MS + (iteration * SEEK_TARGET_STEP_MS) % spanMs
    }

    private fun reportProgress(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            PROGRESS_STATUS_CODE,
            Bundle().apply { putString("stream", "$message\n") },
        )
    }

    private fun reportObservation(observation: YMediaTestObservation) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            RESULT_STATUS_CODE,
            Bundle().apply { putString(RESULT_BUNDLE_KEY, Json.encodeToString(observation)) },
        )
    }

    private class TestSurfaceOutput(
        width: Int = 1_920,
        height: Int = 1_080,
    ) : AutoCloseable {
        private val imageReader = ImageReader.newInstance(width, height, ImageFormat.PRIVATE, 2)
        val output = AndroidSurfaceVideoOutput(imageReader.surface)

        override fun close() {
            imageReader.close()
        }
    }

    private class DeviceHealthSampler(
        context: android.content.Context,
        private val testCase: com.yfuse.core2.test.YMediaTestCase,
    ) {
        private val startedAtMs = SystemClock.elapsedRealtime()
        private val batteryManager = context.getSystemService(BatteryManager::class.java)
        private val powerManager = context.getSystemService(PowerManager::class.java)
        private val startBatteryPermille = batteryPermille()
        private var peakPssBytes = 0L
        private var maximumThermalStatus = 0
        private var droppedFrames = 0
        private var maximumAbsoluteAvDriftMs = 0L
        private var decoderFailureObserved = false

        fun sample(state: com.yfuse.core2.api.YPlayerState) {
            peakPssBytes = maxOf(peakPssBytes, Debug.getPss().toLong() * 1_024L)
            maximumThermalStatus = maxOf(maximumThermalStatus, thermalStatus())
            droppedFrames = maxOf(droppedFrames, state.diagnostics.droppedFrames)
            maximumAbsoluteAvDriftMs =
                maxOf(
                    maximumAbsoluteAvDriftMs,
                    kotlin.math.abs(state.diagnostics.avSyncOffsetMs ?: 0L),
                )
            if (state.errorCategory == com.yfuse.core2.api.YPlaybackFailureCategory.Decoder) {
                decoderFailureObserved = true
            }
        }

        fun finish(
            state: com.yfuse.core2.api.YPlayerState,
            completed: Boolean,
            timedOut: Boolean,
            seekCycles: Int,
            surfaceRecreations: Int,
            queueTransitions: Int,
            continuousSoakMinutes: Int,
            queueSoakMinutes: Int,
        ): YMediaTestObservation {
            val endBattery = batteryPermille()
            return YMediaTestObservation(
                caseId = testCase.id,
                elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L),
                completed = completed,
                timedOut = timedOut,
                failureCategory = state.errorCategory?.name,
                droppedFrames = droppedFrames.coerceAtLeast(0),
                decoderFailures = if (decoderFailureObserved) 1 else 0,
                maximumAbsoluteAvDriftMs = maximumAbsoluteAvDriftMs,
                peakPssBytes = peakPssBytes,
                maximumThermalStatus = maximumThermalStatus,
                batteryDeltaPermille =
                    if (startBatteryPermille >= 0 && endBattery >= 0) {
                        endBattery - startBatteryPermille
                    } else {
                        0
                    },
                dolbyVisionProfile = testCase.dolbyVisionProfile,
                videoOutputMode = state.diagnostics.videoOutput.takeIf(String::isNotBlank),
                audioOutputMode = state.diagnostics.audioOutput.takeIf(String::isNotBlank),
                // Core2's device runner has no server-transcode route. A future non-local route
                // must opt in explicitly instead of silently inheriting false.
                serverTranscodeUsed = false,
                // A native-DV badge/decoder name alone proves neither RPU application nor P7 EL.
                dolbyRpuApplied = false,
                dolbyEnhancementLayerComposed = false,
                seekCycles = seekCycles,
                surfaceRecreations = surfaceRecreations,
                queueTransitions = queueTransitions,
                continuousSoakMinutes = continuousSoakMinutes,
                queueSoakMinutes = queueSoakMinutes,
            )
        }

        private fun batteryPermille(): Int {
            val percent =
                batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return -1
            return if (percent in 0..100) percent * 10 else -1
        }

        private fun thermalStatus(): Int = if (Build.VERSION.SDK_INT >= 29) powerManager?.currentThermalStatus ?: 0 else 0
    }
}

private fun Throwable.hasTimeoutCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is TimeoutCancellationException) return true
        current = current.cause
    }
    return false
}

private fun YMediaTestCase.mediaItem(mediaFile: File): YMediaItem =
    YMediaItem(
        id = id,
        uri = Uri.fromFile(mediaFile).toString(),
        title = id,
        disc =
            when (container.trim().lowercase()) {
                "iso" -> YDiscMedia(kind = YDiscKind.Iso, container = "iso", label = id)
                "bdmv" -> YDiscMedia(kind = YDiscKind.Bdmv, container = "bdmv", label = id)
                else -> null
            },
    )

private fun YDolbyVisionConfig.manifestProfile(evidence: YDolbyVisionStreamEvidence?): String =
    when (profile) {
        7 ->
            when (evidence?.enhancementLayerKind) {
                YDolbyVisionEnhancementLayerKind.Mel -> "p7_mel"
                YDolbyVisionEnhancementLayerKind.Fel -> "p7_fel"
                YDolbyVisionEnhancementLayerKind.None,
                YDolbyVisionEnhancementLayerKind.Unknown,
                null,
                -> "p7_unknown"
            }
        8, 10 -> "p$profile.$baseLayerCompatibilityId"
        else -> "p$profile"
    }

private fun File.isInside(root: File): Boolean = path == root.path || path.startsWith(root.path + File.separator)

private const val MANIFEST_ARGUMENT = "ycoreMediaManifest"
private const val SMOKE_MEDIA_ARGUMENT = "ycoreSmokeMedia"
private const val SOAK_MEDIA_ARGUMENT = "ycoreSoakMedia"
private const val SOAK_DURATION_MINUTES_ARGUMENT = "ycoreSoakDurationMinutes"
private const val SOAK_QUEUE_ARGUMENT = "ycoreSoakQueue"
private const val SEEK_START_ARGUMENT = "ycoreSeekStart"
private const val SEEK_ITERATIONS_ARGUMENT = "ycoreSeekIterations"
private const val SURFACE_ITERATIONS_ARGUMENT = "ycoreSurfaceIterations"
private const val PLAYBACK_TIMEOUT_MS = 30_000L
private const val POLL_INTERVAL_MS = 100L
private const val SURFACE_DETACH_SETTLE_MS = 150L
private const val BASELINE_SEEK_ITERATIONS = 100
private const val BASELINE_SURFACE_RECREATIONS = 8
private const val MATRIX_SEEK_ITERATIONS = 10
private const val MATRIX_SURFACE_RECREATIONS = 1
private const val FINISH_END_GUARD_MS = 500L
private const val MIN_STRESS_MEDIA_DURATION_MS = 1_500L
private const val FALLBACK_DURATION_MS = 6_000L
private const val MIN_SEEK_TARGET_MS = 250L
private const val SEEK_END_GUARD_MS = 750L
private const val SEEK_TARGET_STEP_MS = 791L
private const val PROGRESS_INTERVAL = 5
private const val PROGRESS_STATUS_CODE = 2
private const val RESULT_STATUS_CODE = 3
private const val RESULT_BUNDLE_KEY = "ycoreResult"
private const val SOAK_SAMPLE_INTERVAL_MS = 30_000L
private const val SOAK_PROGRESS_INTERVAL_MS = 5L * 60_000L
private const val QUEUE_TRANSITION_INTERVAL_MS = 10L * 60_000L
private val PURE_NATIVE_ROUTES =
    setOf(
        YPlaybackRoute.NativeTunnel,
        YPlaybackRoute.NativeDirect,
        YPlaybackRoute.NativeEnhanced,
    )

private fun Bundle.intArgument(
    key: String,
    defaultValue: Int,
): Int = getString(key)?.toIntOrNull()?.coerceAtLeast(0) ?: defaultValue

private fun Bundle.longArgument(
    key: String,
    defaultValue: Long,
): Long = getString(key)?.toLongOrNull()?.coerceIn(0L, MAX_SOAK_MINUTES) ?: defaultValue

private fun Bundle.booleanArgument(
    key: String,
    defaultValue: Boolean,
): Boolean =
    when (getString(key)?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> defaultValue
    }

private const val MAX_SOAK_MINUTES = 48L * 60L
