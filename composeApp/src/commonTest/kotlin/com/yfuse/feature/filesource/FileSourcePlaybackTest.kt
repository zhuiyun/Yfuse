package com.yfuse.feature.filesource

import com.russhwolf.settings.MapSettings
import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FileSourceEntry
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.FileSourceProgress
import com.yfuse.core.filesource.FileSourceProgressRecorder
import com.yfuse.core.filesource.FileSourceProgressStore
import com.yfuse.core.filesource.planFileSourceQueue
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.feature.player.PlaybackState
import com.yfuse.feature.player.PlayerMediaItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileSourcePlaybackTest {
    private val source =
        FileSource(
            id = "fs0123456789abcdef01234567",
            kind = FileSourceKind.Alist,
            name = "Alist",
            origin = "http://192.168.1.2:5244",
            rootSegments = listOf("dav"),
            username = "alice",
        )
    private val credentials = FileSourceCredentials("alice", "pw")
    private val folder =
        listOf(
            FileSourceEntry("E01.mkv", directory = false, sizeBytes = 2L * 1024 * 1024 * 1024),
            FileSourceEntry("E01.chs.ass", directory = false),
            FileSourceEntry("E01.eng.srt", directory = false),
            FileSourceEntry("E02.mkv", directory = false),
        )

    @Test
    fun items_are_serverless_carry_the_login_in_memory_and_only_the_sidecars_that_arrived() {
        val plan = planFileSourceQueue(source.id, listOf("剧集"), folder, folder[0])
        val sidecars = mapOf((0 to "E01.chs.ass") to "file:///cache/a.ass")

        val items = fileSourcePlaybackItems(source, credentials, plan, sidecars, emptyMap())

        val first = items.first()
        assertTrue(first.isFileSourcePlayback)
        assertNull(first.serverId)
        assertEquals("http://192.168.1.2:5244/dav/%E5%89%A7%E9%9B%86/E01.mkv", first.url)
        assertEquals("E01", first.title)
        assertEquals("", first.transcodeUrl)
        assertEquals(PlaybackMethod.DirectPlay, first.playMethod)
        assertEquals("alice", (first.transportCredentials as YTransportCredentials.UsernamePassword).username)
        assertEquals(listOf("file:///cache/a.ass"), first.externalSubtitles.map { it.uri })
        assertEquals("简体中文", first.externalSubtitles.single().language)
        assertTrue(first.externalSubtitles.single().default)
        assertEquals("mkv", first.activeVersion?.container)
        assertEquals("MKV · 2.0 GB", first.activeVersion?.detail)
        assertEquals(first.externalSubtitles, first.activeVersion?.externalSubtitles)
        assertTrue(items[1].externalSubtitles.isEmpty())
    }

    @Test
    fun a_launch_resumes_the_tapped_file_and_survives_a_failed_sidecar() =
        runTest {
            val plan = planFileSourceQueue(source.id, emptyList(), folder, folder[0])
            val client = RecordingClient(failing = setOf("E01.eng.srt"))
            val resume =
                mapOf(
                    plan.entries[0].itemId to FileSourceProgress(positionMs = 600_000L, durationMs = 3_000_000L),
                )

            val launch = prepareFileSourceLaunch(source, credentials, plan, client, resume)

            assertEquals(listOf("E01.chs.ass", "E01.eng.srt"), client.requested)
            assertEquals(0, launch.startIndex)
            assertEquals(600_000L, launch.startPositionMs)
            assertEquals(listOf("cache://E01.chs.ass"), launch.items[0].externalSubtitles.map { it.uri })
            assertEquals(0.2f, launch.items[0].progress)
        }

    @Test
    fun progress_is_recorded_only_for_file_items_and_only_once_playing() {
        val settings = MapSettings()
        val store = FileSourceProgressStore(settings)
        val progress = FileSourcePlaybackProgress(FileSourceProgressRecorder(store) { 0L })
        val plan = planFileSourceQueue(source.id, emptyList(), folder, folder[0])
        val item = fileSourcePlaybackItems(source, credentials, plan, emptyMap(), emptyMap()).first()
        val serverItem =
            PlayerMediaItem(id = "emby-1", url = "https://emby/x", transcodeUrl = "", title = "x", serverId = "s")

        progress.onProgress(item, PlaybackState(playing = false, buffering = true, positionMs = 0L))
        assertNull(store.get(item.id))

        progress.onProgress(item, PlaybackState(playing = true, positionMs = 120_000L, durationMs = 1_000_000L))
        assertEquals(120_000L, store.get(item.id)?.positionMs)

        progress.onProgress(serverItem, PlaybackState(playing = true, positionMs = 50_000L, durationMs = 1_000_000L))
        assertNull(store.get(serverItem.id))

        progress.onState(
            item,
            PlaybackState(playing = false, ended = true, positionMs = 1_000_000L, durationMs = 1_000_000L),
        )
        assertEquals(true, store.get(item.id)?.watched)
        assertFalse(
            store.progress.value.keys
                .any { it == "emby-1" },
        )
    }

    @Test
    fun sizes_read_like_the_rest_of_the_app() {
        assertEquals("1 MB", formatFileSize(10L))
        assertEquals("734 MB", formatFileSize(734L * 1024 * 1024))
        // Tenths are cut rather than rounded, as a version's 42.3 GB is on a server item.
        assertEquals("58.3 GB", formatFileSize(58L * 1024 * 1024 * 1024 + 390L * 1024 * 1024))
    }

    private class RecordingClient(
        private val failing: Set<String>,
    ) : FileSourceClient {
        val requested = mutableListOf<String>()

        override suspend fun list(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
        ): List<FileSourceEntry> = error("not listed in these tests")

        override suspend fun cacheSubtitle(
            source: FileSource,
            credentials: FileSourceCredentials,
            path: List<String>,
            entry: FileSourceEntry,
        ): String {
            requested += entry.name
            if (entry.name in failing) error("sidecar unavailable")
            return "cache://${entry.name}"
        }
    }
}
