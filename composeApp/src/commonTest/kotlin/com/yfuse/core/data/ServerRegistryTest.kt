package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerRegistryTest {

    private fun server(id: String) = SavedServer(id, "http://$id", "name-$id", "u", "user", "tok")

    @Test
    fun first_added_becomes_default() {
        val r = ServerRegistry(MapSettings())
        r.addOrUpdate(server("a"))
        assertEquals("a", r.data.value.defaultServerId)
        assertEquals("a", r.defaultServer?.id)
    }

    @Test
    fun second_added_keeps_first_default() {
        val r = ServerRegistry(MapSettings())
        r.addOrUpdate(server("a"))
        r.addOrUpdate(server("b"))
        assertEquals(2, r.data.value.servers.size)
        assertEquals("a", r.data.value.defaultServerId)
    }

    @Test
    fun set_default_switches() {
        val r = ServerRegistry(MapSettings())
        r.addOrUpdate(server("a"))
        r.addOrUpdate(server("b"))
        r.setDefault("b")
        assertEquals("b", r.defaultServer?.id)
    }

    @Test
    fun removing_default_reassigns_to_remaining() {
        val r = ServerRegistry(MapSettings())
        r.addOrUpdate(server("a"))
        r.addOrUpdate(server("b"))
        r.remove("a")
        assertEquals(1, r.data.value.servers.size)
        assertEquals("b", r.defaultServer?.id)
    }

    @Test
    fun removing_last_clears_default() {
        val r = ServerRegistry(MapSettings())
        r.addOrUpdate(server("a"))
        r.remove("a")
        assertNull(r.defaultServer)
    }

    @Test
    fun persists_across_instances() {
        val settings = MapSettings()
        ServerRegistry(settings).addOrUpdate(server("a"))
        val reloaded = ServerRegistry(settings)
        assertEquals(1, reloaded.data.value.servers.size)
        assertEquals("a", reloaded.defaultServer?.id)
    }
}
