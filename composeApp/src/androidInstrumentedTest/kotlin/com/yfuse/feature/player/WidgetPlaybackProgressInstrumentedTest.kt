package com.yfuse.feature.player

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetPlaybackRequest
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.playbackIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WidgetPlaybackProgressInstrumentedTest {
    @Test fun componentPlaybackUsesTheRealDurableReporterWithoutAdoptingAServer() {
        val source =
            WidgetSourceContext("progress-test", WidgetMedia(UUID.randomUUID().toString(), "组件进度回归", mediaType = "tv"))
        val identity = source.playbackIdentity(season = 2, episode = 3)
        val route = source.media.copy(videoUrl = "https://stream.example/temporary.m3u8")
        val item = widgetPlaybackItem(route, WidgetPlaybackRequest(identity))
        val sink =
            playbackEventSinkFor(playbackReportingTarget(item)) { error("Component playback must not use a server") }
        assertNotNull(sink)
        val progress = GlobalContext.get().get<PlaybackSyncStore>()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val reporter = PlaybackProgressReporter(listOf(item), requireNotNull(sink), owner, sourcePreloader = null)
        val started = PlaybackState(playing = true, buffering = false, positionMs = 120_000L, durationMs = 1_000_000L)
        try {
            reporter.update(started)
            await {
                progress
                    .find(identity.mediaKey)
                    ?.document
                    ?.state
                    ?.positionMs == 120_000L
            }
            reporter.close(started.copy(playing = false, positionMs = 123_456L))
            await {
                progress
                    .find(identity.mediaKey)
                    ?.document
                    ?.state
                    ?.positionMs == 123_456L
            }
            val reopened =
                widgetPlaybackItem(
                    route.copy(videoUrl = "https://another.example/new-token.m3u8"),
                    WidgetPlaybackRequest(identity),
                )
            assertEquals(item.id, reopened.id)
            val saved = requireNotNull(progress.find(reopened.watchKey)).document.state
            assertEquals(123_456L, saved.positionMs)
            assertNull(saved.serverId)
            assertTrue(saved.serverItemId?.startsWith("external-widget-") == true)
        } finally {
            reporter.close(started)
            owner.cancel()
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25L)
        assertTrue("The real component progress was not persisted", predicate())
    }
}
