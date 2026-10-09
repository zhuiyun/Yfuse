package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ArtworkPlaceholderColorsTest {
    @Test
    fun one_server_picture_at_two_sizes_is_one_picture() {
        val picture = "https://media.example/emby/Items/42/Images/Backdrop/0"
        val card = "$picture?tag=abc&maxWidth=480&quality=85&format=webp&api_key=t&ApiKey=t"
        val hero = "$picture?tag=abc&maxWidth=1280&quality=85&format=webp"
        assertEquals(artworkIdentity(hero), artworkIdentity(card))
        // Credentials never become part of what is remembered.
        assertEquals("$picture?tag=abc", artworkIdentity(card))
        // Replaced artwork carries a new tag, and so is a new picture.
        assertNotEquals(artworkIdentity(card), artworkIdentity(card.replace("tag=abc", "tag=def")))
    }

    @Test
    fun plex_is_keyed_by_the_path_it_transcodes_and_tmdb_by_file() {
        val transcode = "http://plex.local:32400/photo/:/transcode"
        val path = "url=%2Flibrary%2Fmetadata%2F7%2Fthumb%2F1"
        val small = "$transcode?width=300&height=450&minSize=1&upscale=0&$path&X-Plex-Token=t"
        val large = "$transcode?width=1280&height=720&minSize=1&upscale=0&$path"
        assertEquals(artworkIdentity(large), artworkIdentity(small))
        assertEquals(
            artworkIdentity("https://image.tmdb.org/t/p/w500/poster.jpg"),
            artworkIdentity("https://media.themoviedb.org/t/p/original/poster.jpg"),
        )
    }

    @Test
    fun a_dominant_colour_worked_out_once_stands_in_for_the_same_picture_elsewhere() {
        val backdrop = "https://media.example/Items/7/Images/Backdrop/0?tag=remembered"
        ArtworkPlaceholderColors.record("$backdrop&maxWidth=1280", 0xFF336699.toInt())
        val card = "$backdrop&maxWidth=480"
        val poster = "https://media.example/Items/7/Images/Primary?tag=never-seen&maxHeight=450"
        assertEquals(0xFF336699.toInt(), ArtworkPlaceholderColors.peek(listOf(poster, card)))
        assertNull(ArtworkPlaceholderColors.peek(listOf(poster)))
        assertNull(ArtworkPlaceholderColors.peek(emptyList()))
    }
}
