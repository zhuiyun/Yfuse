package com.yfuse.feature.filesource

import com.yfuse.core.data.CrossServerMediaHit
import com.yfuse.core.data.aggregateCrossServerMedia
import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.FileSourceLibrary
import com.yfuse.core.filesource.FileSourceLibraryFile
import com.yfuse.core.filesource.FileSourceProgress
import com.yfuse.core.filesource.FileSourceScanProgress
import com.yfuse.core.filesource.FileSourceTitle
import com.yfuse.core.filesource.fileSourceItemId
import com.yfuse.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSourceLibraryShelfTest {
    private val nas = FileSource("fs" + "1".repeat(24), FileSourceKind.WebDav, "NAS", "https://nas.local")
    private val matrix = FileSourceTitle(603, "movie", "黑客帝国", year = 1999, posterPath = "/m.jpg")
    private val show = FileSourceTitle(215803, "tv", "狂飙", year = 2023)
    private val library =
        FileSourceLibrary(
            scannedAtEpochMs = 1L,
            titles = listOf(matrix, show),
            files =
                listOf(
                    FileSourceLibraryFile(listOf("电影", "The.Matrix.1999.mkv"), matrix.key),
                    FileSourceLibraryFile(listOf("剧集", "狂飙", "第02集.mp4"), show.key, episode = 2),
                    FileSourceLibraryFile(listOf("剧集", "狂飙", "第01集.mp4"), show.key, episode = 1),
                ),
            unmatchedCount = 3,
        )

    @Test
    fun a_share_and_a_server_holding_the_same_film_are_one_card() {
        val shareHits =
            fileSourceLibraryHits(
                listOf(nas),
                mapOf(nas.id to library),
                emptyMap(),
                itemType = null,
                unplayedOnly = false,
            )
        val serverCopy =
            CrossServerMediaHit(
                "server-a",
                "客厅 Emby",
                MediaItem(
                    "e1",
                    "The Matrix",
                    null,
                    "Movie",
                    "e1",
                    null,
                    null,
                    null,
                    null,
                    providerIds =
                        mapOf(
                            "Tmdb" to "603",
                        ),
                ),
            )

        val groups = aggregateCrossServerMedia(shareHits + serverCopy)

        val film = groups.single { group -> group.copies.any { it.item.id == "e1" } }
        assertEquals(setOf("客厅 Emby", "NAS"), film.copies.map { it.serverName }.toSet())
        assertEquals(nas.id, film.copies.single { it.serverName == "NAS" }.fileSourceId)
        assertNull(serverCopy.fileSourceId)
    }

    @Test
    fun the_filters_of_all_servers_apply_to_shares_too() {
        val watched = FileSourceProgress(positionMs = 0L, durationMs = 7_000_000L, watched = true)
        val progress = mapOf(fileSourceItemId(nas.id, listOf("电影", "The.Matrix.1999.mkv")) to watched)

        val series =
            fileSourceLibraryHits(
                listOf(nas),
                mapOf(nas.id to library),
                progress,
                itemType = "Series",
                unplayedOnly = false,
            )
        val unplayed =
            fileSourceLibraryHits(listOf(nas), mapOf(nas.id to library), progress, itemType = null, unplayedOnly = true)
        val removedShare =
            fileSourceLibraryHits(
                emptyList(),
                mapOf(nas.id to library),
                progress,
                itemType = null,
                unplayedOnly = false,
            )

        assertEquals(listOf("tv:215803"), series.map { it.item.id })
        assertEquals(listOf("tv:215803"), unplayed.map { it.item.id })
        assertTrue(removedShare.isEmpty(), "a library outlives nothing: no share, no titles")
    }

    @Test
    fun a_titles_files_come_in_watching_order() {
        assertEquals(listOf(1, 2), library.filesOf(show.key).map { it.episode })
    }

    @Test
    fun the_card_and_the_notice_say_what_a_scan_is_doing_and_found() {
        assertEquals("正在读取文件夹 · 已找到 12 个视频", libraryStatus(FileSourceScanProgress(videosFound = 12), null))
        assertEquals(
            "正在识别 · 3/40",
            libraryStatus(FileSourceScanProgress(videosFound = 90, titlesToMatch = 40, titlesLookedUp = 3), library),
        )
        assertEquals("片库 2 部 · 3 个未识别", libraryStatus(null, library))
        assertNull(libraryStatus(null, null))
        assertEquals("「NAS」已加入片库：2 部影片与剧集，3 个视频未能识别", library.summary("NAS"))
        assertEquals(
            "「NAS」里没有识别出影片或剧集，其余内容下次刮削时补全",
            FileSourceLibrary(scannedAtEpochMs = 1L, partial = true).summary("NAS"),
        )
    }
}
