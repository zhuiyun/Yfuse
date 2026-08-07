package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServersData
import kotlinx.serialization.json.Json
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
        assertEquals("name-a", reloaded.defaultServer?.serverName)
    }

    @Test
    fun rename_persists_without_changing_identity_session_or_default() {
        val settings = MapSettings()
        val original = server("a")
        val registry = ServerRegistry(settings)
        registry.addOrUpdate(original)

        assertTrue(registry.rename(original.id, "  客厅影院  "))

        val renamed = ServerRegistry(settings).defaultServer
        assertEquals(original.id, renamed?.id)
        assertEquals("客厅影院", renamed?.serverName)
        assertEquals(original.accessToken, renamed?.accessToken)
        assertEquals(original.id, ServerRegistry(settings).data.value.defaultServerId)
    }

    @Test
    fun replacing_a_connection_keeps_the_previous_id_as_a_persisted_alias() {
        val settings = MapSettings()
        val original = SavedServer(
            SavedServer.idOf("http://old.example", "u"),
            "http://old.example",
            "Media",
            "u",
            "User",
            "old-token",
        )
        val replacement = original.copy(
            id = SavedServer.idOf("https://new.example", "u"),
            baseUrl = "https://new.example",
            accessToken = "new-token",
        )
        val registry = ServerRegistry(settings).apply { addOrUpdate(original) }

        assertTrue(registry.replace(original.id, replacement))

        val reloaded = ServerRegistry(settings)
        assertEquals(replacement.id, reloaded.serverById(original.id)?.id)
        assertEquals("new-token", reloaded.serverById(original.id)?.accessToken)
        assertEquals(replacement.id, reloaded.defaultServer?.id)
    }

    @Test
    fun failed_old_cache_cleanup_does_not_fail_an_already_committed_replacement() {
        val original = server("old")
        val replacement = server("new")
        val cacheKey = "library.cache.${original.id}"
        val backing = MapSettings().apply { putString(cacheKey, "cached-home") }
        val settings = object : Settings by backing {
            override fun remove(key: String) {
                if (key == cacheKey) error("cache storage unavailable")
                backing.remove(key)
            }
        }
        val registry = ServerRegistry(settings).apply { addOrUpdate(original) }

        assertTrue(registry.replace(original.id, replacement))

        assertEquals(replacement.id, registry.defaultServer?.id)
        assertEquals(replacement.id, registry.serverById(original.id)?.id)
        assertEquals("cached-home", backing.getStringOrNull(cacheKey))
    }

    @Test
    fun repeated_replacements_keep_only_recent_aliases_and_preserve_lookup() {
        val settings = MapSettings()
        val registry = ServerRegistry(settings)
        val ids = (0..MAX_SERVER_PREVIOUS_IDS + 2).map { "server-$it" }
        registry.addOrUpdate(server(ids.first()))

        ids.drop(1).forEach { nextId ->
            assertTrue(registry.replace(requireNotNull(registry.defaultServer).id, server(nextId)))
        }

        val reloaded = ServerRegistry(settings)
        val latest = requireNotNull(reloaded.defaultServer)
        val expected = ids.dropLast(1).takeLast(MAX_SERVER_PREVIOUS_IDS)
        val dropped = ids.dropLast(1).dropLast(MAX_SERVER_PREVIOUS_IDS)
        assertEquals(ids.last(), latest.id)
        assertEquals(expected, latest.previousIds.toList())
        expected.forEach { alias -> assertEquals(latest.id, reloaded.serverById(alias)?.id) }
        dropped.forEach { alias -> assertNull(reloaded.serverById(alias)) }
    }

    @Test
    fun loading_legacy_unbounded_aliases_keeps_the_most_recent_entries() {
        val settings = MapSettings()
        val aliases = (0..MAX_SERVER_PREVIOUS_IDS + 2).map { "legacy-$it" }
        val saved = server("current").copy(previousIds = aliases.toCollection(linkedSetOf()))
        settings.putString("library.cache.${saved.id}", "current-cache")
        aliases.forEach { settings.putString("library.cache.$it", "orphan-cache") }
        settings.putString("library.cache.unknown", "orphan-cache")
        settings.putString(
            "servers.data",
            Json.encodeToString(
                ServersData.serializer(),
                ServersData(servers = listOf(saved), defaultServerId = saved.id),
            ),
        )

        val loaded = requireNotNull(ServerRegistry(settings).defaultServer)

        assertEquals(aliases.takeLast(MAX_SERVER_PREVIOUS_IDS), loaded.previousIds.toList())
        val persisted = Json.decodeFromString(
            ServersData.serializer(),
            requireNotNull(settings.getStringOrNull("servers.data")),
        )
        assertEquals(
            aliases.takeLast(MAX_SERVER_PREVIOUS_IDS),
            persisted.servers.single().previousIds.toList(),
        )
        assertEquals("current-cache", settings.getStringOrNull("library.cache.${saved.id}"))
        aliases.forEach { assertNull(settings.getStringOrNull("library.cache.$it")) }
        assertNull(settings.getStringOrNull("library.cache.unknown"))
    }

    @Test
    fun removing_a_replaced_server_also_removes_its_old_id_alias() {
        val original = server("old")
        val replacement = server("new")
        val registry = ServerRegistry(MapSettings()).apply { addOrUpdate(original) }
        assertTrue(registry.replace(original.id, replacement))

        registry.remove(replacement.id)

        assertNull(registry.serverById(original.id))
    }

    @Test
    fun portable_backup_round_trips_credentials_and_default() {
        val source = ServerRegistry(MapSettings())
        val first = SavedServer(
            SavedServer.idOf("https://one.example", "u1"),
            "https://one.example",
            "客厅影院",
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
        assertEquals("客厅影院", target.serverById(first.id)?.serverName)
    }

    @Test
    fun legacy_v1_backup_imports_its_server_name() {
        val baseUrl = "https://old.example"
        val id = SavedServer.idOf(baseUrl, "u")
        val payload =
            """{"v":1,"d":"$id","s":[{"b":"$baseUrl","n":"旧服务器名称","u":"u","a":"User","t":"tok"}]}"""

        val registry = ServerRegistry(MapSettings())
        assertEquals(1, registry.importBackup(payload).getOrThrow())
        assertEquals("旧服务器名称", registry.serverById(id)?.serverName)
        assertEquals(id, registry.defaultServer?.id)
        assertEquals(id, registry.data.value.defaultServerId)
    }

    @Test
    fun portable_import_replaces_the_name_and_token_for_the_same_server() {
        val imported = SavedServer(
            SavedServer.idOf("https://same.example", "u"),
            "https://same.example",
            "迁移后的名称",
            "u",
            "User",
            "imported-token",
        )
        val source = ServerRegistry(MapSettings()).apply { addOrUpdate(imported) }
        val target = ServerRegistry(MapSettings()).apply {
            addOrUpdate(imported.copy(serverName = "本地旧名称", accessToken = "old-token"))
        }

        assertEquals(1, target.importBackup(source.exportBackup()).getOrThrow())

        assertEquals(1, target.data.value.servers.size)
        assertEquals("迁移后的名称", target.serverById(imported.id)?.serverName)
        assertEquals("imported-token", target.serverById(imported.id)?.accessToken)
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
