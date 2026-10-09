package com.yfuse.core.filesource

import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FileSourceSubtitleCacheTest {
    private val folder: File = Files.createTempDirectory("filesource-subtitle").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun stored_and_cached_uris_round_trip_paths_with_spaces_and_reserved_characters() {
        val directory = File(folder, "字幕 cache #100%")
        val cache = FileSourceSubtitleCache({ directory })
        val key = "0123456789abcdef"
        val bytes = "你好，世界".toByteArray(Charsets.UTF_8)

        val stored = URI(cache.store(key, "srt", bytes))
        val cached = URI(assertNotNull(cache.cached(key, "srt")))

        assertEquals("file", stored.scheme)
        assertNull(stored.rawQuery)
        assertNull(stored.rawFragment)
        assertEquals(File(directory, "filesource-subtitles/$key.srt"), File(stored))
        assertContentEquals(bytes, File(stored).readBytes())
        assertEquals(stored, cached)
    }
}
