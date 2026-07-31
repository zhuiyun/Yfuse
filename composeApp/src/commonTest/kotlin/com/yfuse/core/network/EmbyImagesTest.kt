package com.yfuse.core.network

import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.Person
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbyImagesTest {

    private val item = MediaItem(
        id = "ep1",
        title = "第 5 集",
        subtitle = null,
        type = "Episode",
        posterItemId = "series1",
        posterTag = "ptag",
        backdropItemId = "series1",
        backdropTag = "btag",
        playedPercentage = null,
    )

    private val detail = MediaDetail(
        id = "series1",
        title = "剧",
        type = "Series",
        seriesId = null,
        overview = null,
        year = null,
        genres = emptyList(),
        runtimeMinutes = null,
        officialRating = null,
        communityRating = null,
        posterItemId = "series1",
        posterTag = "ptag",
        backdropItemId = "series1",
        backdropTag = "btag",
        resumePositionTicks = null,
        people = emptyList(),
    )

    /**
     * The blank-artwork bug this pins: a server that requires authentication answers 401
     * to a token-less image request, so a builder that quietly drops the token takes a
     * whole screen's posters down with it.
     */
    @Test
    fun every_builder_carries_the_session_token() {
        val urls = listOfNotNull(
            EmbyImages.primary("http://emby", "item1", "tag", accessToken = "t"),
            EmbyImages.backdropOf("http://emby", "item1", "tag", accessToken = "t"),
            EmbyImages.backdropAt("http://emby", "item1", 2, "tag", accessToken = "t"),
            EmbyImages.poster("http://emby", item, accessToken = "t"),
            EmbyImages.backdrop("http://emby", item, accessToken = "t"),
            EmbyImages.poster("http://emby", detail, accessToken = "t"),
            EmbyImages.backdrop("http://emby", detail, accessToken = "t"),
            EmbyImages.avatar("http://emby", Person("p1", "名字", null, "tag"), accessToken = "t"),
        )

        assertEquals(8, urls.size)
        urls.forEach { assertTrue("api_key=t" in it, it) }
    }

    @Test
    fun a_blank_token_is_left_off_entirely() {
        val url = EmbyImages.primary("http://emby", "item1", "tag", accessToken = "")

        assertEquals(
            "http://emby/Items/item1/Images/Primary?tag=tag&maxHeight=450&quality=90",
            url,
        )
    }

    @Test
    fun the_backdrop_index_addresses_the_image_and_the_tag_cache_busts_it() {
        val url = EmbyImages.backdropAt("HTTP://emby/", "item1", 3, "btag", accessToken = "t")

        assertEquals(
            "http://emby/Items/item1/Images/Backdrop/3?tag=btag&maxWidth=1280&quality=85&api_key=t",
            url,
        )
    }

    @Test
    fun an_item_without_a_backdrop_has_no_backdrop_url() {
        assertNull(EmbyImages.backdrop("http://emby", item.copy(backdropItemId = null)))
    }

    /** Emby serves the item's default image when no tag is known; the tag is not required. */
    @Test
    fun a_missing_tag_still_produces_a_url() {
        val url = EmbyImages.primary("http://emby", "item1", tag = null, accessToken = "t")

        assertEquals("http://emby/Items/item1/Images/Primary?maxHeight=450&quality=90&api_key=t", url)
    }

    @Test
    fun a_missing_server_or_item_produces_nothing() {
        assertNull(EmbyImages.primary("", "item1", "tag"))
        assertNull(EmbyImages.primary("http://emby", "", "tag"))
        assertNull(EmbyImages.backdropAt("http://emby", "item1", index = -1, tag = "tag"))
    }
}
