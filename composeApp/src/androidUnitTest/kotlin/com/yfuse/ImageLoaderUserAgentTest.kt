package com.yfuse

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The image pipeline has to send the same User-Agent as the API client.
 *
 * Emby deployments behind a reverse proxy commonly gate `/Items/{id}/Images/...` on the UA.
 * A user who set a custom one did it to satisfy such a proxy, so pinning the image fetcher
 * to the stock string leaves every poster 403ing while every API call succeeds — the hardest
 * shape of that failure to diagnose, and one no unit test would otherwise catch.
 *
 * Scanned from source, matching how the other policy contracts in this module are pinned:
 * the image loader is only reachable through Coil's singleton on a real device.
 */
class ImageLoaderUserAgentTest {
    @Test
    fun image_fetcher_reads_the_user_agent_preference() {
        val source = applicationSource()

        assertTrue(
            Regex("""header\(\s*HttpHeaders\.UserAgent\s*,\s*\w+\.userAgent\.value\s*,?\s*\)""")
                .containsMatchIn(source),
            "The Coil fetcher must send UserAgentPreferences.userAgent, not a fixed string",
        )
        assertTrue(
            "UserAgentPreferences" in source,
            "The image loader must resolve the user's UA preference",
        )
    }

    @Test
    fun image_fetcher_does_not_pin_the_stock_user_agent() {
        val source = applicationSource()

        assertFalse(
            "DEFAULT_EMBY_USER_AGENT" in source,
            "Hard-coding the stock UA here is what made custom UAs apply to API calls only. " +
                "UserAgentPreferences already falls back to it when the user set nothing.",
        )
    }

    @Test
    fun image_fetcher_resolves_the_preference_per_request() {
        val source = applicationSource()

        // `install(UserAgent) { agent = ... }` captures one string when Coil builds the client,
        // which it does once per process; `defaultRequest` re-runs for every request instead.
        assertTrue(
            "defaultRequest {" in source,
            "The UA must be read per request so a settings change applies without a restart",
        )
        assertFalse(
            Regex("""install\(\s*UserAgent\s*\)""").containsMatchIn(source),
            "The UserAgent plugin freezes the header at client construction",
        )
    }

    private fun applicationSource(): String =
        sequenceOf(
            File("src/androidMain/kotlin/com/yfuse/YfuseApp.kt"),
            File("composeApp/src/androidMain/kotlin/com/yfuse/YfuseApp.kt"),
        ).firstOrNull(File::isFile)
            ?.readText()
            ?: error("Cannot locate YfuseApp.kt from ${File(".").absolutePath}")
}
