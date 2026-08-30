package com.yfuse.core2.dolby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class YMatroskaDolbyVisionMetadataParserTest {
    @Test
    fun `reads dvvC from a Matroska HEVC video TrackEntry`() {
        val config =
            YDolbyVisionConfig(
                versionMajor = 1,
                versionMinor = 0,
                profile = 8,
                level = 6,
                rpuPresent = true,
                enhancementLayerPresent = false,
                baseLayerPresent = true,
                baseLayerCompatibilityId = 1,
                metadataCompression = 0,
            )
        val document = matroskaDocument(videoTrack(dolbyMapping(TYPE_DVVC, config.toConfigurationBytes())))

        val result =
            assertIs<YMatroskaDolbyVisionMetadataResult.Found>(
                YMatroskaDolbyVisionMetadataParser.parse(document),
            )

        assertEquals("V_MPEGH/ISO/HEVC", result.metadata.codecId)
        assertEquals(TYPE_DVVC, result.metadata.blockAddIdType)
        assertEquals(config, result.metadata.config)
    }

    @Test
    fun `uses BlockAddIDValue when mapping type is zero`() {
        val config =
            YDolbyVisionConfig(
                versionMajor = 1,
                versionMinor = 0,
                profile = 5,
                level = 5,
                rpuPresent = true,
                enhancementLayerPresent = false,
                baseLayerPresent = true,
                baseLayerCompatibilityId = 0,
                metadataCompression = 0,
            )
        val mapping =
            element(
                ID_BLOCK_ADDITION_MAPPING,
                element(ID_BLOCK_ADD_ID_EXTRA_DATA, config.toConfigurationBytes()) +
                    element(ID_BLOCK_ADD_ID_TYPE, unsigned(0)) +
                    element(ID_BLOCK_ADD_ID_VALUE, unsigned(TYPE_DVCC)),
            )

        val result =
            assertIs<YMatroskaDolbyVisionMetadataResult.Found>(
                YMatroskaDolbyVisionMetadataParser.parse(matroskaDocument(videoTrack(mapping))),
            )

        assertEquals(5, result.metadata.config.profile)
        assertEquals(TYPE_DVCC, result.metadata.blockAddIdType)
    }

    @Test
    fun `rejects a Dolby profile that conflicts with the Matroska codec`() {
        val av1Profile =
            YDolbyVisionConfig(
                versionMajor = 1,
                versionMinor = 0,
                profile = 10,
                level = 5,
                rpuPresent = true,
                enhancementLayerPresent = false,
                baseLayerPresent = true,
                baseLayerCompatibilityId = 1,
                metadataCompression = 0,
            )

        assertIs<YMatroskaDolbyVisionMetadataResult.Absent>(
            YMatroskaDolbyVisionMetadataParser.parse(
                matroskaDocument(videoTrack(dolbyMapping(TYPE_DVVC, av1Profile.toConfigurationBytes()))),
            ),
        )
    }

    @Test
    fun `reports a bounded prefix that ends inside Tracks as truncated`() {
        val config =
            YDolbyVisionConfig(1, 0, 8, 6, true, false, true, 1, 0)
        val document = matroskaDocument(videoTrack(dolbyMapping(TYPE_DVVC, config.toConfigurationBytes())))

        assertIs<YMatroskaDolbyVisionMetadataResult.Truncated>(
            YMatroskaDolbyVisionMetadataParser.parse(document.copyOf(document.size - 2)),
        )
    }
}

private fun matroskaDocument(track: ByteArray): ByteArray =
    element(ID_EBML, byteArrayOf()) +
        idBytes(ID_SEGMENT) +
        byteArrayOf(0xff.toByte()) +
        element(ID_INFO, byteArrayOf()) +
        element(ID_TRACKS, element(ID_TRACK_ENTRY, track))

private fun videoTrack(mapping: ByteArray): ByteArray =
    element(ID_TRACK_TYPE, unsigned(1)) +
        element(ID_CODEC_ID, "V_MPEGH/ISO/HEVC".encodeToByteArray()) +
        mapping

private fun dolbyMapping(
    type: Long,
    config: ByteArray,
): ByteArray =
    element(
        ID_BLOCK_ADDITION_MAPPING,
        element(ID_BLOCK_ADD_ID_VALUE, unsigned(2)) +
            element(ID_BLOCK_ADD_ID_TYPE, unsigned(type)) +
            element(ID_BLOCK_ADD_ID_EXTRA_DATA, config),
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
private const val ID_BLOCK_ADDITION_MAPPING = 0x41E4L
private const val ID_BLOCK_ADD_ID_VALUE = 0x41F0L
private const val ID_BLOCK_ADD_ID_TYPE = 0x41E7L
private const val ID_BLOCK_ADD_ID_EXTRA_DATA = 0x41EDL
private const val TYPE_DVCC = 0x64766343L
private const val TYPE_DVVC = 0x64767643L
