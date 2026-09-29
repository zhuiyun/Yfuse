package com.yfuse.feature.watch

import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.WatchInvite
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WatchInviteResolverTest {
    private fun server(id: String) =
        SavedServer(
            id = id,
            baseUrl = "https://$id.example.invalid",
            serverName = "服务器 $id",
            userId = "u",
            userName = "user",
            accessToken = "token-$id",
        )

    private fun item(title: String) =
        MediaItem(
            id = "item",
            title = title,
            subtitle = null,
            type = "Movie",
            posterItemId = "item",
            posterTag = null,
            backdropItemId = null,
            backdropTag = null,
            playedPercentage = null,
            year = 2026,
        )

    private val invite = WatchInvite(roomCode = "ABCD", mediaKey = "tmdb:1", title = "某电影")

    @Test
    fun theDefaultServerIsTriedFirstThenTheRestInOrder() {
        val (a, b, c) = listOf(server("a"), server("b"), server("c"))
        assertEquals(listOf(b, a, c), orderedServers(listOf(a, b, c), default = b))
        assertEquals(listOf(a, b, c), orderedServers(listOf(a, b, c), default = null))
    }

    @Test
    fun anInviteWithoutAMediaKeyIsMissingWithItsTitle() =
        runTest {
            val result = resolveInvite(invite.copy(mediaKey = null), listOf(server("a"))) { _, _ -> error("not asked") }
            assertEquals(InviteResolution.Missing("某电影"), result)
        }

    @Test
    fun noServerAtAllIsAFailureThatSaysWhatToDo() =
        runTest {
            val result = resolveInvite(invite, emptyList()) { _, _ -> error("not asked") }
            assertIs<InviteResolution.Failed>(result)
        }

    @Test
    fun anUnreachableServerIsSkippedAndTheTitleFoundOnTheNext() =
        runTest {
            val asked = mutableListOf<String>()
            val result =
                resolveInvite(invite, listOf(server("a"), server("b"))) { server, key ->
                    asked += "${server.id}:$key"
                    if (server.id == "a") {
                        Result.failure(IllegalStateException("offline"))
                    } else {
                        Result.success(item("某电影"))
                    }
                }
            assertEquals(listOf("a:tmdb:1", "b:tmdb:1"), asked)
            val found = assertIs<InviteResolution.Found>(result)
            assertEquals("服务器 b", found.serverName)
            assertEquals("2026", found.subtitle)
        }

    @Test
    fun onlyFailuresIsAFailureAndOnlyMissesIsMissing() =
        runTest {
            val failing = resolveInvite(invite, listOf(server("a"))) { _, _ -> Result.failure(IllegalStateException()) }
            assertIs<InviteResolution.Failed>(failing)
            val missing = resolveInvite(invite, listOf(server("a"))) { _, _ -> Result.success(null) }
            assertEquals(InviteResolution.Missing("某电影"), missing)
        }

    @Test
    fun theRoomsTargetResolvesToTheFirstServerThatHasIt() =
        runTest {
            val target =
                resolveInviteTarget("tmdb:2", listOf(server("a"), server("b"))) { server, _ ->
                    if (server.id == "b") Result.success(item("第 2 集")) else Result.success(null)
                }
            assertEquals("b", target?.server?.id)
            assertNull(resolveInviteTarget("tmdb:2", listOf(server("a"))) { _, _ -> Result.success(null) })
        }
}
