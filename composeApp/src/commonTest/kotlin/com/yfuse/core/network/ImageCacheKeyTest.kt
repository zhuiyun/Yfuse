package com.yfuse.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageCacheKeyTest {

    @Test
    fun removes_api_key_without_changing_other_url_parts() {
        val requestUrl =
            "https://emby.example/Items/42/Images/Primary?tag=poster&api_key=secret&quality=90#image"

        val cacheKey = imageCacheKeyForUrl(requestUrl)

        assertEquals(
            "https://emby.example/Items/42/Images/Primary?tag=poster&quality=90#image",
            cacheKey,
        )
        assertFalse("secret" in cacheKey)
        assertTrue("api_key=secret" in requestUrl)
    }

    @Test
    fun token_rotation_keeps_the_same_cache_identity() {
        val first = imageCacheKeyForUrl("https://emby.example/image?quality=90&api_key=first")
        val second = imageCacheKeyForUrl("https://emby.example/image?quality=90&API_KEY=second")

        assertEquals(first, second)
        assertEquals("https://emby.example/image?quality=90", first)
    }

    @Test
    fun removes_a_token_only_query_without_leaving_a_question_mark() {
        assertEquals(
            "https://emby.example/image",
            imageCacheKeyForUrl("https://emby.example/image?api_key=secret"),
        )
    }
}
