package com.yfuse.core.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageCacheKeyTest {
    @Test
    fun removes_api_key_without_changing_other_url_parts() {
        val requestUrl =
            "https://emby.example/Items/42/Images/Primary?tag=poster&api_key=secret&quality=90#image"

        val cacheKey = imageCacheKeyForUrl(requestUrl)

        assertTrue(
            cacheKey.endsWith(
                "https://emby.example/Items/42/Images/Primary?tag=poster&quality=90#image",
            ),
        )
        assertFalse("secret" in cacheKey)
        assertFalse("api_key" in cacheKey.lowercase())
        assertTrue("api_key=secret" in requestUrl)
    }

    @Test
    fun token_rotation_changes_the_account_cache_namespace() {
        val first = imageCacheKeyForUrl("https://emby.example/image?quality=90&api_key=first")
        val second = imageCacheKeyForUrl("https://emby.example/image?quality=90&API_KEY=second")

        assertFalse(first == second)
        assertTrue(first.endsWith("https://emby.example/image?quality=90"))
        assertTrue(second.endsWith("https://emby.example/image?quality=90"))
        assertFalse("first" in first)
        assertFalse("second" in second)
    }

    @Test
    fun removes_a_token_only_query_without_leaving_a_question_mark() {
        val cacheKey = imageCacheKeyForUrl("https://emby.example/image?api_key=secret")
        assertTrue(cacheKey.endsWith("https://emby.example/image"))
        assertFalse("secret" in cacheKey)
    }

    @Test
    fun media_cache_key_removes_all_supported_credential_names() {
        val cacheKey =
            mediaCacheKeyForUrl(
                "https://emby.example/video?X-Emby-Token=secret&api_key=second&static=true",
            )

        assertTrue(cacheKey.endsWith("https://emby.example/video?static=true"))
        assertFalse("secret" in cacheKey)
        assertFalse("second" in cacheKey)
        assertFalse("emby-token" in cacheKey.lowercase())
    }

    @Test
    fun plex_token_is_account_scoped_and_never_persisted_in_the_cache_key() {
        val cacheKey =
            mediaCacheKeyForUrl(
                "https://plex.example/library/parts/12/file.mkv?X-Plex-Token=plex-secret",
            )

        assertTrue(cacheKey.endsWith("https://plex.example/library/parts/12/file.mkv"))
        assertFalse("plex-secret" in cacheKey)
        assertFalse("plex-token" in cacheKey.lowercase())
    }
}
