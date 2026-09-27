package com.yfuse.watch.protocol

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteControlProtocolTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun remote_control_is_a_capability_with_its_own_message_types() {
        assertTrue(WatchProtocol.CAPABILITY_REMOTE_CONTROL in WatchProtocol.SERVER_CAPABILITIES)
        WatchProtocol.REMOTE_CLIENT_MESSAGE_TYPES.forEach { type ->
            assertTrue(type in WatchProtocol.CLIENT_MESSAGE_TYPES, type)
        }
        // The room protocol keeps every type it had.
        assertTrue("hello" in WatchProtocol.CLIENT_MESSAGE_TYPES)
        assertTrue("ping" in WatchProtocol.CLIENT_MESSAGE_TYPES)
        assertTrue("playlistReorder" in WatchProtocol.CLIENT_MESSAGE_TYPES)
    }

    @Test
    fun keys_travel_by_wire_name_and_unknown_names_are_rejected() {
        RemoteControlKey.entries.forEach { key ->
            assertEquals(key, RemoteControlKey.fromWireName(key.wireName))
            assertTrue(WatchProtocol.isValidRemoteKey(key.wireName))
        }
        assertEquals(RemoteControlKey.PlayPause, RemoteControlKey.fromWireName("playPause"))
        assertNull(RemoteControlKey.fromWireName("PlayPause"))
        assertFalse(WatchProtocol.isValidRemoteKey("power"))
        assertFalse(WatchProtocol.isValidRemoteKey(""))
        assertFalse(WatchProtocol.isValidRemoteKey(null))
    }

    @Test
    fun text_is_the_whole_field_so_empty_and_spaces_are_kept() {
        assertTrue(WatchProtocol.isValidRemoteText(""))
        assertTrue(WatchProtocol.isValidRemoteText("星际 "))
        assertTrue(WatchProtocol.isValidRemoteText(" the office"))
        assertTrue(WatchProtocol.isValidRemoteText("😀".repeat(WatchProtocol.MAX_REMOTE_TEXT_GRAPHEMES)))
        assertFalse(WatchProtocol.isValidRemoteText(null))
        assertFalse(WatchProtocol.isValidRemoteText("line\nbreak"))
        assertFalse(WatchProtocol.isValidRemoteText("tab\there"))
        assertFalse(WatchProtocol.isValidRemoteText("a".repeat(WatchProtocol.MAX_REMOTE_TEXT_BYTES + 1)))
        assertFalse(WatchProtocol.isValidRemoteText("中".repeat(WatchProtocol.MAX_REMOTE_TEXT_GRAPHEMES + 1)))
    }

    @Test
    fun session_ids_are_bounded_opaque_ids() {
        assertTrue(WatchProtocol.isValidRemoteSessionId("2b1f8c3e-5d4a-4c1b-9a77-0e6f5d4c3b2a"))
        assertFalse(WatchProtocol.isValidRemoteSessionId(null))
        assertFalse(WatchProtocol.isValidRemoteSessionId(""))
        assertFalse(WatchProtocol.isValidRemoteSessionId("has space"))
        assertFalse(WatchProtocol.isValidRemoteSessionId("x".repeat(WatchProtocol.MAX_REMOTE_SESSION_ID_BYTES + 1)))
    }

    @Test
    fun remote_fields_are_optional_on_the_wire() {
        val key = json.decodeFromString(WatchWireMessage.serializer(), """{"type":"remoteKey","remoteKey":"up"}""")
        assertEquals("up", key.remoteKey)
        assertNull(key.remoteSessionId)
        val chat = json.decodeFromString(WatchWireMessage.serializer(), """{"type":"chat","text":"hi"}""")
        assertNull(chat.remoteKey)
        assertFalse("remote" in json.encodeToString(WatchWireMessage.serializer(), chat))
    }

    @Test
    fun heartbeats_without_the_advertisement_still_decode() {
        val device =
            json.decodeFromString(
                HandoffDevice.serializer(),
                """{"sessionId":"s","name":"客厅","platform":"Android","lastSeenAtEpochMs":1,"canReceive":true}""",
            )
        assertFalse(device.acceptsRemote)
        val heartbeat = HandoffHeartbeat("客厅", "Android", canReceive = false)
        assertFalse("acceptsRemote" in json.encodeToString(HandoffHeartbeat.serializer(), heartbeat))
        val advertised = heartbeat.copy(acceptsRemote = true)
        assertTrue(
            json
                .decodeFromString(
                    HandoffHeartbeat.serializer(),
                    json.encodeToString(HandoffHeartbeat.serializer(), advertised),
                ).acceptsRemote,
        )
    }
}
