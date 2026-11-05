package com.yfuse.core.iptv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IptvTest {
    private fun playlist(url: String = "http://lan/live.m3u8") =
        """
        #EXTM3U
        #EXTINF:-1 tvg-id="news" group-title="新闻",新闻,综合
        #EXTVLCOPT:http-user-agent=Yfuse
        $url
        """.trimIndent()

    @Test fun extended_m3u_preserves_plain_http_groups_names_and_scoped_headers() {
        val parsed = parseM3u("\uFEFF" + playlist() + "\n#EXTINF:-1,unsupported\nftp://lan/x\n")
        val channel = parsed.channels.single()
        assertEquals("新闻,综合", channel.name)
        assertEquals("新闻", channel.group)
        assertEquals("http://lan/live.m3u8", channel.url)
        assertEquals(mapOf("User-Agent" to "Yfuse"), channel.headers)
        assertEquals(1, parsed.skipped)
        assertFalse(channel.toString().contains("lan"))
    }

    @Test fun hls_and_oversized_playlists_are_not_committed_as_empty_subscriptions() {
        assertFailsWith<IllegalArgumentException> { parseM3u("#EXTM3U\n#EXT-X-TARGETDURATION:6\nsegment.ts") }
        assertFailsWith<IllegalArgumentException> { parseM3u("x".repeat(MAX_M3U_BYTES + 1)) }
    }

    @Test fun refresh_failure_cancellation_and_storage_failure_preserve_previous_snapshot() =
        runTest {
            val storage = MemoryStorage()
            var response: suspend () -> String = { playlist() }
            val repo = IptvRepository(storage, download = { response() })
            val id = repo.add("电视", "http://lan/list.m3u")
            val before = repo.subscriptions.value
            response = { error("offline") }
            assertFailsWith<IllegalStateException> { repo.refresh(id) }
            assertEquals(before, repo.subscriptions.value)
            response = { throw CancellationException("cancel") }
            assertFailsWith<CancellationException> { repo.refresh(id) }
            assertEquals(before, repo.subscriptions.value)
            response = { playlist("https://lan/renewed?token=new") }
            storage.failWrite = true
            assertFailsWith<IllegalStateException> { repo.refresh(id) }
            assertEquals(before, repo.subscriptions.value)
        }

    @Test fun refresh_retains_favorite_identity_across_rotating_links_and_reload() =
        runTest {
            val storage = MemoryStorage()
            var text = playlist()
            val repo = IptvRepository(storage, download = { text })
            val id = repo.add("电视", "http://lan/list.m3u")
            val channelId =
                repo.subscriptions.value
                    .single()
                    .channels
                    .single()
                    .id
            repo.toggleFavorite(id, channelId)
            text = playlist("http://lan/live.m3u8?token=rotated")
            repo.refresh(id)
            val restored = IptvRepository(storage)
            restored.ensureLoaded()
            assertEquals(
                setOf(channelId),
                restored.subscriptions.value
                    .single()
                    .favorites,
            )
            assertTrue(
                restored.subscriptions.value
                    .single()
                    .channels
                    .single()
                    .url
                    .endsWith("rotated"),
            )
        }

    private class MemoryStorage : IptvStorage {
        var text: String? = null
        var failWrite = false

        override fun read() = text

        override fun write(text: String) {
            check(!failWrite)
            this.text = text
        }
    }
}
