package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDiagnosticsTest {
    @Test
    fun authentication_and_access_failures_do_not_cycle_every_engine_and_version() {
        assertTrue(blocksAutomaticPlaybackFallback(401))
        assertTrue(blocksAutomaticPlaybackFallback(403))
        assertFalse(blocksAutomaticPlaybackFallback(400))
        assertFalse(blocksAutomaticPlaybackFallback(404))
        assertFalse(blocksAutomaticPlaybackFallback(500))
        assertFalse(blocksAutomaticPlaybackFallback(null))
    }

    @Test
    fun playback_url_keeps_source_identity_but_redacts_credentials() {
        val raw =
            "https://emby/Videos/1/master.m3u8?api_key=secret" +
                "&MediaSourceId=source-2&PlaySessionId=session"

        val safe = sanitizePlaybackUrl(raw)

        assertFalse("secret" in safe)
        assertTrue("api_key=<redacted>" in safe)
        assertTrue("MediaSourceId=source-2" in safe)
        assertEquals("source-2", playbackQueryParameter(raw, "MediaSourceId"))
    }

    @Test
    fun response_json_credentials_are_redacted() {
        val safe =
            sanitizePlaybackUrl(
                """{"api_key":"secret-json","message":"bad request"}""",
            )

        assertFalse("secret-json" in safe)
        assertTrue("\"api_key\":\"<redacted>\"" in safe)
    }
}
