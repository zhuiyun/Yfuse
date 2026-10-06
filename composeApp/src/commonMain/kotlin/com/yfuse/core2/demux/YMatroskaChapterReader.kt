package com.yfuse.core2.demux

import com.yfuse.core2.api.YChapter

/** Run after first output, on an independent transport. Never scans clusters or downloads a whole file. */
internal suspend fun readYMatroskaChapters(readRange: suspend (Long, Int) -> ByteArray): List<YChapter> {
    for (limit in listOf(128 * 1024, 512 * 1024, 1024 * 1024)) {
        val prefix = readRange(0L, limit)
        when (val result = YMatroskaChapterParser.parse(prefix)) {
            is YMatroskaChaptersResult.Found -> return result.chapters
            is YMatroskaChaptersResult.Elsewhere -> {
                if (result.offset < 0L || result.offset > Long.MAX_VALUE - MAX_CHAPTER_RANGE_BYTES) return emptyList()
                val header = readRange(result.offset, 12)
                val length = YMatroskaChapterParser.chapterElementSize(header) ?: return emptyList()
                val element = if (length <= header.size) header else readRange(result.offset, length)
                return (
                    YMatroskaChapterParser.parseChapterElement(
                        element,
                    ) as? YMatroskaChaptersResult.Found
                )?.chapters.orEmpty()
            }
            YMatroskaChaptersResult.Truncated -> if (prefix.size < limit) return emptyList()
            YMatroskaChaptersResult.Absent, YMatroskaChaptersResult.Invalid -> return emptyList()
        }
    }
    return emptyList()
}

private const val MAX_CHAPTER_RANGE_BYTES = 4 * 1024 * 1024
