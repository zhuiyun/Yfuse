package com.yfuse.feature.extras

import com.yfuse.core.model.ThemeSong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DetailThemeSongTest {
    @Test
    fun a_theme_plays_only_once_the_page_has_settled_and_was_not_cut_short() {
        val songs =
            listOf(
                ThemeSong("emby", "s1", "theme", "http://host/Audio/s1/stream?static=true&api_key=k"),
                ThemeSong("emby", "s2", "second", "http://host/Audio/s2/stream?static=true&api_key=k"),
            )

        assertEquals("s1", themeSongToPlay(songs, settled = true, silenced = false)?.itemId)
        assertNull(themeSongToPlay(songs, settled = false, silenced = false))
        assertNull(themeSongToPlay(songs, settled = true, silenced = true))
        assertNull(themeSongToPlay(emptyList(), settled = true, silenced = false))
    }

    @Test
    fun a_theme_song_never_prints_its_address() {
        val song = ThemeSong("emby", "s1", "theme", "http://host/Audio/s1/stream?static=true&api_key=k")

        assertFalse("api_key" in song.toString())
    }
}
