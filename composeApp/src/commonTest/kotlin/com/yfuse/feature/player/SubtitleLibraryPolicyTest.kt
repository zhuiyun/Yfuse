package com.yfuse.feature.player

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SubtitleLibraryPolicyTest {
    @Test
    fun unsupported_server_is_distinct_from_an_empty_subtitle_search() {
        assertNull(subtitleSearchUnavailableReason(MediaServerKind.Emby))
        assertNull(subtitleSearchUnavailableReason(MediaServerKind.Jellyfin))
        assertNotNull(subtitleSearchUnavailableReason(MediaServerKind.Plex))
        assertNotNull(subtitleSearchUnavailableReason(null))
    }

    @Test
    fun a_failed_search_says_why_instead_of_showing_the_exception() {
        val searching = RemoteSubtitlePanelState(loading = true)
        val chinese = SubtitleSearchLanguage.Chinese

        val offline = searching.withSearchResult(Result.failure(EmbyErrorException(EmbyError.Network)), chinese)
        val garbled = searching.withSearchResult(Result.failure(IllegalStateException("Expected BEGIN_ARRAY")), chinese)

        assertFalse(offline.loading)
        assertEquals("无法连接服务器，请检查网络后重试", offline.message)
        assertEquals("字幕搜索失败，请重试。", garbled.message)
    }

    @Test
    fun local_import_is_scoped_to_the_exact_server_item_and_version() {
        val item =
            PlayerMediaItem(
                id = "1",
                url = "https://media.invalid/video",
                transcodeUrl = "",
                title = "Title",
                serverId = "one",
                versionId = "cut-a",
            )
        val sidecar = PlayerExternalSubtitle("content://documents/subtitle", codec = "srt")
        val imports = mapOf(item.subtitleItemKey() to listOf(sidecar))
        assertEquals(listOf(sidecar), item.withImportedSubtitles(imports).externalSubtitles)
        assertEquals(emptyList(), item.copy(serverId = "two").withImportedSubtitles(imports).externalSubtitles)
        assertEquals(emptyList(), item.copy(id = "2").withImportedSubtitles(imports).externalSubtitles)
        assertEquals(emptyList(), item.copy(versionId = "cut-b").withImportedSubtitles(imports).externalSubtitles)
    }

    @Test
    fun importing_the_same_uri_preserves_provider_tracks_without_duplicate_entries() {
        val subtitle = PlayerExternalSubtitle("content://documents/subtitle", codec = "ass")
        val item =
            PlayerMediaItem(
                id = "1",
                url = "file:///video",
                transcodeUrl = "",
                title = "Title",
                externalSubtitles = listOf(subtitle),
            )
        assertEquals(
            listOf(subtitle),
            item.withImportedSubtitles(mapOf(item.subtitleItemKey() to listOf(subtitle))).externalSubtitles,
        )
    }
}
