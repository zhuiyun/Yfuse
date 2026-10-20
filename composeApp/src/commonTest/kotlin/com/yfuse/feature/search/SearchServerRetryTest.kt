package com.yfuse.feature.search

import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.model.SavedServer
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchServerRetryTest {
    @Test fun retryPreservesSuccessfulServersAndMergedResults() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            val registry =
                testRegistry().apply {
                    addOrUpdate(SavedServer("a", "http://good", "Good", "u", "u", "token"))
                    addOrUpdate(SavedServer("b", "http://bad", "Bad", "u", "u", "token"))
                    addOrUpdate(SavedServer("c", "http://other", "Other", "u", "u", "token"))
                }
            var failed = true
            val calls = mutableListOf<String>()
            val repo =
                testRepo(dispatcher) { request ->
                    if (request.url.encodedPath.endsWith("/Items")) {
                        calls += request.url.host
                        if (request.url.host != "good" && failed) error("暂不可用")
                        json(
                            """{"Items":[{"Id":"${request.url.host}","Name":"Film","Type":"Movie"}],
                                "TotalRecordCount":1}""",
                        )
                    } else {
                        json("""{"Items":[]}""")
                    }
                }
            val preferences = PlaybackPreferences(MapSettings()).apply { setSmartCrossServerSource(true) }
            val store =
                SearchStoreFactory(
                    DefaultStoreFactory(),
                    repo,
                    registry,
                    playbackPreferences = preferences,
                ).create()
            try {
                store.accept(SearchIntent.QueryChanged("Film"))
                store.accept(SearchIntent.Submit)
                advanceUntilIdle()
                assertEquals(listOf("b", "c"), store.state.unavailableGroups.map { it.serverId })
                val goodCalls = calls.count { it == "good" }
                val badCalls = calls.count { it == "bad" }
                val successful = store.state.groups.first { it.serverId == "a" }
                failed = false
                store.accept(SearchIntent.RetryServer("b"))
                store.accept(SearchIntent.RetryServer("b"))
                assertTrue(
                    store.state.groups
                        .first { it.serverId == "b" }
                        .retrying,
                )
                assertFalse(store.state.loading)
                advanceUntilIdle()
                assertEquals(goodCalls, calls.count { it == "good" })
                assertEquals(badCalls + 1, calls.count { it == "bad" })
                assertEquals(successful, store.state.groups.first { it.serverId == "a" })
                assertEquals(listOf("c"), store.state.unavailableGroups.map { it.serverId })
                store.accept(SearchIntent.RetryServer())
                advanceUntilIdle()
                assertTrue(store.state.unavailableGroups.isEmpty())
                assertEquals(goodCalls, calls.count { it == "good" })
                assertEquals(badCalls + 1, calls.count { it == "bad" })
                assertEquals(3, store.state.items.size)
                assertTrue(store.state.aggregated.isNotEmpty())
            } finally {
                store.dispose()
                advanceUntilIdle()
                Dispatchers.resetMain()
            }
        }
}
