package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.network.TheOtherDbMarker
import com.yfuse.core.network.TheOtherDbQuery
import com.yfuse.core.network.TheOtherDbVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TheOtherDbRepositoryTest {
    private val query = TheOtherDbQuery(226045, 1, 1)

    @Test
    fun oversized_records_are_returned_but_not_persisted_in_preferences() =
        runTest {
            val settings = MapSettings()
            val huge = listOf(TheOtherDbVersion(1, platform = "x".repeat(512_001)))
            val repository = TheOtherDbRepository(settings, { huge })
            assertEquals(huge, repository.versions(query))
            assertEquals("{}", settings.getStringOrNull("player.skip.theotherdb.cache.v1"))
        }

    private val versions =
        listOf(TheOtherDbVersion(1192, 1300.0, markers = listOf(TheOtherDbMarker("intro", 0.0, 27.0))))

    @Test
    fun positive_cache_survives_restart_and_expires_after_seven_days() =
        runTest {
            val settings = MapSettings()
            var now = 1_000L
            var calls = 0
            val fetch: suspend (TheOtherDbQuery) -> List<TheOtherDbVersion> = {
                calls++
                versions
            }
            val repository = TheOtherDbRepository(settings, fetch) { now }
            assertEquals(versions, repository.versions(query))
            assertEquals(versions, TheOtherDbRepository(settings, fetch) { now }.versions(query))
            assertEquals(1, calls)
            now += 7 * 24 * 60 * 60 * 1_000L
            repository.versions(query)
            assertEquals(2, calls)
        }

    @Test
    fun empty_response_is_cached_for_six_hours_and_separate_episodes_have_separate_keys() =
        runTest {
            var now = 1_000L
            var calls = 0
            val repository =
                TheOtherDbRepository(MapSettings(), {
                    calls++
                    emptyList()
                }) { now }
            repository.versions(query)
            repository.versions(query)
            repository.versions(query.copy(episode = 2))
            assertEquals(2, calls)
            now += 6 * 60 * 60 * 1_000L
            repository.versions(query)
            assertEquals(3, calls)
        }

    @Test
    fun failures_back_off_but_are_not_saved_as_empty_results() =
        runTest {
            val settings = MapSettings()
            var now = 1_000L
            var calls = 0
            val repository =
                TheOtherDbRepository(settings, {
                    if (++calls ==
                        1
                    ) {
                        error("offline")
                    } else {
                        versions
                    }
                }) { now }
            assertNull(repository.versions(query))
            assertNull(repository.versions(query))
            assertEquals(1, calls)
            now += 60_000L
            assertEquals(versions, repository.versions(query))
            assertEquals(2, calls)
        }

    @Test
    fun cancellation_propagates_and_does_not_poison_cache() =
        runTest {
            var calls = 0
            val repository =
                TheOtherDbRepository(MapSettings(), {
                    if (++calls ==
                        1
                    ) {
                        throw CancellationException()
                    } else {
                        versions
                    }
                })
            assertFailsWith<CancellationException> { repository.versions(query) }
            assertEquals(versions, repository.versions(query))
        }

    @Test
    fun corrupt_cache_is_ignored() =
        runTest {
            val settings = MapSettings().apply { putString("player.skip.theotherdb.cache.v1", "broken") }
            assertEquals(versions, TheOtherDbRepository(settings, { versions }).versions(query))
        }
}
