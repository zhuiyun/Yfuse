package com.yfuse.core.offline

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineMediaSecurityTest {

    @Test
    fun legacy_authenticated_urls_are_scrubbed_while_source_selection_is_preserved() {
        val legacy = Json.decodeFromString(
            OfflineMedia.serializer(),
            """{
                "id":"server#episode",
                "serverId":"server",
                "itemId":"episode",
                "title":"Episode",
                "sourceUrl":"https://media.example/Videos/episode/stream?api_key=old-secret&MediaSourceId=source%201",
                "posterUrl":"https://media.example/Items/episode/Images/Primary?api_key=old-secret",
                "error":"failed https://media.example/Videos/episode/stream?api_key=old-secret"
            }""".trimIndent(),
        )

        val sanitized = sanitizeLegacyOfflineItem(legacy)
        val persisted = Json.encodeToString(OfflineMedia.serializer(), sanitized)

        assertEquals("source 1", sanitized.mediaSourceId)
        assertNull(sanitized.legacySourceUrl)
        assertNull(sanitized.posterUrl)
        assertFalse(sanitized.error.orEmpty().contains("old-secret"))
        assertFalse(persisted.contains("old-secret"))
        assertTrue(persisted.contains("<redacted>"))
    }

    @Test
    fun download_url_follows_a_server_edit_and_uses_the_rotated_token() {
        val registry = ServerRegistry(MapSettings())
        val oldServer = SavedServer(
            id = SavedServer.idOf("http://old.example", "user"),
            baseUrl = "http://old.example",
            serverName = "Media",
            userId = "user",
            userName = "User",
            accessToken = "old-secret",
        )
        registry.addOrUpdate(oldServer)
        val item = OfflineMedia(
            id = "download",
            serverId = oldServer.id,
            itemId = "episode",
            title = "Episode",
            mediaSourceId = "source 1",
        )
        val editedServer = oldServer.copy(
            id = SavedServer.idOf("https://new.example", "user"),
            baseUrl = "https://new.example",
            accessToken = "fresh-token",
        )

        assertTrue(registry.replace(oldServer.id, editedServer))

        val sourceUrl = resolveOfflineSourceUrl(item, registry)

        assertTrue(sourceUrl.startsWith("https://new.example/"))
        assertTrue(sourceUrl.contains("api_key=fresh-token"))
        assertTrue(sourceUrl.contains("MediaSourceId=source%201"))
        assertFalse(sourceUrl.contains("old-secret"))
    }

    @Test
    fun switching_media_source_resets_files_progress_validator_and_generation() {
        val old = OfflineMedia(
            id = "server#episode",
            serverId = "server",
            itemId = "episode",
            title = "Episode",
            mediaSourceId = "source-a",
            localPath = "/offline/source-a.media",
            downloadedBytes = 100L,
            totalBytes = 100L,
            downloadRevision = 7L,
            resumeValidator = "etag:\"a\"",
            status = DownloadStatus.Completed,
        )

        val plan = planOfflineEnqueue(
            old = old,
            request = OfflineDownloadRequest("server", "episode", "Episode", "source-b"),
            nowMs = 200L,
        )

        assertTrue(plan.sourceChanged)
        assertEquals("source-b", plan.item.mediaSourceId)
        assertEquals(DownloadStatus.Queued, plan.item.status)
        assertNull(plan.item.localPath)
        assertEquals(0L, plan.item.downloadedBytes)
        assertEquals(0L, plan.item.totalBytes)
        assertNull(plan.item.resumeValidator)
        assertEquals(8L, plan.item.downloadRevision)
    }

    @Test
    fun default_source_and_an_explicit_item_source_are_the_same_file() {
        assertTrue(sameOfflineMediaSource("episode", null, "episode"))
        assertFalse(sameOfflineMediaSource("episode", null, "another-source"))
    }

    @Test
    fun range_append_requires_the_expected_offset_and_same_validator() {
        assertTrue(
            canAppendOfflineRange(
                existingBytes = 100L,
                statusCode = 206,
                contentRange = "bytes 100-199/400",
                expectedValidator = "etag:\"v1\"",
                responseValidator = "etag:\"v1\"",
            ),
        )
        assertFalse(
            canAppendOfflineRange(
                existingBytes = 100L,
                statusCode = 206,
                contentRange = "bytes 0-99/400",
                expectedValidator = "etag:\"v1\"",
                responseValidator = "etag:\"v1\"",
            ),
        )
        assertFalse(
            canAppendOfflineRange(
                existingBytes = 100L,
                statusCode = 206,
                contentRange = "bytes 100-199/400",
                expectedValidator = "etag:\"v1\"",
                responseValidator = "etag:\"v2\"",
            ),
        )
        assertFalse(
            canAppendOfflineRange(
                existingBytes = 100L,
                statusCode = 206,
                contentRange = "bytes 100-199/400",
                expectedValidator = null,
                responseValidator = null,
            ),
        )
    }

    @Test
    fun storage_check_keeps_a_reserve_and_handles_unknown_content_length() {
        val reserve = 100L

        assertTrue(hasSufficientOfflineStorage(usableSpace = 150L, requiredBytes = 50L, reserveBytes = reserve))
        assertFalse(hasSufficientOfflineStorage(usableSpace = 149L, requiredBytes = 50L, reserveBytes = reserve))
        assertTrue(hasSufficientOfflineStorage(usableSpace = 100L, requiredBytes = 0L, reserveBytes = reserve))
        assertFalse(hasSufficientOfflineStorage(usableSpace = 99L, requiredBytes = 0L, reserveBytes = reserve))
        // An unknown-length stream reserves its next check interval up front.
        assertTrue(hasSufficientOfflineStorage(usableSpace = 108L, requiredBytes = 8L, reserveBytes = reserve))
        assertFalse(hasSufficientOfflineStorage(usableSpace = 107L, requiredBytes = 8L, reserveBytes = reserve))
        assertFalse(hasSufficientOfflineStorage(Long.MAX_VALUE, Long.MAX_VALUE, reserveBytes = reserve))
        assertEquals(1L, missingOfflineStorageBytes(usableSpace = 149L, requiredBytes = 50L, reserveBytes = reserve))
        assertEquals(1L, missingOfflineStorageBytes(usableSpace = 99L, requiredBytes = 0L, reserveBytes = reserve))
        assertEquals(
            Long.MAX_VALUE,
            missingOfflineStorageBytes(Long.MAX_VALUE - 100L, Long.MAX_VALUE, reserveBytes = reserve),
        )
    }
}
