package com.yfuse.core2.android

import com.yfuse.core2.subtitle.YAssSubtitleSource
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitlePayload
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidAssSubtitleRendererTest {
    @Test
    fun slow_font_render_conflates_clock_updates() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val positions = Collections.synchronizedList(mutableListOf<Long>())
        val backend =
            object : AssSubtitleBackend {
                override fun open(
                    source: YAssSubtitleSource,
                    width: Int,
                    height: Int,
                    cacheMegabytes: Int,
                    styleOverrides: List<String>,
                ) = 1L

                override fun render(
                    handle: Long,
                    positionUs: Long,
                    revision: Long,
                    cues: List<YSubtitleCue>,
                ): List<YSubtitlePayload.BitmapArgb> {
                    positions += positionUs
                    if (positions.size == 1) {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    } else {
                        finished.countDown()
                    }
                    return emptyList()
                }

                override fun close(handle: Long) {
                    closed.countDown()
                }
            }
        val pool = PlaybackMemoryPool(96L * 1024 * 1024)
        val renderer = AndroidAssSubtitleRenderer(backend) { pool.acquire(PlaybackBufferKind.Subtitle, it) }
        val cues =
            listOf(
                YSubtitleCue(
                    "event",
                    0,
                    10_000_000,
                    YSubtitlePayload.AssEvent(YAssSubtitleSource(byteArrayOf(), true)),
                ),
            )
        try {
            renderer.submit(cues, 0)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            renderer.submit(cues, 100_000)
            renderer.submit(cues, 200_000)
            renderer.submit(cues, 300_000)
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(0L, 300_000L), positions.toList())
        } finally {
            release.countDown()
            renderer.close()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun continuous_faster_clock_input_does_not_starve_completed_frames() {
        val backend = SteppedAssBackend()
        val pool = PlaybackMemoryPool(96L * 1024 * 1024)
        val renderer = AndroidAssSubtitleRenderer(backend) { pool.acquire(PlaybackBufferKind.Subtitle, it) }
        val cues = assCues()
        try {
            renderer.submit(cues, 1_000_000)
            backend.awaitStarted(0)
            renderer.submit(cues, 1_010_000)
            renderer.submit(cues, 1_020_000)
            backend.release(0)
            backend.awaitStarted(1)
            assertEquals(
                backend.color(0),
                renderer.bitmaps.value
                    .single()
                    .pixels
                    .single(),
            )
            renderer.submit(cues, 1_030_000)
            backend.release(1)
            backend.awaitStarted(2)
            assertEquals(
                backend.color(1),
                renderer.bitmaps.value
                    .single()
                    .pixels
                    .single(),
            )
        } finally {
            backend.releaseAll()
            renderer.close()
            backend.awaitClosed()
        }
    }

    @Test
    fun seek_track_and_appearance_changes_reject_frames_from_the_previous_generation() {
        val changes =
            listOf("backward_seek", "forward_seek", "small_forward_seek", "track", "appearance", "cue_rollover")
        for (change in changes) {
            val backend = SteppedAssBackend()
            val pool = PlaybackMemoryPool(96L * 1024 * 1024)
            val renderer = AndroidAssSubtitleRenderer(backend) { pool.acquire(PlaybackBufferKind.Subtitle, it) }
            val initialCues =
                if (change == "cue_rollover") {
                    val initial = assCues().single()
                    listOf(initial.copy(endUs = 1_005_000), initial.copy(id = "next", startUs = 1_005_000))
                } else {
                    assCues()
                }
            val nextCues = if (change == "track") assCues() else initialCues
            val styles = if (change == "appearance") listOf("PrimaryColour=&H000000ff") else emptyList()
            val nextGeneration = if (change == "small_forward_seek") 1L else 0L
            val nextPosition =
                when (change) {
                    "backward_seek" -> 500_000L
                    "forward_seek" -> 2_000_000L
                    else -> 1_010_000L
                }
            try {
                renderer.submit(initialCues, 1_000_000)
                backend.awaitStarted(0)
                renderer.submit(nextCues, nextPosition, styleOverrides = styles, timelineGeneration = nextGeneration)
                backend.release(0)
                backend.awaitStarted(1)
                assertTrue(renderer.bitmaps.value.isEmpty(), "Previous frame survived $change")
                renderer.submit(
                    nextCues,
                    nextPosition + 10_000L,
                    styleOverrides = styles,
                    timelineGeneration = nextGeneration,
                )
                backend.release(1)
                backend.awaitStarted(2)
                assertEquals(
                    backend.color(1),
                    renderer.bitmaps.value
                        .single()
                        .pixels
                        .single(),
                    change,
                )
            } finally {
                backend.releaseAll()
                renderer.close()
                backend.awaitClosed()
            }
        }
    }

    @Test
    fun close_returns_while_native_render_is_busy_and_releases_handle_after_render() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val backend =
            object : AssSubtitleBackend {
                override fun open(
                    source: YAssSubtitleSource,
                    width: Int,
                    height: Int,
                    cacheMegabytes: Int,
                    styleOverrides: List<String>,
                ) = 2L

                override fun render(
                    handle: Long,
                    positionUs: Long,
                    revision: Long,
                    cues: List<YSubtitleCue>,
                ): List<YSubtitlePayload.BitmapArgb> {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return emptyList()
                }

                override fun close(handle: Long) {
                    closed.countDown()
                }
            }
        val pool = PlaybackMemoryPool(96L * 1024 * 1024)
        val renderer = AndroidAssSubtitleRenderer(backend) { pool.acquire(PlaybackBufferKind.Subtitle, it) }
        try {
            renderer.submit(
                listOf(
                    YSubtitleCue(
                        "event",
                        0,
                        1_000_000,
                        YSubtitlePayload.AssEvent(YAssSubtitleSource(byteArrayOf(), true)),
                    ),
                ),
                0,
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))
            renderer.close()
            assertEquals(1L, closed.count)
            release.countDown()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertTrue(renderer.bitmaps.value.isEmpty())
        } finally {
            release.countDown()
            renderer.close()
        }
    }
}

private fun assCues(): List<YSubtitleCue> =
    listOf(YSubtitleCue("event", 0, 10_000_000, YSubtitlePayload.AssEvent(YAssSubtitleSource(byteArrayOf(), true))))

/** Gates each render so a newer clock request is guaranteed to be pending at completion. */
private class SteppedAssBackend : AssSubtitleBackend {
    private val started = List(3) { CountDownLatch(1) }
    private val released = List(3) { CountDownLatch(1) }
    private val closed = ConcurrentHashMap<Long, CountDownLatch>()
    private var calls = 0
    private var handleCount = 0L

    override fun open(
        source: YAssSubtitleSource,
        width: Int,
        height: Int,
        cacheMegabytes: Int,
        styleOverrides: List<String>,
    ): Long {
        val handle = ++handleCount
        closed[handle] = CountDownLatch(1)
        return handle
    }

    override fun render(
        handle: Long,
        positionUs: Long,
        revision: Long,
        cues: List<YSubtitleCue>,
    ): List<YSubtitlePayload.BitmapArgb> {
        val index = calls++
        started[index].countDown()
        check(released[index].await(5, TimeUnit.SECONDS))
        return listOf(YSubtitlePayload.BitmapArgb(1, 1, 0, 0, 1, 1, intArrayOf(color(index))))
    }

    override fun close(handle: Long) {
        requireNotNull(closed[handle]).countDown()
    }

    fun color(index: Int): Int = 0xff000000.toInt() or (index + 1)

    fun awaitStarted(index: Int) {
        assertTrue(started[index].await(5, TimeUnit.SECONDS), "Render $index did not start")
    }

    fun release(index: Int) {
        released[index].countDown()
    }

    fun releaseAll() {
        released.forEach(CountDownLatch::countDown)
    }

    fun awaitClosed() {
        closed.values.forEach { assertTrue(it.await(5, TimeUnit.SECONDS), "Native handle was not closed") }
    }
}
