package com.yfuse.feature.player

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@UnstableApi
class ExoSecondarySubtitleRecoveryTest {
    @Test
    fun reselect_after_error_prepares_same_queue_at_current_main_position() {
        val fake = SecondaryPlayerFake()
        val controller = ExoSecondarySubtitleController(fake.player, ExoDualSubtitleCueMerger())
        val items = listOf(MediaItem.Builder().setMediaId("episode").build())
        try {
            assertTrue(controller.select(identity, items, 0, 1_000L, 1f, true))
            assertEquals(1, fake.prepareCalls)
            fake.fail()
            assertFalse(controller.needsReconciliation)

            assertTrue(controller.select(identity, items, 0, 42_000L, 1.5f, false))
            assertEquals(2, fake.prepareCalls)
            assertEquals(42_000L, fake.position)
            assertEquals(1.5f, fake.speed)
            assertFalse(fake.playWhenReady)
            assertTrue(controller.needsReconciliation)
        } finally {
            controller.release()
        }
        assertTrue(fake.listeners.isEmpty())
    }

    @Test
    fun normal_reselection_keeps_prepared_pipeline_and_reenable_resynchronizes() {
        val fake = SecondaryPlayerFake()
        val controller = ExoSecondarySubtitleController(fake.player, ExoDualSubtitleCueMerger())
        val items = listOf(MediaItem.Builder().setMediaId("episode").build())
        try {
            controller.select(identity, items, 0, 1_000L, 1f, true)
            controller.select(identity, items, 0, 2_000L, 1f, true)
            assertEquals(1, fake.prepareCalls)
            controller.disable()
            controller.select(identity, items, 0, 9_000L, 1f, true)
            assertEquals(2, fake.prepareCalls)
            assertEquals(9_000L, fake.position)
        } finally {
            controller.release()
        }
    }

    private val identity = ExoSubtitleTrackIdentity("sub-2", "en", null, "text/vtt", null, 0, 0, null, null)

    /** Models Media3's public Player contract, without constructing an Android audio/video runtime. */
    private class SecondaryPlayerFake : InvocationHandler {
        val listeners = mutableSetOf<Player.Listener>()
        var prepareCalls = 0
        var position = 0L
        var speed = 1f
        var playWhenReady = false
        private var itemCount = 0
        private var itemIndex = 0
        private var parameters = TrackSelectionParameters.Builder().build()
        val player: Player =
            Proxy.newProxyInstance(
                Player::class.java.classLoader,
                arrayOf(Player::class.java),
                this,
            ) as Player

        fun fail() {
            listeners.toList().forEach {
                it.onPlayerError(
                    PlaybackException(
                        "injected subtitle failure",
                        null,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    ),
                )
            }
        }

        override fun invoke(
            proxy: Any,
            method: Method,
            args: Array<out Any?>?,
        ): Any? {
            val values = args.orEmpty()
            return when (method.name) {
                "addListener" -> {
                    listeners += values[0] as Player.Listener
                    null
                }
                "removeListener" -> {
                    listeners -= values[0] as Player.Listener
                    null
                }
                "getTrackSelectionParameters" -> parameters
                "setTrackSelectionParameters" -> {
                    parameters = values[0] as TrackSelectionParameters
                    null
                }
                "getMediaItemCount" -> itemCount
                "getCurrentMediaItemIndex" -> itemIndex
                "getCurrentPosition" -> position
                "getCurrentTracks" -> Tracks.EMPTY
                "getPlaybackParameters" -> PlaybackParameters(speed)
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> {
                    playWhenReady = values[0] as Boolean
                    null
                }
                "setPlaybackSpeed" -> {
                    speed = values[0] as Float
                    null
                }
                "setMediaItems" -> {
                    itemCount = (values[0] as List<*>).size
                    itemIndex = values[1] as Int
                    position = values[2] as Long
                    null
                }
                "seekTo" -> {
                    if (values.size == 2) itemIndex = values[0] as Int
                    position = values.last() as Long
                    null
                }
                "prepare" -> {
                    prepareCalls++
                    null
                }
                "stop", "release" -> null
                else -> error("Unexpected Player operation: ${method.name}")
            }
        }
    }
}
