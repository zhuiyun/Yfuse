package com.yfuse.core.sync

import com.yfuse.core.security.TestSecureStore
import com.yfuse.watch.protocol.WatchProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchRoomResumeStoreTest {
    private val resume =
        PersistedRoomResume(
            roomCode = "ABC234",
            mediaKey = "tmdb:1399/s2e5",
            resumeCapability = "r".repeat(WatchProtocol.CAPABILITY_LENGTH),
            hostCapability = "h".repeat(WatchProtocol.CAPABILITY_LENGTH),
        )

    @Test
    fun a_welcomed_room_survives_a_fresh_store_instance() {
        val secrets = TestSecureStore()
        WatchRoomResumeStore(secrets).save(resume)

        assertEquals(resume, WatchRoomResumeStore(secrets).load())
        assertTrue(secrets.storedKeys().single().startsWith("watch_together."))
    }

    @Test
    fun clearing_forgets_the_capabilities() {
        val secrets = TestSecureStore()
        val store = WatchRoomResumeStore(secrets)
        store.save(resume)
        store.clear()

        assertNull(store.load())
        assertTrue(secrets.storedKeys().isEmpty())
    }

    @Test
    fun entries_with_invalid_capabilities_are_refused_and_dropped() {
        val secrets = TestSecureStore()
        val store = WatchRoomResumeStore(secrets)
        store.save(resume.copy(hostCapability = "short"))
        assertNull(store.load())
        assertTrue(secrets.storedKeys().isEmpty())
    }

    @Test
    fun malformed_or_unreadable_entries_read_as_nothing_saved() {
        val secrets = TestSecureStore()
        val store = WatchRoomResumeStore(secrets)
        secrets.put("watch_together.room_resume.v1", "not json".encodeToByteArray())
        assertNull(store.load())
        assertTrue(secrets.storedKeys().isEmpty())

        secrets.corruptedKeys += "watch_together.room_resume.v1"
        assertNull(store.load())
    }

    @Test
    fun write_failures_and_a_missing_store_are_silent() {
        val secrets = TestSecureStore().apply { failWrites = true }
        WatchRoomResumeStore(secrets).save(resume)
        assertNull(WatchRoomResumeStore(secrets).load())

        val absent = WatchRoomResumeStore(null)
        absent.save(resume)
        assertNull(absent.load())
        absent.clear()
    }
}
