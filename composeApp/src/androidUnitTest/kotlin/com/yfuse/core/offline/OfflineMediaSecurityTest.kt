package com.yfuse.core.offline

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.SavedServer
import com.yfuse.core.security.TestSecureStore
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun rawOfflineTransferAllowsHttpAndHttpsWithoutConfirmation() {
        assertEquals(
            "https",
            requireAllowedOfflineTransferUrl(
                "https://media.example/Videos/episode/stream?api_key=secret",
                localCleartextConfirmed = false,
            ).protocol,
        )
        assertEquals(
            "http",
            requireAllowedOfflineTransferUrl(
                "http://media.example/Videos/episode/stream?api_key=secret",
                localCleartextConfirmed = false,
            ).protocol,
        )
        assertEquals(
            "http",
            requireAllowedOfflineTransferUrl(
                "http://192.168.1.20:8096/Videos/episode/stream?api_key=secret",
                localCleartextConfirmed = false,
            ).protocol,
        )
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
    fun reenqueueing_the_same_variant_keeps_its_verified_resume_state() {
        val old =
            OfflineMedia(
                id = "server#episode",
                serverId = "server",
                itemId = "episode",
                title = "Episode",
                mediaSourceId = "source",
                downloadedBytes = 4_096L,
                totalBytes = 8_192L,
                downloadRevision = 5L,
                resumeValidator = "etag:\"same\"",
                status = DownloadStatus.Paused,
            )

        val plan =
            planOfflineEnqueue(
                old = old,
                request = OfflineDownloadRequest("server", "episode", "Episode", "source"),
                nowMs = 200L,
            )

        assertFalse(plan.sourceChanged)
        assertEquals(4_096L, plan.item.downloadedBytes)
        assertEquals(8_192L, plan.item.totalBytes)
        assertEquals("etag:\"same\"", plan.item.resumeValidator)
        assertEquals(DownloadStatus.Queued, plan.item.status)
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

    @Test
    fun an_interrupted_post_finalize_download_keeps_its_verified_video_for_resume() {
        val finalized =
            OfflineMedia(
                id = "server#episode",
                serverId = "server",
                itemId = "episode",
                title = "Episode",
                mediaSourceId = "source",
                localPath = "/offline/episode.4.media",
                downloadedBytes = 123L,
                totalBytes = 123L,
                downloadRevision = 4L,
                status = DownloadStatus.Paused,
            )

        val plan =
            planOfflineEnqueue(
                old = finalized,
                request = OfflineDownloadRequest("server", "episode", "Episode", "source"),
                nowMs = 100L,
            )

        assertFalse(plan.sourceChanged)
        assertEquals("/offline/episode.4.media", plan.item.localPath)
        assertEquals(123L, plan.item.downloadedBytes)
        assertEquals(123L, plan.item.totalBytes)
        assertEquals(DownloadStatus.Queued, plan.item.status)
    }

    @Test
    fun only_known_offline_artifact_suffixes_are_cleaned_without_an_index_entry() {
        assertTrue(isOfflineArtifactName("ab.media"))
        assertTrue(isOfflineArtifactName("ab.part"))
        assertTrue(isOfflineArtifactName("ab.srt"))
        assertTrue(isOfflineArtifactName("ab.4.subtitle.part"))
        assertFalse(isOfflineArtifactName("ab.jpg"))
        assertFalse(isOfflineArtifactName("notes.txt"))
    }

    @Test
    fun startup_cleanup_keeps_the_revision_specific_completed_video() {
        val directory = Files.createTempDirectory("yfuse-offline-startup-").toFile()
        try {
            // "e" is encoded as 65 by the deterministic offline filename scheme.
            val completed = File(directory, "65.4.media").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val staleRevision = File(directory, "65.3.media").apply { writeBytes(byteArrayOf(4)) }
            val unrelated = File(directory, "orphan.1.media").apply { writeBytes(byteArrayOf(5)) }
            val item =
                OfflineMedia(
                    id = "e",
                    serverId = "server",
                    itemId = "episode",
                    title = "Episode",
                    localPath = completed.absolutePath,
                    downloadRevision = 4L,
                    status = DownloadStatus.Completed,
                )

            cleanupOrphanedOfflineArtifacts(directory, listOf(item))

            assertTrue(completed.isFile)
            assertEquals(3L, completed.length())
            assertFalse(staleRevision.exists())
            assertFalse(unrelated.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun startup_cleanup_keeps_the_indexed_completed_path_after_reenqueue_advances_revision() {
        val directory = Files.createTempDirectory("yfuse-offline-revision-").toFile()
        try {
            val retained = File(directory, "65.4.media").apply { writeBytes(byteArrayOf(1)) }
            val item =
                OfflineMedia(
                    id = "e",
                    serverId = "server",
                    itemId = "episode",
                    title = "Episode",
                    localPath = retained.absolutePath,
                    downloadRevision = 5L,
                    status = DownloadStatus.Completed,
                )

            cleanupOrphanedOfflineArtifacts(directory, listOf(item))

            assertTrue(retained.isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun subtitle_content_length_rejects_a_declared_oversized_body() {
        validateOfflineSubtitleContentLength(-1L)
        validateOfflineSubtitleContentLength(MAX_OFFLINE_SUBTITLE_BYTES)

        assertFailsWith<IOException> {
            validateOfflineSubtitleContentLength(MAX_OFFLINE_SUBTITLE_BYTES + 1L)
        }
    }

    @Test
    fun unknown_length_subtitle_is_stopped_when_streamed_bytes_cross_the_limit() {
        val maxBytes = 32L * 1024L
        val output = ByteArrayOutputStream()

        assertFailsWith<IOException> {
            copyOfflineSubtitleBounded(
                input = ByteArrayInputStream(ByteArray(maxBytes.toInt() + 1)),
                output = output,
                maxBytes = maxBytes,
            )
        }

        // The first chunk may be written, but the byte that crosses the cumulative bound is not.
        assertEquals(maxBytes, output.size().toLong())
    }

    @Test
    fun removing_an_item_while_its_subtitle_is_prepared_cannot_publish_an_orphan() {
        val directory = Files.createTempDirectory("yfuse-offline-remove-").toFile()
        try {
            val snapshot = downloadingItem(revision = 4L)
            val video = File(directory, "episode.media").apply { writeBytes(byteArrayOf(1)) }
            val subtitlePart = File(directory, "episode.4.subtitle.part").apply { writeText("old subtitle") }
            val subtitleTarget = File(directory, "episode.srt")

            val completed =
                publishOfflineCompletionLocked(
                    current = null,
                    snapshot = snapshot,
                    videoTarget = video,
                    subtitlePart = subtitlePart,
                    subtitleTarget = subtitleTarget,
                    nowMs = 100L,
                )

            assertNull(completed)
            assertFalse(subtitlePart.exists())
            assertFalse(subtitleTarget.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reenqueue_during_subtitle_download_rejects_the_old_revision() {
        val directory = Files.createTempDirectory("yfuse-offline-reenqueue-").toFile()
        try {
            val snapshot = downloadingItem(revision = 7L)
            val replacement = downloadingItem(revision = 8L)
            val video = File(directory, "episode.media").apply { writeBytes(byteArrayOf(1)) }
            val oldPart = File(directory, "episode.7.subtitle.part").apply { writeText("old subtitle") }
            val subtitleTarget = File(directory, "episode.srt").apply { writeText("replacement subtitle") }

            val completed =
                publishOfflineCompletionLocked(
                    current = replacement,
                    snapshot = snapshot,
                    videoTarget = video,
                    subtitlePart = oldPart,
                    subtitleTarget = subtitleTarget,
                    nowMs = 100L,
                )

            assertNull(completed)
            assertFalse(oldPart.exists())
            assertEquals("replacement subtitle", subtitleTarget.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun completed_is_created_only_after_the_sidecar_is_atomically_visible() {
        val directory = Files.createTempDirectory("yfuse-offline-complete-").toFile()
        try {
            val snapshot = downloadingItem(revision = 11L)
            val video = File(directory, "episode.media").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val subtitlePart = File(directory, "episode.11.subtitle.part").apply { writeText("subtitle") }
            val subtitleTarget = File(directory, "episode.srt")

            val completed =
                publishOfflineCompletionLocked(
                    current = snapshot,
                    snapshot = snapshot,
                    videoTarget = video,
                    subtitlePart = subtitlePart,
                    subtitleTarget = subtitleTarget,
                    nowMs = 200L,
                )

            assertEquals(DownloadStatus.Completed, completed?.status)
            assertEquals(subtitleTarget.absolutePath, completed?.subtitlePath)
            assertTrue(subtitleTarget.isFile)
            assertEquals("subtitle", subtitleTarget.readText())
            assertFalse(subtitlePart.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failed_selected_subtitle_is_an_explicit_playable_degradation() {
        val directory = Files.createTempDirectory("yfuse-offline-subtitle-failed-").toFile()
        try {
            val snapshot = downloadingItem(revision = 12L)
            val video = File(directory, "episode.media").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val staleSubtitle = File(directory, "episode.srt").apply { writeText("stale") }

            val completed =
                publishOfflineCompletionLocked(
                    current = snapshot,
                    snapshot = snapshot,
                    videoTarget = video,
                    subtitlePart = null,
                    subtitleTarget = staleSubtitle,
                    nowMs = 200L,
                )

            assertEquals(DownloadStatus.Completed, completed?.status)
            assertTrue(completed?.playable == true)
            assertNull(completed?.subtitlePath)
            assertEquals("视频已完成，但所选字幕未能保存", completed?.error)
            assertFalse(staleSubtitle.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun downloadingItem(revision: Long) =
        OfflineMedia(
            id = "server#episode",
            serverId = "server",
            itemId = "episode",
            title = "Episode",
            subtitleStreamIndex = 2,
            downloadRevision = revision,
            status = DownloadStatus.Downloading,
        )
}
