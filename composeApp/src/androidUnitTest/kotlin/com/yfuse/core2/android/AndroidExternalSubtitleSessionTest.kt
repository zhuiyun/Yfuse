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
import kotlin.test.assertTrue

class AndroidExternalSubtitleSessionTest {
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
