package com.yfuse.feature.player

import androidx.media3.datasource.DataSpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VideoCacheKeyTest {
    @Test
    fun authenticated_url_uses_a_redacted_account_scoped_cache_key() {
        val first =
            SecureMediaCacheKeyFactory.buildCacheKey(
                dataSpec("https://emby.example/Videos/42/stream?static=true&api_key=first-secret"),
            )
        val second =
            SecureMediaCacheKeyFactory.buildCacheKey(
                dataSpec("https://emby.example/Videos/42/stream?static=true&api_key=second-secret"),
            )

        assertNotEquals(first, second)
        assertTrue(first.endsWith("https://emby.example/Videos/42/stream?static=true"))
        assertFalse("first-secret" in first)
        assertFalse("api_key" in first)
    }

    private fun dataSpec(uri: String): DataSpec {
        val builder = DataSpec.Builder()
        builder.setUri(uri)
        return builder.build()
    }
}
