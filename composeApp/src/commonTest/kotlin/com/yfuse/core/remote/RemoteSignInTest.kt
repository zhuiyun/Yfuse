package com.yfuse.core.remote

import com.yfuse.core.data.AuthedServer
import com.yfuse.core.model.MediaServerKind
import com.yfuse.watch.protocol.RemoteSignInServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteSignInTest {
    @Test
    fun a_handed_server_becomes_the_sign_in_this_television_would_have_made() {
        assertEquals(
            AuthedServer(
                baseUrl = "http://192.168.1.8:8096",
                serverName = "家里的 Jellyfin",
                userId = "u1",
                userName = "alice",
                accessToken = "handed-token",
                kind = MediaServerKind.Jellyfin,
            ),
            HANDED.toAuthedServer(),
        )
        // Without its session there is nothing to save, and nothing the relay should not pass is used.
        assertNull(HANDED.summary.toAuthedServer())
        assertNull(HANDED.copy(kind = "Plex").toAuthedServer())
        assertNull(HANDED.copy(baseUrl = "http://alice:secret@192.168.1.8:8096").toAuthedServer())
        assertNull(HANDED.copy(accessToken = "handed token").toAuthedServer())
    }

    @Test
    fun a_television_is_asking_only_while_it_waits_for_a_phone_or_shows_one() {
        assertFalse(RemoteSignInRequest.Idle.asking)
        assertTrue(RemoteSignInRequest.Waiting.asking)
        assertTrue(RemoteSignInRequest.Offered("phone-a", null, HANDED.summary).asking)
        assertFalse(RemoteSignInRequest.Receiving(null, HANDED.summary).asking)
        assertFalse(RemoteSignInRequest.Expired.asking)
    }

    private companion object {
        val HANDED =
            RemoteSignInServer(
                kind = "Jellyfin",
                serverName = "家里的 Jellyfin",
                baseUrl = "http://192.168.1.8:8096",
                userName = "alice",
                userId = "u1",
                accessToken = "handed-token",
            )
    }
}
