package com.yfuse.core2.android

import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.legacy.YNativeSubtitleStyle
import com.yfuse.core2.legacy.YNativeSubtitleStyleTarget
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidChildPlaybackSettingsTest {
    @Test
    fun foreground_and_style_changes_during_child_attach_win_after_the_stale_snapshot() {
        val pending = LinkedBlockingQueue<Unit>()
        val settings = AndroidChildPlaybackSettings { pending.add(Unit) }
        val oldStyle = YNativeSubtitleStyle(offsetMs = 100L)
        val latestStyle = YNativeSubtitleStyle(offsetMs = 750L, scale = 1.5f)
        settings.setVideoSuspended(true)
        settings.setSubtitleStyle(oldStyle)
        pending.clear()
        val enteredAttach = CountDownLatch(1)
        val resumeAttach = CountDownLatch(1)
        val child = RecordingChild()
        child.beforeFirstVideoChange = {
            enteredAttach.countDown()
            check(resumeAttach.await(5, TimeUnit.SECONDS))
        }
        val router = Executors.newSingleThreadExecutor()
        try {
            val completed =
                router.submit {
                    settings.applyTo(child) // Attaching child has already read the old snapshot.
                    while (pending.poll() != null) settings.applyTo(child)
                }
            assertTrue(enteredAttach.await(5, TimeUnit.SECONDS))
            settings.setVideoSuspended(false)
            settings.setSubtitleStyle(latestStyle)
            assertFalse(settings.videoSuspended)
            // Neither setter calls native code on this caller while attachment is in flight.
            assertTrue(child.videoChanges.isEmpty())
            resumeAttach.countDown()
            completed.get(5, TimeUnit.SECONDS)
            assertFalse(child.videoChanges.last())
            assertEquals(latestStyle, child.styles.last())
            assertTrue(child.callerThreads.all { it !== Thread.currentThread() })

            val nextChild = RecordingChild()
            router.submit { settings.applyTo(nextChild) }.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(false), nextChild.videoChanges)
            assertEquals(listOf(latestStyle), nextChild.styles)
        } finally {
            resumeAttach.countDown()
            router.shutdownNow()
            assertTrue(router.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class RecordingChild :
        YPlayer,
        YNativeSubtitleStyleTarget {
        override val state = MutableStateFlow(YPlayerState())
        var beforeFirstVideoChange: (() -> Unit)? = null
        val videoChanges = mutableListOf<Boolean>()
        val styles = mutableListOf<YNativeSubtitleStyle>()
        val callerThreads = mutableListOf<Thread>()

        override fun setVideoSuspended(suspended: Boolean) {
            beforeFirstVideoChange?.also { beforeFirstVideoChange = null }?.invoke()
            callerThreads += Thread.currentThread()
            videoChanges += suspended
        }

        override fun setNativeSubtitleStyle(style: YNativeSubtitleStyle): Boolean {
            callerThreads += Thread.currentThread()
            styles += style
            return true
        }

        override fun play() = Unit

        override fun pause() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun setSpeed(speed: Float) = Unit

        override fun selectTrack(
            type: YTrackType,
            id: String,
        ) = Unit

        override fun selectItem(index: Int) = Unit

        override fun retry() = Unit

        override fun release() = Unit
    }
}
