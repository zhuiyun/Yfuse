package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PlaybackBookmarksTest {
    @Test
    fun bookmarks_survive_restart_and_isolate_servers_and_versions() {
        val settings = MapSettings()
        val first = PlaybackBookmarkKey("a", "film", "cut1")
        val second = first.copy(serverId = "b")
        val third = first.copy(versionId = "cut2")
        val store = PlaybackBookmarks(settings)
        listOf(first, second, third).forEach { store.save(it, 42_000, "精彩片段", "备注") }
        val restored = PlaybackBookmarks(settings)
        assertEquals(3, restored.items.value.size)
        assertEquals(
            "备注",
            restored.items.value
                .single { it.media == first }
                .note,
        )
        restored.remove(
            second,
            restored.items.value
                .first { it.media == first }
                .id,
        )
        assertEquals(3, restored.items.value.size)
    }

    @Test
    fun saving_the_same_time_updates_its_label_and_delete_is_persistent() {
        val settings = MapSettings()
        val store = PlaybackBookmarks(settings)
        val media = PlaybackBookmarkKey(null, "local-file")
        store.save(media, 12_000, "first")
        store.save(media, 12_000, "second")
        assertEquals(
            "second",
            store.items.value
                .single()
                .title,
        )
        store.remove(
            media,
            store.items.value
                .single()
                .id,
        )
        assertEquals(emptyList(), PlaybackBookmarks(settings).items.value)
    }

    @Test
    fun corrupt_storage_is_not_overwritten_by_a_new_bookmark() {
        val settings = MapSettings()
        settings.putString("playback.bookmarks.v1", "broken")
        val store = PlaybackBookmarks(settings)
        assertNotNull(store.loadError)
        assertFailsWith<IllegalStateException> { store.save(PlaybackBookmarkKey("a", "film"), 1, "x") }
        assertEquals("broken", settings.getString("playback.bookmarks.v1", ""))
    }

    @Test
    fun invalid_record_identity_is_preserved_instead_of_silently_replaced() {
        val settings = MapSettings()
        val document = """[{"id":1,"media":{"serverId":"a","itemId":"film"},"positionMs":-1,"title":"invalid"}]"""
        settings.putString("playback.bookmarks.v1", document)
        val store = PlaybackBookmarks(settings)
        assertNotNull(store.loadError)
        assertFailsWith<IllegalStateException> { store.remove(PlaybackBookmarkKey("a", "film"), 1) }
        assertEquals(document, settings.getString("playback.bookmarks.v1", ""))
    }
}
