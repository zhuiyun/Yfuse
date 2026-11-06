package com.yfuse.feature.player

import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core2.android.AndroidNativeDirectYPlayer
import com.yfuse.core2.android.AndroidSurfaceVideoOutput
import com.yfuse.core2.android.GeneratedAvcAacTestMedia
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlayerOpenRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses generated media or a held loopback response; never starts SenPlayer or requests a license. */
@RunWith(AndroidJUnit4::class)
class PlaybackReleaseInstrumentedTest {
    @Test
    fun ycore_releases_after_real_local_avc_aac_output() = measure(loading = false)

    @Test
    fun ycore_release_cancels_a_real_pending_http_read() = measure(loading = true)

    private fun measure(loading: Boolean): Unit =
        runBlocking {
            requireFullReleaseTestPackage()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val caseName = if (loading) "YCore-loading" else "YCore-playing"
            val directory = File(context.getExternalFilesDir(null), "release-measurements").apply { mkdirs() }
            val reportFile = File(directory, "$caseName-${SystemClock.elapsedRealtime()}.json")
            val report = JSONObject().put("case", caseName).put("package", context.packageName).put("status", "started")
            var origin: PlaybackHeldResponseOrigin? = null
            var media: File? = null
            var host: PlaybackReleaseTestHost? = null
            var player: AndroidNativeDirectYPlayer? = null
            try {
                if (loading) {
                    origin = PlaybackHeldResponseOrigin()
                } else {
                    media =
                        GeneratedAvcAacTestMedia.create(context.cacheDir)
                }
                val uri = origin?.url ?: Uri.fromFile(checkNotNull(media)).toString()
                val owner = PlaybackReleaseTestHost.open().also { host = it }
                val active =
                    owner.onMain {
                        AndroidNativeDirectYPlayer(
                            context,
                            YPlayerOpenRequest(
                                items = listOf(YMediaItem("fixture", uri)),
                                autoPlay = true,
                                autoNext = false,
                            ),
                        ).also {
                            player = it
                            assertTrue(it.setVideoOutput(AndroidSurfaceVideoOutput(owner.view.holder.surface)))
                            it.prepare()
                            it.play()
                        }
                    }
                if (loading) {
                    val line = withTimeout(10_000L) { checkNotNull(origin).requested.await() }
                    assertTrue("The fixture must receive a real HTTP read", line.startsWith("GET "))
                    assertNull(active.state.value.error)
                    assertFalse(active.state.value.diagnostics.videoOutputVerified)
                } else {
                    val ready =
                        withTimeout(20_000L) {
                            active.state.first {
                                check(it.error == null) { "Generated YCore playback failed: ${it.error}" }
                                it.positionMs >= 300L && it.diagnostics.videoOutputVerified
                            }
                        }
                    assertTrue(ready.durationMs > 0L)
                    report.put("positionBeforeReleaseMs", ready.positionMs)
                    report.put("route", ready.diagnostics.route.name)
                }
                val elapsed =
                    owner.onMain {
                        assertEquals(Looper.getMainLooper(), Looper.myLooper())
                        val start = System.nanoTime()
                        active.release()
                        System.nanoTime() - start
                    }
                report.put("releaseMs", elapsed / 1_000_000.0)
                val joinStarted = System.nanoTime()
                withTimeout(10_000L) { active.releaseAndJoin() }
                assertTrue(
                    "All decoder and transport work must finish before a replacement starts",
                    active.releaseCompleted,
                )
                report.put("joinMs", (System.nanoTime() - joinStarted) / 1_000_000.0)
                owner.onMain { active.release() }
                withTimeout(10_000L) { active.releaseAndJoin() }
                assertTrue("Repeated release remains complete", active.releaseCompleted)
                report.put("status", "passed")
            } catch (failure: Throwable) {
                report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                throw failure
            } finally {
                withContext(NonCancellable + Dispatchers.Default) {
                    try {
                        withTimeout(10_000L) { player?.releaseAndJoin() }
                    } finally {
                        origin?.close()
                        host?.close()
                        media?.delete()
                        reportFile.writeText(report.toString(2))
                    }
                }
            }
        }
}
