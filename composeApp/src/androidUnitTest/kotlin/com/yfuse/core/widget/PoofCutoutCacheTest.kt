package com.yfuse.core.widget

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PoofCutoutCacheTest {
    private val quality = WidgetCutoutQuality(.1f, .9f, .2f, .8f, .4f)

    @Test
    fun committed_pair_survives_restart_and_two_urls_share_the_same_files() {
        val directory = Files.createTempDirectory("poof-cache").toFile()
        try {
            val cache = PoofCutoutCache(directory, imageBoundsValid = { _, _, _ -> true })
            val source = poofHash("url1".toByteArray())
            val content = poofHash("pixels".toByteArray())
            val first =
                assertNotNull(
                    cache.write(
                        source,
                        content,
                        "original".toByteArray(),
                        "foreground".toByteArray(),
                        320,
                        480,
                        quality,
                    ),
                )
            val secondSource = poofHash("url2".toByteArray())
            cache.bindSource(secondSource, content)
            val stamp = first.foreground.lastModified()
            val reopened = PoofCutoutCache(directory, imageBoundsValid = { _, _, _ -> true })
            assertEquals(first, reopened.findSource(source))
            assertEquals(first, reopened.findSource(secondSource))
            assertEquals(stamp, first.foreground.lastModified())
            assertEquals(1, directory.listFiles().orEmpty().count { it.name.endsWith("foreground.png") })
            first.foreground.writeText("corruption")
            assertNull(reopened.findSource(source))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun partial_and_wrong_size_pairs_never_become_cache_hits() {
        val directory = Files.createTempDirectory("poof-cache").toFile()
        try {
            val cache = PoofCutoutCache(directory, imageBoundsValid = { _, _, _ -> false })
            val source = poofHash("url".toByteArray())
            val content = poofHash("pixels".toByteArray())
            assertNull(cache.write(source, content, byteArrayOf(1), byteArrayOf(2), 320, 480, quality))
            assertNull(cache.findSource(source))
            directory.resolve("$content.foreground.png.part").writeText("interrupted")
            directory.resolve("$content.meta").delete()
            cache.trim()
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".part") || it.name.endsWith(".png") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failure_expiry_is_fixed_and_authentication_failure_is_key_scoped() {
        val directory = Files.createTempDirectory("poof-cache").toFile()
        try {
            var now = 1_000L
            val cache = PoofCutoutCache(directory, now = { now }, imageBoundsValid = { _, _, _ -> true })
            val source = poofHash("url".toByteArray())
            cache.reject(source, 10_000, "key-a")
            assertTrue(cache.isRejected(source, "key-a"))
            now += 9_000
            assertTrue(cache.isRejected(source, "key-a"))
            now += 1_001
            assertFalse(cache.isRejected(source, "key-a"))
            cache.reject(source, 10_000, "key-a")
            assertFalse(cache.isRejected(source, "key-b"))
            cache.reject(source, POOF_QUALITY_TTL)
            assertTrue(cache.isRejected(source, "key-a"))
            assertTrue(cache.isRejected(source, "key-b"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun least_recently_used_pairs_are_evicted_under_the_disk_budget() {
        val directory = Files.createTempDirectory("poof-cache").toFile()
        try {
            var now = System.currentTimeMillis()
            val cache =
                PoofCutoutCache(directory, now = { now }, budget = 1_400, imageBoundsValid = {
                        _,
                        _,
                        _,
                    ->
                    true
                })
            val old = poofHash("old".toByteArray())
            val recent = poofHash("recent".toByteArray())
            assertNotNull(cache.write(old, old, ByteArray(350), ByteArray(350), 320, 480, quality))
            now += 2_000
            assertNotNull(cache.write(recent, recent, ByteArray(350), ByteArray(350), 320, 480, quality))
            assertNull(cache.findSource(old))
            assertNotNull(cache.findSource(recent))
            assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 1_400)
        } finally {
            directory.deleteRecursively()
        }
    }
}
