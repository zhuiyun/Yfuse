package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionManagerTest {

    @Test
    fun save_then_read_roundtrip() {
        val sm = SessionManager(MapSettings())
        assertFalse(sm.hasSession())

        sm.save("http://host:8096", "tok", "uid")

        assertTrue(sm.hasSession())
        assertEquals("http://host:8096", sm.baseUrl())
        assertEquals("tok", sm.token())
        assertEquals("uid", sm.userId())
    }

    @Test
    fun clear_removes_session() {
        val sm = SessionManager(MapSettings())
        sm.save("http://host:8096", "tok", "uid")

        sm.clear()

        assertFalse(sm.hasSession())
        assertNull(sm.token())
    }
}
