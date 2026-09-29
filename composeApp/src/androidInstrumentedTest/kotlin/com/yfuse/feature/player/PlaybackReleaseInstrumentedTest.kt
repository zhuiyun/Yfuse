package com.yfuse.feature.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.BuildConfig
import com.yfuse.core.logging.DiagnosticLogStore
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core2.android.AndroidSerializedPlayerRelease
import com.yfuse.core2.android.GeneratedAvcAacTestMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Measures real compatibility backends. It deliberately has no uncalibrated release-time limit. */
@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class PlaybackReleaseInstrumentedTest {
    @Test fun exo_releases_once_after_local_avc_aac_playback() = measure(Backend.Exo, loading = false)

    @Test fun mpv_releases_once_after_local_avc_aac_playback() = measure(Backend.Mpv, loading = false)

    @Test fun mdk_releases_once_after_local_avc_aac_playback() = measure(Backend.Mdk, loading = false)

    @Test fun exo_releases_once_while_waiting_for_http_headers() = measure(Backend.Exo, loading = true)

    @Test fun mpv_releases_once_while_waiting_for_http_headers() = measure(Backend.Mpv, loading = true)

    @Test fun mdk_releases_once_while_waiting_for_http_headers() = measure(Backend.Mdk, loading = true)

    private fun measure(
        backend: Backend,
        loading: Boolean,
    ): Unit =
        runBlocking {
            requireFullReleaseTestPackage()
            if (backend ==
                Backend.Mdk
            ) {
                assumeTrue("MDK is not included in this full-build configuration", BuildConfig.YFUSE_MDK_INCLUDED)
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val caseName = backend.name + if (loading) "-loading" else "-playing"
            val report =
                JSONObject()
                    .put("case", caseName)
                    .put("package", context.packageName)
                    .put("device", Build.MODEL)
                    .put("api", Build.VERSION.SDK_INT)
                    .put("status", "started")
            val directory = File(context.getExternalFilesDir(null), "release-measurements").apply { mkdirs() }
            val reportFile = File(directory, "$caseName-${SystemClock.elapsedRealtime()}.json")
            reportFile.writeText(report.toString(2))
            var host: PlaybackReleaseTestHost? = null
            var engine: VideoEngine? = null
            var origin: PlaybackHeldResponseOrigin? = null
            var media: File? = null
            var firstReleaseCalled = false
            var failure: Throwable? = null
            try {
                if (loading) {
                    origin = PlaybackHeldResponseOrigin()
                } else {
                    media =
                        GeneratedAvcAacTestMedia.create(context.cacheDir)
                }
                val source = origin?.url ?: Uri.fromFile(checkNotNull(media)).toString()
                val owner = PlaybackReleaseTestHost.open().also { host = it }
                val active =
                    owner.onMain {
                        createEngine(backend, context, source, scope).also { created ->
                            engine = created
                            attach(backend, created, owner.view)
                            created.play()
                        }
                    }
                if (loading) {
                    val requestLine = withTimeout(10_000L) { checkNotNull(origin).requested.await() }
                    assertTrue("No real HTTP request reached the held origin", requestLine.startsWith("GET "))
                    assertNull("Backend failed before loading-exit measurement", active.state.value.error)
                    assertFalse(
                        "No response was sent, so video must not be reported rendered",
                        active.state.value.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering,
                    )
                } else {
                    val ready =
                        withTimeout(20_000L) {
                            active.state.first { state ->
                                check(
                                    state.error == null,
                                ) { "$caseName could not play generated media: ${state.error}" }
                                state.positionMs >= 300L &&
                                    state.diagnostics.effectiveVideoReadiness == PlaybackOutputReadiness.Rendering
                            }
                        }
                    assertTrue(ready.durationMs > 0L)
                    report.put("positionBeforeReleaseMs", ready.positionMs)
                    report.put("videoReadiness", ready.diagnostics.effectiveVideoReadiness.name)
                    report.put("decoder", ready.diagnostics.decoder)
                }

                // Reset only this isolated test application's log/deduplication state. Each captured
                // release record is copied into the per-case report before the next reset.
                DiagnosticLogStore.clear()
                report.put("status", "releasing")
                reportFile.writeText(report.toString(2))
                val elapsedNs =
                    owner.onMain {
                        assertEquals(Looper.getMainLooper(), Looper.myLooper())
                        firstReleaseCalled = true
                        val started = System.nanoTime()
                        active.release()
                        System.nanoTime() - started
                    }
                report.put("releaseMs", elapsedNs / 1_000_000.0)
                // Join the teardown as PlaybackEngineRetirements does before it starts a replacement.
                // mpv runs `stop` and `mpv_terminate_destroy` on its own thread after release()
                // returns (MpvVideoEngine.release), under a trace of its own; until that thread is
                // done its records are not all written, and a later read would catch half of them.
                val joinStarted = System.nanoTime()
                withContext(Dispatchers.Main) { (active as AndroidSerializedPlayerRelease).releaseAndJoin() }
                report.put("joinMs", (System.nanoTime() - joinStarted) / 1_000_000.0)
                val first = exportedReleaseEntries()
                report.put("releaseRecords", JSONArray(first))
                val traces = first.groupBy { it.optJSONObject("attributes")?.optString("engine").orEmpty() }
                val attributes = assertTracedOnce(traces[backend.name].orEmpty(), "Release")
                // DiagnosticLogStore normalizes every exported attribute key to lowercase.
                assertEquals("true", attributes.getString("mainthread"))
                val nativeAttributes =
                    backend.nativeTrace?.let { name ->
                        assertTracedOnce(traces[name].orEmpty(), "Native teardown").also {
                            // Blocking native calls are the reason this part has a thread of its own.
                            assertEquals("Native teardown ran on the main thread", "false", it.getString("mainthread"))
                        }
                    }
                assertTrue(
                    "Actual backend destruction was not measured",
                    (nativeAttributes ?: attributes).has(backend.destroyStage),
                )
                assertEquals(
                    "Release was traced by an unexpected owner",
                    setOfNotNull(backend.name, backend.nativeTrace),
                    traces.keys,
                )

                // Clearing deduplication is essential: two identical log entries inside five seconds
                // would otherwise hide a repeated native teardown and produce a false pass.
                DiagnosticLogStore.clear()
                owner.onMain { active.release() }
                val repeated = exportedReleaseEntries()
                report.put("repeatedReleaseRecords", JSONArray(repeated))
                assertTrue("Second release entered teardown again", repeated.isEmpty())
                report.put("status", "passed")
                Log.i(
                    "YfuseReleaseTest",
                    "$caseName releaseMs=${report.getDouble("releaseMs")} stages=$attributes native=$nativeAttributes",
                )
            } catch (error: Throwable) {
                failure = error
                report.put("status", "failed")
                report.put("failure", "${error.javaClass.simpleName}: ${error.message}")
                throw error
            } finally {
                var cleanupFailure: Throwable? = null
                val cleanupErrors = JSONArray()

                fun cleanup(action: () -> Unit) {
                    runCatching(action).onFailure { error ->
                        cleanupErrors.put("${error.javaClass.simpleName}: ${error.message}")
                        if (cleanupFailure == null) cleanupFailure = error else cleanupFailure?.addSuppressed(error)
                        failure?.addSuppressed(error)
                    }
                }
                if (!firstReleaseCalled) cleanup { host?.onMain { engine?.release() } }
                scope.cancel()
                cleanup { origin?.close() }
                cleanup { host?.close() }
                cleanup {
                    media?.let {
                        check(
                            it.delete() || !it.exists(),
                        ) { "Generated release-test media was not removed" }
                    }
                }
                if (cleanupErrors.length() > 0) report.put("cleanupFailures", cleanupErrors).put("status", "failed")
                reportFile.writeText(report.toString(2))
                if (failure == null) cleanupFailure?.let { throw it }
            }
        }

    /** One started/finished pair of a trace that completed without swallowing a failed stage. */
    private fun assertTracedOnce(
        entries: List<JSONObject>,
        teardown: String,
    ): JSONObject {
        assertEquals(
            "$teardown must enter its teardown exactly once",
            1,
            entries.count { it.optString("event") == "release_started" },
        )
        val finished = entries.filter { it.optString("event") == "release_finished" }
        assertEquals("$teardown must finish exactly once", 1, finished.size)
        val attributes = finished.single().getJSONObject("attributes")
        assertEquals("true", attributes.getString("completed"))
        assertFalse("$teardown swallowed a teardown exception: $attributes", attributes.has("failedstages"))
        return attributes
    }

    private fun createEngine(
        backend: Backend,
        context: Context,
        source: String,
        scope: CoroutineScope,
    ): VideoEngine {
        val items =
            listOf(
                PlayerMediaItem(
                    id = "release-test",
                    url = source,
                    transcodeUrl = "",
                    fallbackTranscodeUrl = "",
                    title = "Generated release fixture",
                    serverId = "test",
                ),
            )
        return when (backend) {
            Backend.Exo ->
                ExoVideoEngine(
                    context,
                    items,
                    0,
                    0L,
                    true,
                    1f,
                    scope,
                    DecoderMode.Hardware,
                    PlaybackOptimizationMode.Balanced,
                    false,
                    "Yfuse-release-test",
                    0L,
                )
            Backend.Mpv ->
                MpvVideoEngine(
                    context,
                    items,
                    0,
                    0L,
                    true,
                    1f,
                    DecoderMode.Hardware,
                    PlaybackOptimizationMode.Balanced,
                    false,
                    "Yfuse-release-test",
                    scope,
                    videoCacheBytes = 0L,
                )
            Backend.Mdk ->
                MdkVideoEngine(
                    items,
                    0,
                    0L,
                    true,
                    1f,
                    DecoderMode.Hardware,
                    false,
                    "Yfuse-release-test",
                    scope,
                    context = context,
                    videoCacheBytes = 0L,
                )
        }
    }

    private fun attach(
        backend: Backend,
        engine: VideoEngine,
        view: SurfaceView,
    ) {
        when (backend) {
            Backend.Exo -> {
                // Reflection keeps the test APK compilable with the native-only profile, whose
                // Exo runtime is intentionally compileOnly; the test explicitly skips that build.
                val player = engine.javaClass.getMethod("getPlayer").invoke(engine) as androidx.media3.common.Player
                player.setVideoSurfaceView(view)
            }
            Backend.Mpv -> (engine as MpvVideoEngine).attach(view.holder.surface)
            Backend.Mdk -> (engine as MdkVideoEngine).attach(view)
        }
    }

    /**
     * [destroyStage] is the exported stage that times the backend's own destruction; [nativeTrace]
     * names the trace of the part a backend finishes on a thread of its own after release() returns.
     */
    private enum class Backend(
        val destroyStage: String,
        val nativeTrace: String? = null,
    ) {
        Exo("playerms"),
        Mpv("nativedestroyms", nativeTrace = "Mpv.native"),
        Mdk("nativedestroyms"),
    }
}
