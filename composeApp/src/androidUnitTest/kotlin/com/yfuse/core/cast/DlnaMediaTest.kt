package com.yfuse.core.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DlnaMediaTest {
    @Test
    fun an_original_mkv_is_announced_as_matroska_with_byte_seeking() {
        val format = dlnaMediaFormat("https://emby.example/videos/42/original.mkv?Static=true&api_key=t")

        assertEquals("video/x-matroska", format.mimeType)
        assertEquals("mkv", format.extension)
        assertTrue(format.byteSeekable)
        assertEquals(
            "http-get:*:video/x-matroska:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            format.protocolInfo,
        )
    }

    @Test
    fun an_extensionless_static_stream_takes_the_container_of_the_original_file() {
        val url = "https://emby.example/Videos/42/stream?static=true&MediaSourceId=a&api_key=t"

        assertEquals("video/x-matroska", dlnaMediaFormat(url, container = "MKV").mimeType)
        // Jellyfin reports ffprobe names.
        assertEquals("video/x-matroska", dlnaMediaFormat(url, container = "matroska,webm").mimeType)
        assertEquals("video/mp4", dlnaMediaFormat(url, container = "mov,mp4,m4a,3gp,3g2,mj2").mimeType)
        assertEquals("video/mp2t", dlnaMediaFormat(url, container = "mpegts").mimeType)
        // Unknown stays what every DLNA load used to be told.
        assertEquals("video/mp4", dlnaMediaFormat(url, container = null).mimeType)
    }

    @Test
    fun a_transcode_is_described_by_its_own_address_and_is_not_byte_seekable() {
        val progressive =
            dlnaMediaFormat(
                "https://emby.example/Videos/42/stream.mp4?static=false&VideoCodec=h264&AudioCodec=aac",
                container = "mkv",
            )
        assertEquals("video/mp4", progressive.mimeType)
        assertFalse(progressive.byteSeekable)
        assertTrue(progressive.contentFeatures.startsWith("DLNA.ORG_OP=00;DLNA.ORG_CI=1;"))

        val hls = dlnaMediaFormat("https://emby.example/Videos/42/master.m3u8?VideoCodec=h264", container = "mkv")
        assertEquals("application/x-mpegURL", hls.mimeType)
        assertEquals("m3u8", hls.extension)
        assertFalse(hls.byteSeekable)

        assertTrue(dlnaMediaFormat("http://nas:8096/Videos/1/stream.mkv?Static=true&VideoCodec=copy").byteSeekable)
    }

    @Test
    fun metadata_escapes_the_address_and_carries_size_and_duration_when_known() {
        val url = "http://192.168.1.5:41234/yfuse-cast/abc/media.mkv?a=1&b=2"
        val metadata =
            dlnaMetadata(
                url = url,
                title = "Tom & \"Jerry\" <1>",
                format = dlnaMediaFormat(url),
                durationMs = 2_470_504L,
                sizeBytes = 4_655_267_216L,
            )

        assertTrue("<dc:title>Tom &amp; &quot;Jerry&quot; &lt;1&gt;</dc:title>" in metadata)
        assertTrue(
            """<res protocolInfo="http-get:*:video/x-matroska:DLNA.ORG_OP=01;""" in metadata,
            metadata,
        )
        assertTrue(""" size="4655267216" duration="0:41:10.504">""" in metadata, metadata)
        assertTrue(">http://192.168.1.5:41234/yfuse-cast/abc/media.mkv?a=1&amp;b=2</res>" in metadata)
        assertFalse("size=" in dlnaMetadata(url, "t", dlnaMediaFormat(url)))
    }

    @Test
    fun didl_duration_pads_minutes_seconds_and_milliseconds_but_not_hours() {
        assertEquals("0:00:00.000", didlDuration(0L))
        assertEquals("1:02:03.004", didlDuration(3_723_004L))
        assertEquals("12:00:00.000", didlDuration(43_200_000L))
    }
}
