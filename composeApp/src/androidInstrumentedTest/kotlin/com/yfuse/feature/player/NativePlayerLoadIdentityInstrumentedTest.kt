package com.yfuse.feature.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mediadevkit.sdk.MDKPlayer
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exercises the bundled SDK, rather than assuming what setMedia does to its prior LOADED bit. */
@RunWith(AndroidJUnit4::class)
class NativePlayerLoadIdentityInstrumentedTest {
    @Test(timeout = 180000L)
    fun loaded_metadata_after_set_media_belongs_to_the_replacement_source() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.yfuse.validation")
        val files =
            listOf(
                wave(File(context.cacheDir, "mdk-load-a.wav"), 1000),
                wave(File(context.cacheDir, "mdk-load-b.wav"), 3000),
            )
        try {
            MDKPlayer().use { player ->
                listOf(0, 1, 1, 0, 0, 1, 1, 0).forEachIndexed { round, target ->
                    val expectedMs = if (round % 2 == 0) 1000L else 3000L
                    player.setState(MDKPlayer.STATE_STOPPED)
                    runBlocking { assertTrue(awaitMdkStopped({ player.state() == MDKPlayer.STATE_STOPPED })) }
                    // Rewriting the same path makes a stale same-URL snapshot distinguishable.
                    wave(files[target], expectedMs.toInt())
                    player.setMedia(files[target].absolutePath)
                    val opening = player.playbackSnapshot()
                    assertEquals(0, opening[0].toInt() and (MDKPlayer.STATUS_LOADED or MDKPlayer.STATUS_PREPARED))
                    assertEquals(listOf(0.0, 0.0, 0.0), opening.slice(1..3))
                    assertEquals(0L, player.position())
                    assertEquals(0L, player.duration())
                    assertEquals(0L, player.bufferedDuration())
                    assertEquals(0, player.videoHeight())
                    assertTrue(player.playbackEvidence().isEmpty())
                    player.prepare()
                    if (round % 2 != 0) player.setState(MDKPlayer.STATE_PLAYING)
                    val deadline = System.nanoTime() + 15000000000L
                    var loaded = false
                    var lastSnapshot = doubleArrayOf()
                    while (System.nanoTime() < deadline) {
                        val snapshot = player.playbackSnapshot()
                        lastSnapshot = snapshot
                        val ready = MDKPlayer.STATUS_LOADED or MDKPlayer.STATUS_PREPARED
                        if (snapshot[0].toInt() and ready != 0) {
                            assertEquals(
                                "Prior LOADED metadata leaked into replacement round $round",
                                expectedMs,
                                snapshot[2].toLong(),
                            )
                            loaded = true
                            break
                        }
                        Thread.sleep(10L)
                    }
                    assertTrue(
                        "MDK round $round did not preload: ${lastSnapshot.contentToString()}, error=${player.lastError()}",
                        loaded,
                    )
                    waitUntil(5000L, { "round=$round snapshot=${player.playbackSnapshot().contentToString()}" }) {
                        player.mediaStatus() and MDKPlayer.STATUS_PREPARED != 0
                    }
                    player.setState(MDKPlayer.STATE_PAUSED)
                    waitUntil(5000L, { "round=$round state=${player.state()}" }) {
                        player.state() == MDKPlayer.STATE_PAUSED
                    }
                    player.seek(250L)
                    waitUntil(5000L) { kotlin.math.abs(player.position() - 250L) < 150L }
                }
            }
        } finally {
            files.forEach(File::delete)
        }
    }

    @Test(timeout = 30000L)
    fun failed_prepare_does_not_poison_the_next_media_generation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.yfuse.validation")
        val file = wave(File(context.cacheDir, "mdk-recover.wav"), 1000)
        try {
            MDKPlayer().use { player ->
                player.setMedia(File(context.cacheDir, "mdk-missing-source.wav").absolutePath)
                player.prepare()
                waitUntil(5000L) { player.mediaStatus() and MDKPlayer.STATUS_INVALID != 0 }
                player.setState(MDKPlayer.STATE_STOPPED)
                runBlocking { assertTrue(awaitMdkStopped({ player.state() == MDKPlayer.STATE_STOPPED })) }
                player.setMedia(file.absolutePath)
                assertEquals(0, player.mediaStatus() and MDKPlayer.STATUS_INVALID)
                assertEquals(0L, player.duration())
                player.prepare()
                waitUntil(5000L) {
                    player.mediaStatus() and (MDKPlayer.STATUS_LOADED or MDKPlayer.STATUS_PREPARED) != 0
                }
                assertEquals(1000L, player.duration())
            }
        } finally {
            file.delete()
        }
    }

    @Test(timeout = 180000L)
    fun mpv_identity_and_latest_seek_follow_native_file_replacement_and_playlist_expansion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.yfuse.validation")
        val files =
            listOf(
                wave(File(context.cacheDir, "mpv-load-a.wav"), 1000),
                wave(File(context.cacheDir, "mpv-load-b.wav"), 3000),
            )
        val playlist =
            File(context.cacheDir, "mpv-load-list.m3u").apply {
                writeText(
                    "#EXTM3U\n" + files[1].absolutePath + "\n",
                )
            }
        val player = checkNotNull(MPVLib.create(context))
        try {
            for ((name, value) in listOf(
                "config" to "no",
                "vo" to "null",
                "ao" to "null",
                "keep-open" to "always",
                "idle" to "yes",
                "pause" to "yes",
            )) {
                player.setOptionString(name, value)
            }
            player.init()
            var prior: MpvNativeFileIdentity? = null
            for (file in listOf(files[0], files[1], files[0], playlist)) {
                val gate = MpvVideoReadinessGate()
                val pending = MpvPendingSeek(800L)
                gate.onLoadRequested()
                player.command(arrayOf("loadfile", file.absolutePath))
                val entry = checkNotNull(player.getPropertyString("playlist/0/id")?.toLongOrNull())
                gate.bindNativeIdentity(entry, file.absolutePath)
                prior?.let { assertTrue("Predecessor file must not start the replacement", !gate.onStartFile(it)) }
                pending.updateIfPending(250L)
                waitUntil(15000L) {
                    gate.onStartFile(player.currentMpvFileIdentity()) &&
                        player.getPropertyDouble("time-pos")?.isFinite() == true
                }
                val identity = checkNotNull(player.currentMpvFileIdentity())
                assertTrue(
                    "Requested direct file or explicit playlist child must be accepted: $identity",
                    gate.onStartFile(identity),
                )
                assertTrue(gate.onFileLoaded(identity))
                assertTrue(
                    pending.applyIfPending { target ->
                        player.command(arrayOf("seek", (target / 1000.0).toString(), "absolute+exact"))
                        true
                    },
                )
                waitUntil(5000L) {
                    player.getPropertyDouble("time-pos")?.let { kotlin.math.abs(it - 0.25) < 0.15 } ==
                        true
                }
                prior = identity
            }
        } finally {
            player.destroy()
            files.forEach(File::delete)
            playlist.delete()
        }
    }

    private fun waitUntil(
        timeoutMs: Long,
        detail: () -> String = { "" },
        ready: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1000000L
        while (!ready()) {
            assertTrue(
                "Native playback state did not become ready within ${timeoutMs}ms ${detail()}",
                System.nanoTime() < deadline,
            )
            Thread.sleep(10L)
        }
    }

    private fun wave(
        file: File,
        durationMs: Int,
    ): File {
        val size = durationMs * 16
        val data = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN)
        data
            .put("RIFF".toByteArray())
            .putInt(36 + size)
            .put("WAVEfmt ".toByteArray())
            .putInt(16)
        data
            .putShort(1)
            .putShort(1)
            .putInt(8000)
            .putInt(16000)
            .putShort(2)
            .putShort(16)
        data.put("data".toByteArray()).putInt(size)
        file.writeBytes(data.array())
        return file
    }
}
