package com.yfuse.core.offline

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.SavedServer
import com.yfuse.core.security.TestSecureStore
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineMediaSecurityTest {
    @Test
    fun legacy_authenticated_urls_are_scrubbed_while_source_selection_is_preserved() {
        val legacy =
            Json.decodeFromString(
                OfflineMedia.serializer(),
                """
                {
                    "id":"server#episode",
                    "serverId":"server",
                    "itemId":"episode",
                    "title":"Episode",
                    "sourceUrl":"https://media.example/Videos/episode/stream?api_key=old-secret&MediaSourceId=source%201",
                    "posterUrl":"https://media.example/Items/episode/Images/Primary?api_key=old-secret",
                    "error":"failed https://media.example/Videos/episode/stream?api_key=old-secret"
                }
                """.trimIndent(),
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
        val registry = ServerRegistry(MapSettings(), TestSecureStore())
        val oldServer =
            SavedServer(
                id = SavedServer.idOf("https://old.example", "user"),
                baseUrl = "https://old.example",
                serverName = "Media",
                userId = "user",
                userName = "User",
                accessToken = "old-secret",
            )
        registry.addOrUpdate(oldServer)
        val item =
            OfflineMedia(
                id = "download",
                serverId = oldServer.id,
                itemId = "episode",
                title = "Episode",
                mediaSourceId = "source 1",
            )
        val editedServer =
            oldServer.copy(
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
        val old =
            OfflineMedia(
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

        val plan =
            planOfflineEnqueue(
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
    fun changing_quality_or_subtitle_is_a_new_download_variant() {
        assertFalse(
            sameOfflineDownloadVariant(
                "episode",
                "source",
                "source",
                OfflineDownloadQuality.Original,
                OfflineDownloadQuality.FullHd,
                null,
                null,
            ),
        )
        assertFalse(
            sameOfflineDownloadVariant(
                "episode",
                "source",
                "source",
                OfflineDownloadQuality.Original,
                OfflineDownloadQuality.Original,
                2,
                3,
            ),
        )
        assertTrue(
            sameOfflineDownloadVariant(
                "episode",
                null,
                "episode",
                OfflineDownloadQuality.Original,
                OfflineDownloadQuality.Original,
                null,
                null,
            ),
        )
    }

    @Test
    fun size_estimate_uses_exact_original_and_bitrate_for_quality_caps() {
        assertEquals(
            1_002_097_152L,
            estimateOfflineBytes(
                sourceSizeBytes = 1_000_000_000L,
                sourceBitrateBps = 20_000_000,
                runtimeMinutes = 90,
                quality = OfflineDownloadQuality.Original,
                includeSubtitle = true,
            ),
        )
        assertEquals(
            5_529_600_000L,
            estimateOfflineBytes(
                sourceSizeBytes = 20_000_000_000L,
                sourceBitrateBps = 30_000_000,
                runtimeMinutes = 90,
                quality = OfflineDownloadQuality.FullHd,
            ),
        )
        assertNull(estimateOfflineBytes(null, null, null, OfflineDownloadQuality.Hd))
    }

    @Test
    fun batch_filter_preserves_order_and_skips_watched_episodes() {
        val episodes =
            listOf(
                OfflineBatchItem("e1", played = true),
                OfflineBatchItem("e2", played = false),
                OfflineBatchItem("e2", played = false),
                OfflineBatchItem("e3", played = false),
            )

        assertEquals(listOf("current"), selectOfflineBatchItems(OfflineBatchMode.Current, "current", episodes))
        assertEquals(listOf("e1", "e2", "e3"), selectOfflineBatchItems(OfflineBatchMode.Season, "current", episodes))
        assertEquals(listOf("e2", "e3"), selectOfflineBatchItems(OfflineBatchMode.Unwatched, "current", episodes))
    }

    @Test
    fun persisted_policy_clamps_concurrency() {
        assertEquals(1, OfflineDownloadPolicy(maxConcurrentDownloads = 0).normalized().maxConcurrentDownloads)
        assertEquals(3, OfflineDownloadPolicy(maxConcurrentDownloads = 20).normalized().maxConcurrentDownloads)
        assertTrue(OfflineDownloadPolicy(autoDeleteWatched = true).autoDeleteWatched)

        val settings = MapSettings()
        persistOfflineDownloadPolicy(
            settings,
            OfflineDownloadPolicy(
                wifiOnly = false,
                maxConcurrentDownloads = 2,
                autoDeleteWatched = true,
            ),
        )
        assertEquals(
            OfflineDownloadPolicy(false, 2, true),
            loadOfflineDownloadPolicy(settings),
        )
    }

    @Test
    fun pending_selector_honors_retry_time_and_concurrency() {
        val items =
            listOf(
                OfflineMedia("a", "s", "a", "A", status = DownloadStatus.Queued),
                OfflineMedia("b", "s", "b", "B", status = DownloadStatus.WaitingForWifi),
                OfflineMedia("c", "s", "c", "C", status = DownloadStatus.Queued, nextRetryAt = 101L),
                OfflineMedia("d", "s", "d", "D", status = DownloadStatus.Paused),
            )

        assertEquals(
            listOf("a", "b"),
            selectPendingOfflineDownloads(items, nowMs = 100L, maxConcurrentDownloads = 2).map { it.id },
        )
        assertEquals(
            listOf("a"),
            selectPendingOfflineDownloads(items, nowMs = 100L, maxConcurrentDownloads = 0).map { it.id },
        )
    }

    @Test
    fun request_builder_uses_each_episode_source_and_never_reuses_current_subtitle_index() {
        fun episode(
            id: String,
            sourceId: String,
            played: Boolean,
        ) = Episode(
            id = id,
            name = id,
            indexNumber = if (id == "e1") 1 else 2,
            seasonNumber = 1,
            seasonId = "season",
            overview = null,
            runtimeMinutes = 24,
            primaryTag = null,
            playedPercentage = null,
            played = played,
            resumePositionTicks = null,
            versions =
                listOf(
                    MediaVersion(
                        id = sourceId,
                        name = sourceId,
                        container = "mkv",
                        sizeBytes = 1_000L,
                        bitrateBps = 1_000_000,
                        videoCodec = "h264",
                        videoHeight = 1080,
                        videoRange = null,
                    ),
                ),
        )
        val requests =
            buildOfflineDownloadRequests(
                serverId = "server",
                currentItemId = "e1",
                currentTitle = "Episode 1",
                currentRuntimeMinutes = 24,
                currentVersions = episode("e1", "source-1", false).versions,
                seasonEpisodes =
                    listOf(
                        episode("e1", "source-1", false),
                        episode("e2", "source-2", false),
                    ),
                selection =
                    OfflineDownloadSelection(
                        batchMode = OfflineBatchMode.Season,
                        mediaSourceId = "source-1",
                        subtitleStreamIndex = 7,
                    ),
            )

        assertEquals(listOf("source-1", "source-2"), requests.map { it.mediaSourceId })
        assertEquals(listOf(7, null), requests.map { it.subtitleStreamIndex })
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
