package com.yfuse.core2.dolby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class YMatroskaTrackCodecParserTest {
    @Test
    fun `lists every TrackEntry codec including audio the platform extractor hides`() {
        val document =
            matroskaDocument(
                track(type = 1, codecId = "V_MPEGH/ISO/HEVC") +
                    track(type = 2, codecId = "A_TRUEHD") +
                    track(type = 17, codecId = "S_TEXT/UTF8"),
            )

        val result = assertIs<YMatroskaTrackCodecResult.Found>(YMatroskaTrackCodecParser.parse(document))

        assertEquals(
            listOf(
                YMatroskaTrackCodec(trackType = 1, codecId = "V_MPEGH/ISO/HEVC"),
                YMatroskaTrackCodec(trackType = 2, codecId = "A_TRUEHD"),
                YMatroskaTrackCodec(trackType = 17, codecId = "S_TEXT/UTF8"),
            ),
            result.tracks,
        )
        assertEquals(listOf("A_TRUEHD"), result.tracks.filter(YMatroskaTrackCodec::audio).map { it.codecId })
    }

    @Test
    fun `asks for more bytes when the Tracks element is cut short`() {
        val document = matroskaDocument(track(type = 2, codecId = "A_DTS"))

        assertIs<YMatroskaTrackCodecResult.Truncated>(
            YMatroskaTrackCodecParser.parse(document.copyOf(document.size - 3)),
        )
    }

    @Test
    fun `rejects bytes that are not an EBML document`() {
        assertIs<YMatroskaTrackCodecResult.Invalid>(
            YMatroskaTrackCodecParser.parse(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)),
        )
    }
}

private fun matroskaDocument(tracks: ByteArray): ByteArray =
    element(ID_EBML, byteArrayOf()) +
        idBytes(ID_SEGMENT) +
        byteArrayOf(0xff.toByte()) +
        element(ID_INFO, byteArrayOf()) +
        element(ID_TRACKS, tracks)

private fun track(
    type: Long,
    codecId: String,
): ByteArray =
    element(
        ID_TRACK_ENTRY,
        element(ID_TRACK_TYPE, unsigned(type)) + element(ID_CODEC_ID, codecId.encodeToByteArray()),
    )

private fun element(
    id: Long,
    payload: ByteArray,
): ByteArray = idBytes(id) + sizeBytes(payload.size) + payload

private fun idBytes(value: Long): ByteArray {
    val count = ((64 - value.countLeadingZeroBits()) + 7) / 8
    return ByteArray(count) { index ->
        (value ushr ((count - index - 1) * 8)).toByte()
    }
}

private fun sizeBytes(size: Int): ByteArray {
    require(size >= 0)
    val length = (1..4).first { candidate -> size.toLong() < (1L shl (7 * candidate)) - 1L }
    val encoded = size.toLong() or (1L shl (7 * length))
    return ByteArray(length) { index ->
        (encoded ushr ((length - index - 1) * 8)).toByte()
    }
}

private fun unsigned(value: Long): ByteArray {
    val count = maxOf(1, ((64 - value.countLeadingZeroBits()) + 7) / 8)
    return ByteArray(count) { index ->
        (value ushr ((count - index - 1) * 8)).toByte()
    }
}

private const val ID_EBML = 0x1A45DFA3L
private const val ID_SEGMENT = 0x18538067L
private const val ID_INFO = 0x1549A966L
private const val ID_TRACKS = 0x1654AE6BL
private const val ID_TRACK_ENTRY = 0xAEL
private const val ID_TRACK_TYPE = 0x83L
private const val ID_CODEC_ID = 0x86L
