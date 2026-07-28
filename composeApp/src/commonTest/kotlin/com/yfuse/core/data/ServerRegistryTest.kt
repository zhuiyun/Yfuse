package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.SavedServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun portable_backup_round_trips_credentials_and_default() {
        val source = ServerRegistry(MapSettings())
        val first = SavedServer(
            SavedServer.idOf("https://one.example", "u1"),
            "https://one.example",
            "One",
            "u1",
            "Alice",
            "secret-one",
        )
        val second = SavedServer(
            SavedServer.idOf("http://two.local:8096", "u2"),
            "http://two.local:8096",
            "Two",
            "u2",
            "Bob",
            "secret-two",
        )
        source.addOrUpdate(first)
        source.addOrUpdate(second)
        source.setDefault(second.id)

        val target = ServerRegistry(MapSettings())
        assertEquals(2, target.importBackup(source.exportBackup()).getOrThrow())
        assertEquals(second.id, target.defaultServer?.id)
        assertEquals("secret-one", target.serverById(first.id)?.accessToken)
    }

    @Test
    fun portable_import_merges_and_rejects_invalid_payload() {
        val source = ServerRegistry(MapSettings())
        val imported = SavedServer(
            SavedServer.idOf("https://new.example", "u"),
            "https://new.example",
            "New",
            "u",
            "User",
            "new-token",
        )
        source.addOrUpdate(imported)

        val target = ServerRegistry(MapSettings())
        target.addOrUpdate(server("local"))
        target.importBackup(source.exportBackup()).getOrThrow()
        assertEquals(2, target.data.value.servers.size)
        assertTrue(target.importBackup("""{"v":99,"s":[]}""").isFailure)
    }
}
