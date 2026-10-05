package com.yfuse.core2.demux

import com.yfuse.core2.api.YChapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class YMatroskaChaptersTest {
    @Test
    fun the_default_edition_names_its_chapters_preferring_a_chinese_title() {
        val chapters =
            element(
                0x1043A770,
                // A hidden edition first, as authoring tools leave behind.
                element(0x45B9, uint(0x45BD, 1), atom(0, "Hidden")),
                element(
                    0x45B9,
                    uint(0x45DB, 1),
                    atom(90_000_000_000L, "Opening", "eng", "片头曲" to "chi"),
                    atom(0, "Prologue", "eng", "序章" to "zh-Hans"),
                    atom(1_320_000_000_000L, "Part B"),
                ),
            )

        val result = YMatroskaChapterParser.parse(file(tracks(), chapters, cluster()))

        assertEquals(
            listOf(YChapter(0, "序章"), YChapter(90_000, "片头曲"), YChapter(1_320_000, "Part B")),
            assertIs<YMatroskaChaptersResult.Found>(result).chapters,
        )
    }

    @Test
    fun hidden_disabled_and_linked_chapters_are_left_out() {
        val chapters =
            element(
                0x1043A770,
                element(
                    0x45B9,
                    atom(0, "Kept"),
                    atom(10_000_000_000L, "Hidden", extra = uint(0x98, 1)),
                    atom(20_000_000_000L, "Disabled", extra = uint(0x4598, 0)),
                    atom(30_000_000_000L, "Other file", extra = element(0x6E67, ByteArray(16))),
                ),
            )

        val result = YMatroskaChapterParser.parse(file(tracks(), chapters, cluster()))

        assertEquals(listOf(YChapter(0, "Kept")), assertIs<YMatroskaChaptersResult.Found>(result).chapters)
    }

    @Test
    fun a_header_without_chapters_is_absent_once_its_first_cluster_is_reached() {
        assertEquals(YMatroskaChaptersResult.Absent, YMatroskaChapterParser.parse(file(tracks(), cluster())))
    }

    @Test
    fun chapters_listed_past_the_bytes_read_are_reported_where_they_are() {
        // SeekHead -> Chapters at 9 MB into the segment, behind attachments larger than the prefix.
        val seekHead =
            element(
                0x114D9B74,
                element(0x4DBB, element(0x53AB, byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70)), uint(0x53AC, 9_000_000)),
            )
        val attachments = elementHeader(0x1941A469, 8_000_000)
        val bytes = file(seekHead, tracks(), attachments)

        val result = YMatroskaChapterParser.parse(bytes)

        val segmentDataStart = ebmlHeader().size + 12
        assertEquals(YMatroskaChaptersResult.Elsewhere(segmentDataStart + 9_000_000L), result)
    }

    @Test
    fun a_prefix_that_stops_inside_the_header_or_is_not_matroska_is_said_so() {
        // Cut inside the Chapters element.
        val full = file(tracks(), element(0x1043A770, element(0x45B9, atom(0, "A"))))
        assertEquals(YMatroskaChaptersResult.Truncated, YMatroskaChapterParser.parse(full.copyOf(full.size - 5)))
        assertEquals(YMatroskaChaptersResult.Invalid, YMatroskaChapterParser.parse(byteArrayOf(0x47, 0x40, 0x00, 0x10)))
    }

    private fun atom(
        startNs: Long,
        title: String,
        language: String? = null,
        vararg more: Pair<String, String>,
        extra: ByteArray = ByteArray(0),
    ): ByteArray =
        element(
            0xB6,
            uint(0x91, startNs),
            display(title, language),
            *more.map { (text, lang) -> display(text, lang) }.toTypedArray(),
            extra,
        )

    private fun display(
        title: String,
        language: String?,
    ): ByteArray {
        val languageElement =
            when {
                language == null -> ByteArray(0)
                language.contains('-') -> element(0x437D, language.encodeToByteArray())
                else -> element(0x437C, language.encodeToByteArray())
            }
        return element(0x80, element(0x85, title.encodeToByteArray()), languageElement)
    }

    private fun tracks(): ByteArray =
        element(0x1654AE6B, element(0xAE, uint(0x83, 1), element(0x86, "V_MPEGH/ISO/HEVC".encodeToByteArray())))

    private fun cluster(): ByteArray = element(0x1F43B675, uint(0xE7, 0))

    private fun ebmlHeader(): ByteArray = element(0x1A45DFA3, element(0x4282, "matroska".encodeToByteArray()))

    /** EBML header, then a Segment with an 8-byte size field holding [children]. */
    private fun file(vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { all, child -> all + child }
        return ebmlHeader() + id(0x18538067) + size8(body.size.toLong()) + body
    }

    private fun element(
        id: Long,
        vararg children: ByteArray,
    ): ByteArray {
        val body = children.fold(ByteArray(0)) { all, child -> all + child }
        return id(id) + size8(body.size.toLong()) + body
    }

    private fun element(
        id: Long,
        payload: ByteArray,
    ): ByteArray = id(id) + size8(payload.size.toLong()) + payload

    /** Only the header of an element whose [size] bytes are not in the prefix. */
    private fun elementHeader(
        id: Long,
        size: Long,
    ): ByteArray = id(id) + size8(size)

    private fun uint(
        id: Long,
        value: Long,
    ): ByteArray = element(id, ByteArray(8) { (value shr (8 * (7 - it))).toByte() })

    private fun id(value: Long): ByteArray {
        val length =
            when {
                value > 0xFFFFFF -> 4
                value > 0xFFFF -> 3
                value > 0xFF -> 2
                else -> 1
            }
        return ByteArray(length) { (value shr (8 * (length - 1 - it))).toByte() }
    }

    private fun size8(value: Long): ByteArray = byteArrayOf(0x01) + ByteArray(7) { (value shr (8 * (6 - it))).toByte() }
}
