package com.yfuse.core2.android

import android.media.MediaFormat
import android.view.Surface
import com.yfuse.core.logging.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Exact codec configuration, including CSD bytes. Container duration/bitrate are not decoder state. */
internal data class VideoDecoderReuseKey(
    val mime: String,
    val decoderName: String?,
    val integers: List<Int?>,
    val csd: List<List<Byte>>,
)

internal fun videoDecoderReuseKey(
    format: MediaFormat,
    decoderName: String?,
): VideoDecoderReuseKey? {
    val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
    if (mime !in setOf("video/avc", "video/hevc")) return null
    val csd =
        (0..2).map { index ->
            val buffer = format.getByteBuffer("csd-$index")?.duplicate() ?: return@map emptyList<Byte>()
            if (buffer.remaining() > 64 * 1024) return null
            ByteArray(buffer.remaining()).also { buffer.get(it) }.toList()
        }
    if (csd.all { it.isEmpty() }) return null
    val keys =
        listOf(
            "width",
            "height",
            "profile",
            "level",
            "max-input-size",
            "rotation-degrees",
            "color-standard",
            "color-range",
            "color-transfer",
            "sar-width",
            "sar-height",
            "crop-left",
            "crop-right",
            "crop-top",
            "crop-bottom",
        )
    return VideoDecoderReuseKey(
        mime,
        decoderName,
        keys.map { key ->
            if (format.containsKey(key)) format.getInteger(key) else null
        },
        csd,
    )
}

/** One router-owned decoder, transferred only after the old player's release barrier completes. */
internal class AndroidVideoDecoderHandoff : AutoCloseable {
    private data class Entry(
        val key: VideoDecoderReuseKey,
        val surface: Surface,
        val node: AndroidMediaCodecVideoNode,
    )

    private var entry: Entry? = null
    private var expiry: ScheduledFuture<*>? = null

    @Synchronized
    fun offer(
        key: VideoDecoderReuseKey,
        surface: Surface,
        node: AndroidMediaCodecVideoNode,
    ) {
        close()
        node.setOnFrameRenderedListener(listener = null)
        val next = Entry(key, surface, node)
        entry = next
        expiry = releaser.schedule({ expire(next) }, 5L, TimeUnit.SECONDS)
    }

    @Synchronized
    fun take(
        key: VideoDecoderReuseKey?,
        surface: Surface,
    ): AndroidMediaCodecVideoNode? {
        val previous = entry ?: return null
        entry = null
        expiry?.cancel(false)
        expiry = null
        if (key == null || previous.key != key || previous.surface !== surface || !surface.isValid) {
            previous.node.release()
            return null
        }
        return try {
            // A new timestamp epoch rejects any old Surface callback arriving after the flush.
            previous.node.flush()
            AppLog.info(
                category = "player.core2",
                event = "next_item_video_decoder_reused",
                message = "Exact-format SDR decoder reused on the same Surface",
            )
            previous.node
        } catch (_: Exception) {
            previous.node.release()
            null
        }
    }

    @Synchronized
    private fun expire(expected: Entry) {
        if (entry === expected) close()
    }

    @Synchronized
    override fun close() {
        expiry?.cancel(false)
        expiry = null
        val previous = entry
        entry = null
        previous?.node?.release()
    }

    private companion object {
        val releaser =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "YCore-VideoHandoffRelease").apply { isDaemon = true }
            }
    }
}
