package com.yfuse.core2.android

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.quirk.InMemoryYCore2FailureStore
import com.yfuse.core2.quirk.YCore2FailureLedger
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.test.YMediaTestCase
import com.yfuse.core2.test.YMediaTestSuite
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
                mediaFile = mediaFile,
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
                exerciseCase(
                    context = instrumentation.targetContext,
                    testCase = testCase,
                    mediaFile = mediaFile,
                    verifyNextEpisode = index == 0,
                    seekStartIteration = 0,
                    seekIterations = MATRIX_SEEK_ITERATIONS,
                    surfaceRecreationIterations = MATRIX_SURFACE_RECREATIONS,
                )
            }
        }

    private suspend fun exerciseCase(
        context: android.content.Context,
        testCase: YMediaTestCase,
        mediaFile: File,
        verifyNextEpisode: Boolean,
        seekStartIteration: Int,
        seekIterations: Int,
        surfaceRecreationIterations: Int,
    ) {
        val item =
            YMediaItem(
                id = testCase.id,
                uri = Uri.fromFile(mediaFile).toString(),
                title = testCase.id,
            )
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
        try {
            assertTrue(player.setVideoOutput(output.output))
            player.prepare()
            player.play()
            awaitPlayable(player, testCase.id)

            val state = player.state.value
            repeat(seekIterations) { iteration ->
                val absoluteIteration = seekStartIteration + iteration
                val seekTarget = stressSeekTarget(state.durationMs, absoluteIteration)
                awaitFreshVideoOutput(player, "${testCase.id}:seek-${absoluteIteration + 1}") {
                    player.seekTo(seekTarget)
                }
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

            state.audioTracks.getOrNull(1)?.let { player.selectTrack(YTrackType.Audio, it.id) }
            state.subtitleTracks.getOrNull(1)?.let { player.selectTrack(YTrackType.Subtitle, it.id) }

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
                reportProgress("${testCase.id}: completed Surface recreation ${iteration + 1}")
            }

            if (verifyNextEpisode) {
                awaitFreshVideoOutput(player, "${testCase.id}:next-episode") {
                    player.selectItem(1)
                    player.play()
                }
                assertTrue(player.state.value.currentIndex == 1)
                awaitFreshVideoOutput(player, "${testCase.id}:previous-episode") {
                    player.selectItem(0)
                    player.play()
                }
                assertTrue(player.state.value.currentIndex == 0)
                reportProgress("${testCase.id}: completed next/previous episode round trip")
            }
        } finally {
            player.release()
            output.close()
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
                    if (state.diagnostics.videoOutputVerified && audioOutputReady) return@withTimeout
                    delay(POLL_INTERVAL_MS)
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
                            resetObserved &&
                                state.diagnostics.videoOutputVerified &&
                                (state.audioTracks.isEmpty() || state.diagnostics.audioOutputVerified)
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

    private class TestSurfaceOutput(
        width: Int = 1_920,
        height: Int = 1_080,
    ) : AutoCloseable {
        private val texture = SurfaceTexture(0)
        private val surface = Surface(texture)
        val output = AndroidSurfaceVideoOutput(surface)

        init {
            texture.setDefaultBufferSize(width, height)
        }

        override fun close() {
            surface.release()
            texture.release()
        }
    }
}

private fun File.isInside(root: File): Boolean = path == root.path || path.startsWith(root.path + File.separator)

private const val MANIFEST_ARGUMENT = "ycoreMediaManifest"
private const val SMOKE_MEDIA_ARGUMENT = "ycoreSmokeMedia"
private const val SEEK_START_ARGUMENT = "ycoreSeekStart"
private const val SEEK_ITERATIONS_ARGUMENT = "ycoreSeekIterations"
private const val SURFACE_ITERATIONS_ARGUMENT = "ycoreSurfaceIterations"
private const val PLAYBACK_TIMEOUT_MS = 30_000L
private const val POLL_INTERVAL_MS = 100L
private const val SURFACE_DETACH_SETTLE_MS = 150L
private const val BASELINE_SEEK_ITERATIONS = 100
private const val BASELINE_SURFACE_RECREATIONS = 8
private const val MATRIX_SEEK_ITERATIONS = 1
private const val MATRIX_SURFACE_RECREATIONS = 1
private const val MIN_STRESS_MEDIA_DURATION_MS = 1_500L
private const val FALLBACK_DURATION_MS = 6_000L
private const val MIN_SEEK_TARGET_MS = 250L
private const val SEEK_END_GUARD_MS = 750L
private const val SEEK_TARGET_STEP_MS = 791L
private const val PROGRESS_INTERVAL = 5
private const val PROGRESS_STATUS_CODE = 2

private fun Bundle.intArgument(
    key: String,
    defaultValue: Int,
): Int = getString(key)?.toIntOrNull()?.coerceAtLeast(0) ?: defaultValue
