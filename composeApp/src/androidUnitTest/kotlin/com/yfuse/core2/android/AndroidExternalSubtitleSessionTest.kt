package com.yfuse.core2.android

import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidExternalSubtitleSessionTest {
    @Test
    fun `failed default subtitle stays local to the track and another track still loads`() =
        runTest {
            val completions = mutableListOf<AndroidExternalSubtitleSession.Completion>()
            val calls = mutableListOf<String>()
            val session =
                AndroidExternalSubtitleSession(
                    this,
                    load = { source, _, id ->
                        calls += source.uri
                        if (source.uri.endsWith(".sup")) error("unsupported bitmap sidecar")
                        AndroidLoadedExternalSubtitle(YTrack(id, YTrackType.Subtitle, "字幕"), emptyList())
                    },
                    completed = completions::add,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            try {
                session.reset(
                    listOf(
                        YExternalSubtitleSource("https://example.invalid/track.sup", default = true),
                        YExternalSubtitleSource("https://example.invalid/track.srt"),
                    ),
                    emptyMap(),
                )
                session.request(session.defaultId)
                runCurrent()
                assertNull(completions.single().subtitle)
                assertEquals("IllegalStateException", completions.single().errorType)
                assertTrue(session.accept(completions.single()))
                session.request(session.defaultId)
                runCurrent()
                assertEquals(1, calls.size, "A failed default must not be retried for every frame")
                session.request(session.tracks[1].track.id)
                runCurrent()
                assertTrue(session.accept(completions.last()))
                assertNull(completions.last().errorType)
                assertEquals(2, calls.size)
            } finally {
                session.close()
            }
        }

    @Test
    fun `catalog preparation does not download and only requested subtitles are loaded`() =
        runTest {
            val calls = mutableListOf<String>()
            val completions = mutableListOf<AndroidExternalSubtitleSession.Completion>()
            val session =
                AndroidExternalSubtitleSession(
                    this,
                    load = { source, _, id ->
                        calls += source.uri
                        AndroidLoadedExternalSubtitle(YTrack(id, YTrackType.Subtitle, "字幕"), emptyList())
                    },
                    completed = completions::add,
                    dispatcher = StandardTestDispatcher(testScheduler),
                )
            try {
                session.reset(
                    listOf(
                        YExternalSubtitleSource("https://example.invalid/default.srt", default = true),
                        YExternalSubtitleSource("https://example.invalid/other.srt"),
                    ),
                    emptyMap(),
                )
                runCurrent()
                assertTrue(calls.isEmpty())
                session.request(session.defaultId)
                runCurrent()
                assertEquals(listOf("https://example.invalid/default.srt"), calls)
                assertTrue(session.accept(completions.single()))
                session.request(session.defaultId)
                runCurrent()
                assertEquals(1, calls.size)
                session.reset(listOf(YExternalSubtitleSource("https://example.invalid/new.srt")), emptyMap())
                assertFalse(session.accept(completions.single()), "A completion from the previous item is stale")
            } finally {
                session.close()
            }
        }
}
