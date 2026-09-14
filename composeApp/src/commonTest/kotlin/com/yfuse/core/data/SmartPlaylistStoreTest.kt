package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmartPlaylistStoreTest {
    @Test fun rules_restore_and_unpin_without_losing_filters() {
        val settings = MapSettings()
        val store = SmartPlaylistStore(settings)
        val rule =
            SmartPlaylist(
                "未看剧集",
                serverId = "a",
                libraryId = "b",
                type = "Series",
                watchStatus = "Unplayed",
                year = 2025,
            )
        assertTrue(store.save(rule))
        val restored = SmartPlaylistStore(settings)
        assertEquals(rule, restored.items.value.single())
        assertTrue(restored.save(rule.copy(pinned = false)))
        assertEquals(rule.copy(pinned = false), SmartPlaylistStore(settings).items.value.single())
        assertTrue(restored.remove(rule.name))
        assertTrue(SmartPlaylistStore(settings).items.value.isEmpty())
    }

    @Test fun malformed_storage_is_not_overwritten_by_save_or_delete() {
        val settings = MapSettings()
        settings.putString("media.smartPlaylists.v1", "broken-data")
        val store = SmartPlaylistStore(settings)
        assertFalse(store.save(SmartPlaylist("新片单")))
        assertFalse(store.remove("不存在"))
        assertEquals("broken-data", settings.getString("media.smartPlaylists.v1", ""))
    }

    @Test fun cap_allows_updates_but_rejects_extra_rules() {
        val store = SmartPlaylistStore(MapSettings())
        repeat(40) { assertTrue(store.save(SmartPlaylist("片单$it"))) }
        assertFalse(store.save(SmartPlaylist("片单41")))
        assertTrue(store.save(SmartPlaylist("片单0", pinned = false)))
        assertEquals(40, store.items.value.size)
    }
}
