package com.yfuse.core.offline

import com.yfuse.core.model.AudioTrackInfo
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.SubtitleTrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflineBatchSelectionTest {
    @Test
    fun auto_download_selects_only_new_unwatched_episodes_within_remaining_capacity() {
        val episodes =
            listOf(
                episode("episode-1", 1, emptyList()).copy(played = true),
                episode("episode-2", 2, emptyList()),
                episode("episode-3", 3, emptyList()),
                episode("episode-4", 4, emptyList()),
            )

        assertEquals(
            listOf("episode-3", "episode-4"),
            selectNewAutoDownloadEpisodes(
                episodes = episodes,
                knownEpisodeIds = setOf("episode-1", "episode-2"),
                existingItemIds = setOf("episode-2"),
                itemLimit = 3,
            ).map(Episode::id),
        )
    }

    @Test
    fun offline_playback_uri_preserves_saf_content_uri() {
        assertEquals("content://downloads/tree/video", offlinePlaybackUri("content://downloads/tree/video"))
        assertEquals("file:///data/user/0/video.media", offlinePlaybackUri("/data/user/0/video.media"))
    }

    @Test
    fun season_maps_version_and_subtitle_features_across_heterogeneous_ids_and_indices() {
        val current =
            episode(
                id = "episode-1",
                index = 1,
                versions =
                    listOf(
                        version("episode-1-remux", "Remux", "mkv", 2160, 50_000_000, 9_000L),
                        version(
                            id = "episode-1-web",
                            name = "WEB-DL",
                            container = "mp4",
                            height = 1080,
                            bitrate = 8_000_000,
                            size = 1_000L,
                            subtitles = listOf(subtitle(4, default = true)),
                        ),
                    ),
            )
        val second =
            episode(
                id = "episode-2",
                index = 2,
                versions =
                    listOf(
                        version("episode-2-remux", "Remux", "mkv", 2160, 52_000_000, 12_000L),
                        version(
                            id = "different-source-id",
                            name = "WEB-DL",
                            container = "mp4",
                            height = 1080,
                            bitrate = 8_200_000,
                            size = 2_000L,
                            subtitles =
                                listOf(
                                    subtitle(4, default = false),
                                    subtitle(17, default = true),
                                ),
                        ),
                    ),
            )
        val third =
            episode(
                id = "episode-3",
                index = 3,
                versions =
                    listOf(
                        version(
                            id = "source-order-is-unrelated",
                            name = "WEB-DL",
                            container = "mp4",
                            height = 1080,
                            bitrate = 7_900_000,
                            size = 3_000L,
                            subtitles = listOf(subtitle(23, default = true)),
                        ),
                        version("episode-3-remux", "Remux", "mkv", 2160, 49_000_000, 14_000L),
                    ),
            )
        val selection =
            OfflineDownloadSelection(
                batchMode = OfflineBatchMode.Season,
                mediaSourceId = "episode-1-web",
                quality = OfflineDownloadQuality.Original,
                subtitleStreamIndex = 4,
                subtitleCodec = "ass",
                subtitleLanguage = "中文",
                subtitleDefault = true,
                subtitleForced = false,
            )

        val requests =
            buildOfflineDownloadRequests(
                serverId = "server",
                currentItemId = current.id,
                currentTitle = current.name,
                currentRuntimeMinutes = current.runtimeMinutes,
                currentVersions = current.versions,
                seasonEpisodes = listOf(current, second, third),
                selection = selection,
                currentSeriesId = "series-1",
                currentSeasonId = "season-1",
            )

        assertEquals(
            listOf("episode-1-web", "different-source-id", "source-order-is-unrelated"),
            requests.map { it.mediaSourceId },
        )
        assertEquals(listOf(4, 17, 23), requests.map { it.subtitleStreamIndex })
        assertEquals(setOf("episode-1", "episode-2", "episode-3"), requests.first().knownEpisodeIds)
        assertEquals(List(3) { "series-1" }, requests.map { it.seriesId })
        assertEquals(listOf(1_000L, 2_000L, 3_000L).map { it + SUBTITLE_ESTIMATE }, requests.map { it.estimatedBytes })
        assertEquals(
            6_000L + 3L * SUBTITLE_ESTIMATE,
            estimateOfflineDownloadBytes(
                currentItemId = current.id,
                currentTitle = current.name,
                currentRuntimeMinutes = current.runtimeMinutes,
                currentVersions = current.versions,
                seasonEpisodes = listOf(current, second, third),
                selection = selection,
            ),
        )
    }

    @Test
    fun sibling_with_different_forced_semantics_does_not_reuse_index() {
        val currentVersion =
            version(
                id = "current-source",
                name = "WEB-DL",
                container = "mkv",
                height = 1080,
                bitrate = 8_000_000,
                size = 1_000L,
                subtitles = listOf(subtitle(7, default = false, forced = true)),
            )
        val siblingVersion =
            version(
                id = "sibling-source",
                name = "WEB-DL",
                container = "mkv",
                height = 1080,
                bitrate = 8_000_000,
                size = 2_000L,
                subtitles = listOf(subtitle(7, default = false, forced = false)),
            )
        val current = episode("episode-1", 1, listOf(currentVersion))
        val sibling = episode("episode-2", 2, listOf(siblingVersion))

        val requests =
            buildOfflineDownloadRequests(
                serverId = "server",
                currentItemId = current.id,
                currentTitle = current.name,
                currentRuntimeMinutes = current.runtimeMinutes,
                currentVersions = current.versions,
                seasonEpisodes = listOf(current, sibling),
                selection =
                    OfflineDownloadSelection(
                        batchMode = OfflineBatchMode.Season,
                        mediaSourceId = currentVersion.id,
                        subtitleStreamIndex = 7,
                        subtitleCodec = "ass",
                        subtitleLanguage = "中文",
                        subtitleDefault = false,
                        subtitleForced = true,
                    ),
            )

        assertEquals(7, requests.first().subtitleStreamIndex)
        assertNull(requests.last().subtitleStreamIndex)
    }

    @Test
    fun subtitle_language_and_forced_are_identity_while_codec_and_default_are_preferences() {
        val tracks =
            listOf(
                SubtitleTrackInfo(9, "srt", "中文", forced = false, default = false),
                SubtitleTrackInfo(10, "ass", "中文", forced = false, default = false),
                SubtitleTrackInfo(11, "ass", "英语", forced = false, default = true),
                SubtitleTrackInfo(12, "ass", "中文", forced = true, default = true),
            )

        assertEquals(
            10,
            matchOfflineSubtitleTrack(tracks, "中文", "ass", default = true, forced = false)?.index,
        )
        assertNull(matchOfflineSubtitleTrack(tracks, null, "ass", default = true, forced = false))
    }

    @Test
    fun version_matching_keeps_the_selected_audio_variant_when_sibling_order_changes() {
        val reference =
            version("current-cn", "WEB-DL 国语", "mkv", 1080, 8_000_000, 1_000L)
                .copy(audioTracks = listOf(audio("中文", "aac", default = true)))
        val original =
            version("sibling-original", "WEB-DL 原声", "mkv", 1080, 8_000_000, 1_000L)
                .copy(audioTracks = listOf(audio("日语", "aac", default = true)))
        val chinese =
            version("sibling-cn", "WEB-DL 国语版", "mkv", 1080, 8_000_000, 1_000L)
                .copy(audioTracks = listOf(audio("中文", "aac", default = true)))

        assertEquals("sibling-cn", matchOfflineMediaVersion(reference, listOf(original, chinese))?.id)
        assertEquals("sibling-cn", matchOfflineMediaVersion(reference, listOf(chinese, original))?.id)
    }

    @Test
    fun sibling_without_a_compatible_version_does_not_silently_download_an_unrelated_file() {
        val reference =
            version(
                id = "current-web",
                name = "WEB-DL",
                container = "mp4",
                height = 1080,
                bitrate = 8_000_000,
                size = 1_000L,
            )
        val unrelated =
            MediaVersion(
                id = "sibling-audio-only",
                name = "Audio commentary",
                container = "m4a",
                sizeBytes = 500L,
                bitrateBps = 192_000,
                videoCodec = "aac",
                videoHeight = null,
                videoRange = "unknown",
            )
        val current = episode("episode-1", 1, listOf(reference))
        val sibling = episode("episode-2", 2, listOf(unrelated))

        val requests =
            buildOfflineDownloadRequests(
                serverId = "server",
                currentItemId = current.id,
                currentTitle = current.name,
                currentRuntimeMinutes = current.runtimeMinutes,
                currentVersions = current.versions,
                seasonEpisodes = listOf(current, sibling),
                selection =
                    OfflineDownloadSelection(
                        batchMode = OfflineBatchMode.Season,
                        mediaSourceId = reference.id,
                    ),
            )

        assertEquals(listOf(current.id), requests.map { it.itemId })
    }

    private fun episode(
        id: String,
        index: Int,
        versions: List<MediaVersion>,
    ) = Episode(
        id = id,
        name = "Episode $index",
        indexNumber = index,
        seasonNumber = 1,
        seasonId = "season-1",
        overview = null,
        runtimeMinutes = 24,
        primaryTag = null,
        playedPercentage = null,
        resumePositionTicks = null,
        versions = versions,
    )

    private fun version(
        id: String,
        name: String,
        container: String,
        height: Int,
        bitrate: Int,
        size: Long,
        subtitles: List<SubtitleTrackInfo> = emptyList(),
    ) = MediaVersion(
        id = id,
        name = name,
        container = container,
        sizeBytes = size,
        bitrateBps = bitrate,
        videoCodec = "h264",
        videoHeight = height,
        videoRange = "SDR",
        subtitleTracks = subtitles,
    )

    private fun subtitle(
        index: Int,
        default: Boolean,
        forced: Boolean = false,
    ) = SubtitleTrackInfo(
        index = index,
        codec = "ass",
        language = "中文",
        forced = forced,
        default = default,
    )

    private fun audio(
        language: String,
        codec: String,
        default: Boolean,
    ) = AudioTrackInfo(
        codec = codec,
        channels = "2.0",
        language = language,
        channelCount = 2,
        default = default,
    )

    private companion object {
        const val SUBTITLE_ESTIMATE = 2L * 1024L * 1024L
    }
}
