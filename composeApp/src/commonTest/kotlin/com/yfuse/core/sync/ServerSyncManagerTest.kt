package com.yfuse.core.sync

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerSyncManagerTest {
    @Test
    fun backoff_survives_manager_recreation() = runTest {
        val settings = MapSettings()
        val registry = ServerRegistry(settings).apply { addOrUpdate(server("http://emby.test")) }
        var requests = 0
        val repo = testRepo {
            requests++
            throw IOException("offline")
        }

        ServerSyncManager(repo, registry, settings).syncAll()
        ServerSyncManager(repo, ServerRegistry(settings), settings).syncAll()

        assertEquals(1, requests)
    }

    @Test
    fun successful_forced_retry_clears_persisted_backoff() = runTest {
        val settings = MapSettings()
        val registry = ServerRegistry(settings).apply { addOrUpdate(server("http://emby.test")) }
        var requests = 0
        var fail = true
        val repo = testRepo {
            requests++
            if (fail) throw IOException("offline")
            json("""{"Items":[],"TotalRecordCount":0}""")
        }

        ServerSyncManager(repo, registry, settings).syncAll()
        fail = false
        ServerSyncManager(repo, ServerRegistry(settings), settings).syncAll(force = true)
        ServerSyncManager(repo, ServerRegistry(settings), settings).syncAll()

        assertEquals(3, requests)
    }

    @Test
    fun known_unavailable_yun_endpoint_is_skipped_even_when_forced() = runTest {
        val settings = MapSettings()
        val registry = ServerRegistry(settings).apply {
            addOrUpdate(server("http://gf.emby.yun:8096"))
        }
        var requests = 0
        val manager = ServerSyncManager(
            repo = testRepo {
                requests++
                error("Known unavailable endpoint must not reach HTTP")
            },
            registry = registry,
            settings = settings,
        )

        manager.syncAll(force = true)

        assertEquals(0, requests)
        val status = manager.state.value.statuses.single()
        assertEquals(false, status.online)
        assertTrue(status.error.orEmpty().contains("编辑或移除"))
    }

    private fun server(baseUrl: String) =
        SavedServer(
            id = SavedServer.idOf(baseUrl, "user"),
            baseUrl = baseUrl,
            serverName = "Emby",
            userId = "user",
            userName = "User",
            accessToken = "token",
        )
}
