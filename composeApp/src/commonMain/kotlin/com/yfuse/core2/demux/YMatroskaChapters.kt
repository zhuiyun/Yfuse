package com.yfuse.core2.demux

import com.yfuse.core2.api.YChapter

/** What a bounded read of a Matroska header says about the file's chapters. */
internal sealed interface YMatroskaChaptersResult {
    data class Found(
        val chapters: List<YChapter>,
    ) : YMatroskaChaptersResult

    /** The header reaches its first Cluster without a Chapters element, and its SeekHead lists none. */
    data object Absent : YMatroskaChaptersResult

    /** The SeekHead places the Chapters element beyond the bytes read, at [offset] in the file. */
    data class Elsewhere(
        val offset: Long,
    ) : YMatroskaChaptersResult

    /** More of the header is needed to decide. */
    data object Truncated : YMatroskaChaptersResult

    /** The bytes do not begin a Matroska/EBML document. */
    data object Invalid : YMatroskaChaptersResult
}

/**
 * Reads the chapters MediaExtractor never reports, from the header bytes the probe has already
 * fetched; muxers write them just after the track list, ahead of attachments and clusters.
 *
 * The default edition is used (else the first visible one). Hidden or disabled chapters, and those
 * of an ordered edition that point into another file, are left out; nested titles retain their path.
 * Of a chapter's titles a Chinese one is preferred, else the first.
 */
internal object YMatroskaChapterParser {
    fun parse(prefix: ByteArray): YMatroskaChaptersResult {
        if (prefix.size < EBML_MAGIC.size) return YMatroskaChaptersResult.Truncated
        if (EBML_MAGIC.indices.any { prefix[it] != EBML_MAGIC[it] }) return YMatroskaChaptersResult.Invalid
        val reader = EbmlReader(prefix)
        val header = reader.element(0) ?: return YMatroskaChaptersResult.Truncated
        var position = header.end ?: return YMatroskaChaptersResult.Truncated
        while (position < prefix.size) {
            val element = reader.element(position) ?: return YMatroskaChaptersResult.Truncated
            if (element.id == ID_SEGMENT) return parseSegment(reader, element.dataStart)
            position = element.end ?: return YMatroskaChaptersResult.Truncated
        }
        return YMatroskaChaptersResult.Truncated
    }

    fun chapterElementSize(header: ByteArray): Int? {
        val element = EbmlReader(header).element(0) ?: return null
        if (element.id != ID_CHAPTERS) return null
        val length = element.dataSize ?: return null
        return (element.dataStart + length).takeIf { it in 1..MAX_CHAPTER_ELEMENT_BYTES }?.toInt()
    }

    fun parseChapterElement(bytes: ByteArray): YMatroskaChaptersResult {
        val reader = EbmlReader(bytes)
        val element = reader.element(0) ?: return YMatroskaChaptersResult.Truncated
        if (element.id != ID_CHAPTERS) return YMatroskaChaptersResult.Invalid
        val end = element.end ?: return YMatroskaChaptersResult.Truncated
        return YMatroskaChaptersResult.Found(reader.chapters(element.dataStart, end))
    }

    private fun parseSegment(
        reader: EbmlReader,
        segmentStart: Int,
    ): YMatroskaChaptersResult {
        var listedAt: Long? = null

        fun notInPrefix(): YMatroskaChaptersResult =
            listedAt
                ?.let { offset -> segmentStart + offset }
                ?.takeIf { it >= reader.size }
                ?.let(YMatroskaChaptersResult::Elsewhere)
                ?: YMatroskaChaptersResult.Truncated

        var position = segmentStart
        while (position < reader.size) {
            val element = reader.element(position) ?: return notInPrefix()
            when (element.id) {
                ID_SEEK_HEAD ->
                    if (listedAt == null) {
                        listedAt = element.end?.let { end -> reader.seekPosition(element.dataStart, end, ID_CHAPTERS) }
                    }
                ID_CHAPTERS -> {
                    val end = element.end ?: return YMatroskaChaptersResult.Truncated
                    return YMatroskaChaptersResult.Found(reader.chapters(element.dataStart, end))
                }
                ID_CLUSTER ->
                    return listedAt?.let { YMatroskaChaptersResult.Elsewhere(segmentStart + it) }
                        ?: YMatroskaChaptersResult.Absent
            }
            position = element.end ?: return notInPrefix()
        }
        return notInPrefix()
    }
}

private class EbmlElement(
    val id: Long,
    val dataStart: Int,
    /** Where the element ends, or null when its size is unknown or runs past the bytes read. */
    val end: Int?,
    val dataSize: Long?,
)

private class Edition(
    val default: Boolean,
    val hidden: Boolean,
    val chapters: List<YChapter>,
)

private class EbmlReader(
    private val bytes: ByteArray,
) {
    val size: Int get() = bytes.size
    private var chapterNodes = 0

    fun element(offset: Int): EbmlElement? {
        val id = vint(offset, keepMarker = true, maximumLength = 4) ?: return null
        val length = vint(offset + id.second, keepMarker = false, maximumLength = 8) ?: return null
        val dataStart = offset + id.second + length.second
        if (dataStart > bytes.size) return null
        val unknownSize = length.first == (1L shl (7 * length.second)) - 1L
        val end =
            length.first
                .takeUnless { unknownSize }
                ?.takeIf { it <= bytes.size - dataStart }
                ?.let { dataStart + it.toInt() }
        return EbmlElement(id.first, dataStart, end, length.first.takeUnless { unknownSize })
    }

    /** Children of [start] until [end], each complete; a damaged child ends the walk. */
    inline fun children(
        start: Int,
        end: Int,
        visit: (EbmlElement, Int) -> Unit,
    ) {
        var position = start
        while (position < end) {
            val child = element(position) ?: return
            val childEnd = child.end?.takeIf { it <= end } ?: return
            visit(child, childEnd)
            position = childEnd
        }
    }

    /** The SeekPosition a SeekHead lists for [target], relative to the segment's data. */
    fun seekPosition(
        start: Int,
        end: Int,
        target: Long,
    ): Long? {
        var found: Long? = null
        children(start, end) { seek, seekEnd ->
            if (seek.id != ID_SEEK || found != null) return@children
            var id: Long? = null
            var position: Long? = null
            children(seek.dataStart, seekEnd) { field, fieldEnd ->
                when (field.id) {
                    ID_SEEK_ID -> id = unsigned(field.dataStart, fieldEnd)
                    ID_SEEK_POSITION -> position = unsigned(field.dataStart, fieldEnd)
                }
            }
            if (id == target) found = position
        }
        return found
    }

    fun chapters(
        start: Int,
        end: Int,
    ): List<YChapter> {
        val editions = mutableListOf<Edition>()
        children(start, end) { child, childEnd ->
            if (child.id == ID_EDITION_ENTRY) editions += edition(child.dataStart, childEnd)
        }
        val chosen =
            editions.firstOrNull { it.default && !it.hidden }
                ?: editions.firstOrNull { !it.hidden }
                ?: editions.firstOrNull()
        return chosen?.chapters.orEmpty().sortedBy(YChapter::startMs)
    }

    private fun edition(
        start: Int,
        end: Int,
    ): Edition {
        var default = false
        var hidden = false
        val chapters = mutableListOf<YChapter>()
        children(start, end) { child, childEnd ->
            when (child.id) {
                ID_EDITION_FLAG_DEFAULT -> default = unsigned(child.dataStart, childEnd) == 1L
                ID_EDITION_FLAG_HIDDEN -> hidden = unsigned(child.dataStart, childEnd) == 1L
                ID_CHAPTER_ATOM ->
                    if (chapters.size <
                        MAX_CHAPTERS
                    ) {
                        chapters.addAll(
                            chapter(child.dataStart, childEnd, 0, "").take(
                                MAX_CHAPTERS - chapters.size,
                            ),
                        )
                    }
            }
        }
        return Edition(default, hidden, chapters)
    }

    private fun chapter(
        start: Int,
        end: Int,
        depth: Int,
        parentTitle: String,
    ): List<YChapter> {
        if (depth >= MAX_CHAPTER_DEPTH || ++chapterNodes > MAX_CHAPTER_NODES) return emptyList()
        val nested = mutableListOf<Pair<Int, Int>>()
        var startNs: Long? = null
        var visible = true
        val titles = mutableListOf<Pair<String, String?>>()
        children(start, end) { child, childEnd ->
            when (child.id) {
                ID_CHAPTER_TIME_START -> startNs = unsigned(child.dataStart, childEnd)
                ID_CHAPTER_FLAG_HIDDEN -> if (unsigned(child.dataStart, childEnd) == 1L) visible = false
                ID_CHAPTER_FLAG_ENABLED -> if (unsigned(child.dataStart, childEnd) == 0L) visible = false
                // Ordered editions can play a span of another file; its times mean nothing here.
                ID_CHAPTER_SEGMENT_UID -> visible = false
                ID_CHAPTER_DISPLAY -> title(child.dataStart, childEnd)?.let(titles::add)
                ID_CHAPTER_ATOM -> if (nested.size < MAX_CHAPTERS) nested += child.dataStart to childEnd
            }
        }
        val time = startNs?.takeIf { visible && it >= 0L } ?: return emptyList()
        val title =
            titles.firstOrNull { (_, language) -> language.chinese() }?.first
                ?: titles.firstOrNull()?.first
                ?: ""
        val fullTitle = listOf(parentTitle, title).filter(String::isNotBlank).joinToString(" / ")
        val result = mutableListOf<YChapter>()
        for ((childStart, childEnd) in nested) {
            if (result.size >= MAX_CHAPTERS) break
            result += chapter(childStart, childEnd, depth + 1, fullTitle).take(MAX_CHAPTERS - result.size)
        }
        val current = YChapter(startMs = time / NANOS_PER_MILLISECOND, title = fullTitle)
        if (result.none { it.startMs == current.startMs }) result.add(0, current)
        return result.take(MAX_CHAPTERS)
    }

    /** One ChapterDisplay: its ChapString and language (ChapLanguageIETF over ChapLanguage). */
    private fun title(
        start: Int,
        end: Int,
    ): Pair<String, String?>? {
        var text: String? = null
        var language: String? = null
        var ietf: String? = null
        children(start, end) { child, childEnd ->
            when (child.id) {
                ID_CHAP_STRING -> text = string(child.dataStart, childEnd)
                ID_CHAP_LANGUAGE -> language = string(child.dataStart, childEnd)
                ID_CHAP_LANGUAGE_IETF -> ietf = string(child.dataStart, childEnd)
            }
        }
        return text?.let { it to (ietf ?: language) }
    }

    private fun String?.chinese(): Boolean {
        val language = this?.lowercase() ?: return false
        return language == "chi" || language == "zho" || language == "zh" || language.startsWith("zh-")
    }

    private fun vint(
        offset: Int,
        keepMarker: Boolean,
        maximumLength: Int,
    ): Pair<Long, Int>? {
        if (offset !in bytes.indices) return null
        val first = bytes[offset].toInt() and 0xff
        if (first == 0) return null
        var length = 1
        while (first and (0x80 shr (length - 1)) == 0) length++
        if (length > maximumLength || offset > bytes.size - length) return null
        var value = if (keepMarker) first.toLong() else (first and ((0x80 shr (length - 1)) - 1)).toLong()
        for (index in 1 until length) value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
        return value to length
    }

    fun unsigned(
        start: Int,
        end: Int,
    ): Long? {
        if (start >= end || end - start > 8) return null
        var value = 0L
        for (index in start until end) value = (value shl 8) or (bytes[index].toLong() and 0xffL)
        return value
    }

    private fun string(
        start: Int,
        end: Int,
    ): String? {
        if (start > end || end - start > MAX_STRING_BYTES) return null
        var stop = end
        // Matroska strings may be zero-padded.
        while (stop > start && bytes[stop - 1] == 0.toByte()) stop--
        return bytes.decodeToString(start, stop).trim()
    }
}

private val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
private const val ID_SEGMENT = 0x18538067L
private const val ID_SEEK_HEAD = 0x114D9B74L
private const val ID_SEEK = 0x4DBBL
private const val ID_SEEK_ID = 0x53ABL
private const val ID_SEEK_POSITION = 0x53ACL
private const val ID_CLUSTER = 0x1F43B675L
private const val ID_CHAPTERS = 0x1043A770L
private const val ID_EDITION_ENTRY = 0x45B9L
private const val ID_EDITION_FLAG_HIDDEN = 0x45BDL
private const val ID_EDITION_FLAG_DEFAULT = 0x45DBL
private const val ID_CHAPTER_ATOM = 0xB6L
private const val ID_CHAPTER_TIME_START = 0x91L
private const val ID_CHAPTER_FLAG_HIDDEN = 0x98L
private const val ID_CHAPTER_FLAG_ENABLED = 0x4598L
private const val ID_CHAPTER_SEGMENT_UID = 0x6E67L
private const val ID_CHAPTER_DISPLAY = 0x80L
private const val ID_CHAP_STRING = 0x85L
private const val ID_CHAP_LANGUAGE = 0x437CL
private const val ID_CHAP_LANGUAGE_IETF = 0x437DL
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val MAX_CHAPTERS = 1_000
private const val MAX_STRING_BYTES = 1_024

private const val MAX_CHAPTER_DEPTH = 32
private const val MAX_CHAPTER_NODES = 5_000
private const val MAX_CHAPTER_ELEMENT_BYTES = 4L * 1024 * 1024
