package com.yfuse.core.data.dto

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageBlurHashMappingTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    private fun item(body: String) = json.decodeFromString<BaseItemDto>(body).toMediaItem()

    @Test
    fun a_film_carries_the_hashes_of_its_own_poster_and_backdrop() {
        val film =
            item(
                """
                {"Id":"m1","Name":"Film","Type":"Movie",
                 "ImageTags":{"Primary":"p1","Logo":"l1"},
                 "BackdropImageTags":["b1","b2"],
                 "ImageBlurHashes":{
                   "Primary":{"p1":"LEHV6nWB2yk8pyo0adR*.7kCMdnj"},
                   "Backdrop":{"b1":"LGF5]+Yk^6#M@-5c,1J5@[or[Q6.","b2":"L6PZfSi_.AyE_3t7t7R**0o#DgR4"},
                   "Logo":{"l1":"LKO2?U%2Tw=w]~RBVZRi};RPxuwH"}}}
                """.trimIndent(),
            )
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", film.posterBlurHash)
        // The first backdrop is the one every card draws.
        assertEquals("LGF5]+Yk^6#M@-5c,1J5@[or[Q6.", film.backdropBlurHash)
    }

    @Test
    fun an_episode_takes_the_hashes_of_the_series_poster_and_parent_backdrop_it_shows() {
        val episode =
            item(
                """
                {"Id":"e1","Name":"Pilot","Type":"Episode","SeriesId":"s1","SeriesName":"Show",
                 "SeriesPrimaryImageTag":"sp","ImageTags":{"Primary":"still"},
                 "ParentBackdropItemId":"s1","ParentBackdropImageTags":["sb"],
                 "ImageBlurHashes":{
                   "Primary":{"still":"LKO2?U%2Tw=w]~RBVZRi};RPxuwH","sp":"LEHV6nWB2yk8pyo0adR*.7kCMdnj"},
                   "Backdrop":{"sb":"L6PZfSi_.AyE_3t7t7R**0o#DgR4"}}}
                """.trimIndent(),
            )
        assertEquals("sp", episode.posterTag)
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", episode.posterBlurHash)
        assertEquals("sb", episode.backdropTag)
        assertEquals("L6PZfSi_.AyE_3t7t7R**0o#DgR4", episode.backdropBlurHash)
    }

    @Test
    fun emby_items_and_odd_payloads_simply_have_no_hash() {
        val emby = item("""{"Id":"m2","Name":"Film","ImageTags":{"Primary":"p2"},"BackdropImageTags":["b"]}""")
        assertNull(emby.posterBlurHash)
        assertNull(emby.backdropBlurHash)

        val odd =
            item(
                """
                {"Id":"m3","ImageTags":{"Primary":"p3"},"BackdropImageTags":["b3"],
                 "ImageBlurHashes":{"Primary":{"p3":null},"Backdrop":null,"Thumb":{}}}
                """.trimIndent(),
            )
        assertNull(odd.posterBlurHash)
        assertNull(odd.backdropBlurHash)
    }
}
