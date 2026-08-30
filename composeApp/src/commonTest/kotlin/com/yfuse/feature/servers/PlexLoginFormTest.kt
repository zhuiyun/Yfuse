package com.yfuse.feature.servers

import com.yfuse.core.model.MediaServerKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlexLoginFormTest {
    @Test
    fun plex_requires_a_token_and_never_offers_emby_quick_connect() {
        val withoutToken =
            LoginForm(
                kind = MediaServerKind.Plex,
                https = false,
                host = "192.168.1.2",
                port = "32400",
            )
        val ready = withoutToken.copy(password = "plex-token")

        assertFalse(withoutToken.canSubmit)
        assertTrue(ready.canSubmit)
        assertFalse(ready.canStartQuickConnect)
    }
}
