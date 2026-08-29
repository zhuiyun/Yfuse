package com.yfuse.core.sync

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerSyncManagerTest {
    @Test
    fun eachNewManagerMakesOneFreshStartupProgressAttemptDespiteOldBackoff() =
        runTest {
            val settings = MapSettings()
            val secrets = TestSecureStore()
            val registry = ServerRegistry(settings, secrets).apply { addOrUpdate(server("https://emby.test")) }
            var requests = 0
            val repo =
                testRepo {
                    requests++
                    throw IOException("offline")
                }

            ServerSyncManager(repo, registry, settings).syncAll()
            ServerSyncManager(repo, ServerRegistry(settings, secrets), settings).syncAll()

            assertEquals(2, requests)
        }

    @Test
    fun progressSnapshotIsPulledOnlyOncePerServerAndSeedsLocalStore() =
        runTest {
            val settings = MapSettings()
            val savedServer = server("https://emby.test")
            val registry =
                ServerRegistry(settings, TestSecureStore()).apply {
                    addOrUpdate(savedServer)
                }
            val progressQueries = mutableListOf<String>()
            val store = PlaybackSyncStore(MapSettings()) { 1_000L }
            val manager =
                ServerSyncManager(
                    repo =
                        testRepo { request ->
                            val query = request.url.toString()
                            if (
                                request.url.parameters["Filters"] == "IsResumable" ||
                                request.url.parameters["IsPlayed"] == "true"
                            ) {
                                progressQueries += query
                                json(
                                    """{"Items":[{"Id":"movie-1","Name":"Movie","Type":"Movie","UserData":{"PlaybackPositionTicks":250000000,"Played":false}}],"TotalRecordCount":1}""",
                                )
                            } else {
                                json("""{"Items":[],"TotalRecordCount":0}""")
                            }
                        },
                    registry = registry,
                    settings = settings,
                    playbackStore = store,
                )

            manager.syncAll(force = true)
            manager.syncAll(force = true)

            assertEquals(2, progressQueries.size)
            assertEquals(
                25_000L,
                store.stateForServerItem(savedServer.id, "movie-1")?.positionMs,
            )
            assertTrue(store.pending().isEmpty())
        }

    @Test
    fun successful_forced_retry_clears_persisted_backoff() =
        runTest {
            val settings = MapSettings()
            val secrets = TestSecureStore()
            val registry = ServerRegistry(settings, secrets).apply { addOrUpdate(server("https://emby.test")) }
            var requests = 0
            var fail = true
            val repo =
                testRepo {
                    requests++
                    if (fail) throw IOException("offline")
                    json("""{"Items":[],"TotalRecordCount":0}""")
                }

            ServerSyncManager(repo, registry, settings).syncAll()
            fail = false
            ServerSyncManager(repo, ServerRegistry(settings, secrets), settings).syncAll(force = true)
            ServerSyncManager(repo, ServerRegistry(settings, secrets), settings).syncAll()

            assertEquals(7, requests)
        }

    @Test
    fun known_unavailable_yun_endpoint_is_skipped_even_when_forced() =
        runTest {
            val settings = MapSettings()
            val registry =
                ServerRegistry(settings, TestSecureStore()).apply {
                    addOrUpdate(server("https://gf.emby.yun:8096"))
                }
            var requests = 0
            val manager =
                ServerSyncManager(
                    repo =
                        testRepo {
                            requests++
                            error("Known unavailable endpoint must not reach HTTP")
                        },
                    registry = registry,
                    settings = settings,
                )

            manager.syncAll(force = true)

            assertEquals(0, requests)
            val status =
                manager.state.value.statuses
                    .single()
            assertEquals(false, status.online)
            assertTrue(status.error.orEmpty().contains("编辑或移除"))
        }

    @Test
    fun disabledProgressSyncDoesNotWritePlayedStateToEmby() =
        runTest {
            val settings = MapSettings()
            val savedServer = server("https://emby.test")
            val registry =
                ServerRegistry(settings, TestSecureStore()).apply {
                    addOrUpdate(savedServer)
                }
            var requests = 0
            val manager =
                ServerSyncManager(
                    repo =
                        testRepo {
                            requests++
                            error("Disabled progress sync must not reach HTTP")
                        },
                    registry = registry,
                    settings = settings,
                )
            manager.setProgress(false)

            val result =
                manager.setPlayed(
                    server = savedServer,
                    itemId = "movie",
                    title = "Movie",
                    value = true,
                )

            val recreated = ServerSyncManager(testRepo { json("{}") }, registry, settings)

            assertTrue(result.isSuccess)
            assertFalse(manager.syncProgress.value)
            assertFalse(recreated.syncProgress.value)
            assertEquals(0, requests)
        }

    @Test
    fun pending_queue_keeps_only_the_newest_distinct_operations_within_capacity() {
        val input =
            listOf(
                pending("one", desired = false),
                pending("two", desired = false),
                pending("three", desired = false),
                pending("four", desired = false),
                pending("one", desired = true),
            )

        val bounded =
            boundPendingMutations(
                value = input,
                maxEntries = 3,
                maxSerializedBytes = 32 * 1024,
            )

        assertEquals(listOf("three", "four", "one"), bounded.map { it.itemId })
        assertEquals(true, bounded.last().desired)
    }

    @Test
    fun pending_queue_rejects_oversized_identity_and_bounds_display_text() {
        val bounded =
            boundPendingMutations(
                value =
                    listOf(
                        pending("x".repeat(513)),
                        pending("valid").copy(title = "片".repeat(1_000)),
                    ),
                maxEntries = 10,
                maxSerializedBytes = 32 * 1024,
            )

        assertEquals(listOf("valid"), bounded.map { it.itemId })
        assertEquals(256, bounded.single().title.length)
    }

    private fun pending(
        itemId: String,
        desired: Boolean = true,
    ) = PendingSyncMutation(
        serverId = "server",
        itemId = itemId,
        title = "Title",
        kind = SyncMutationKind.Favorite,
        desired = desired,
        baseValue = null,
        createdAtEpochMs = 1L,
    )

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
