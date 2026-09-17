package com.yfuse.core.sync

import com.russhwolf.settings.MapSettings
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.security.TestSecureStore
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherClientTest {
    @Test
    fun a_fresh_client_is_disconnected_without_a_room_timeline_or_server_clock() {
        val client = client()

        assertEquals(WatchTogetherState(), client.state.value)
        assertNull(client.timeline.value)
        assertNull(client.resumableRoom.value)
        assertNull(client.estimatedServerNowOrNull())
        assertTrue(abs(client.estimatedServerNow() - System.currentTimeMillis()) < 5_000L)
    }

    @Test
    fun joining_through_an_endpoint_the_account_does_not_trust_fails_closed_without_connecting() {
        val client = client()

        client.joinRoom("https://relay.example", "ABC234", "tmdb:603")

        val state = client.state.value
        assertEquals("一起看协议 v5 仅支持 Yfuse 账号服务的官方 HTTPS 地址", state.error)
        assertFalse(state.connecting)
        assertFalse(state.connected)
        assertNull(state.roomCode)
    }

    @Test
    fun the_official_endpoint_is_still_refused_when_the_account_token_source_trusts_another_origin() {
        val client = client(tokens = AccountAccessTokenSource("https://other.example"))

        client.createRoom(WatchTogetherPreferences.DEFAULT_ENDPOINT, "tmdb:603")

        assertEquals("一起看协议 v5 仅支持 Yfuse 账号服务的官方 HTTPS 地址", client.state.value.error)
        assertFalse(client.state.value.connecting)
    }

    @Test
    fun chat_and_reactions_are_rejected_while_disconnected_without_touching_the_state() {
        val client = client()

        assertFalse(client.sendChat("hello"))
        assertFalse(client.sendReaction(WatchReaction.entries.first()))

        assertTrue(client.state.value.chatMessages.isEmpty())
        assertTrue(client.state.value.reactions.isEmpty())
        assertNull(client.state.value.chatError)
    }

    @Test
    fun a_sync_warning_marks_the_local_media_unavailable_until_it_is_cleared() {
        val client = client()

        client.setSyncWarning("本机没有这部片")
        assertEquals("本机没有这部片", client.state.value.syncWarning)
        assertFalse(client.state.value.localMediaAvailable)

        client.setSyncWarning("字幕缺失", mediaAvailable = true)
        assertEquals("字幕缺失", client.state.value.syncWarning)
        assertTrue(client.state.value.localMediaAvailable)

        client.setSyncWarning(null)
        assertNull(client.state.value.syncWarning)
        assertTrue(client.state.value.localMediaAvailable)
    }

    @Test
    fun a_member_without_control_cannot_publish_a_timeline() {
        val client = client()

        client.publishTimeline("tmdb:603", positionMs = 1_000L, paused = false)

        assertNull(client.timeline.value)
        assertNull(client.state.value.error)
    }

    @Test
    fun a_persisted_room_is_exposed_on_start_and_discarded_on_request() {
        val store = TestSecureStore()
        val resume = PersistedRoomResume(roomCode = "ABC234", mediaKey = "")
        WatchRoomResumeStore(store).save(resume)

        val client = client(secureStore = store)
        assertEquals(resume, client.resumableRoom.value)

        client.discardPersistedRoom()

        assertNull(client.resumableRoom.value)
        assertTrue(store.storedKeys().isEmpty())
    }

    @Test
    fun leaving_resets_every_piece_of_room_state() {
        val client = client()
        client.setSyncWarning("本机没有这部片")
        client.joinRoom("https://relay.example", "ABC234", "tmdb:603")

        client.leave()

        assertEquals(WatchTogetherState(), client.state.value)
        assertNull(client.timeline.value)
        assertNull(client.resumableRoom.value)
    }

    private fun client(
        tokens: AccountAccessTokenSource = AccountAccessTokenSource("https://other.example"),
        secureStore: TestSecureStore = TestSecureStore(),
    ): WatchTogetherClient =
        WatchTogetherClient(
            preferences = WatchTogetherPreferences(MapSettings()),
            accountTokens = tokens,
            resumeStore = WatchRoomResumeStore(secureStore),
        )
}
