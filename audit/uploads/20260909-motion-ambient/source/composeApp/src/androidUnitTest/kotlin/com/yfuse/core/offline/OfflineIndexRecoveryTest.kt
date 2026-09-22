package com.yfuse.core.offline

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineIndexRecoveryTest {
    @Test
    fun unreadable_index_never_runs_real_orphan_cleanup_or_publishes_an_empty_index() {
        val directory = Files.createTempDirectory("offline-recovery").toFile()
        try {
            val video = File(directory, "saved.1.media").apply { writeText("offline movie") }
            var cleanupCalled = false
            var persistCalled = false
            assertFailsWith<IOException> {
                recoverOfflineIndex(
                    load = { throw IOException("database unavailable") },
                    cleanup = {
                        cleanupCalled = true
                        cleanupOrphanedOfflineArtifacts(directory, it)
                    },
                    recover = { it },
                    persist = { _, _ -> persistCalled = true },
                )
            }
            assertFalse(cleanupCalled)
            assertFalse(persistCalled)
            assertTrue(video.isFile)
            assertEquals("offline movie", video.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recovery_is_not_published_when_its_durable_write_fails() {
        val stored = listOf(OfflineMedia("s#e", "s", "e", "Episode", status = DownloadStatus.Downloading))
        var published = stored
        assertFailsWith<IOException> {
            published =
                recoverOfflineIndex(
                    load = { stored },
                    cleanup = {},
                    recover = { rows -> rows.map { it.copy(status = DownloadStatus.Queued) } },
                    persist = { previous, next ->
                        assertEquals(stored, previous)
                        assertEquals(DownloadStatus.Queued, next.single().status)
                        throw IOException("disk full")
                    },
                )
        }
        assertEquals(stored, published)
    }
}
