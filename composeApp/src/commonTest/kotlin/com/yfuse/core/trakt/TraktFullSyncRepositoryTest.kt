package com.yfuse.core.trakt

import com.russhwolf.settings.MapSettings
import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.security.TestSecureStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktFullSyncRepositoryTest {
    @Test fun oneClickSyncsThreeCollectionsAndBlocksRepeatedClicks() =
        runTest {
            val f = fixture()
            f.repository.syncLibrary()
            f.repository.syncLibrary()
            assertTrue(f.repository.state.value.busy)
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(PersonalCollection.entries.toSet(), f.api.writes.toSet())
            assertEquals(3, f.api.writes.size)
            assertEquals(3, f.repository.state.value.syncResults.size)
            assertNotNull(f.repository.state.value.lastLibrarySyncAt)
            assertFalse(f.repository.state.value.busy)
            assertFalse(f.repository.state.value.scrobbling)
        }

    @Test fun partialFailureStillSyncsOtherCollectionsAndDoesNotSetFullSuccessTime() =
        runTest {
            val f = fixture()
            f.api.favoriteFailure = true
            f.repository.syncLibrary()
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(setOf(PersonalCollection.WatchLater, PersonalCollection.History), f.api.writes.toSet())
            assertNotNull(
                f.repository.state.value.syncResults
                    .first()
                    .error,
            )
            assertNull(f.repository.state.value.lastLibrarySyncAt)
            assertNotNull(f.repository.state.value.error)
        }

    @Test fun disconnectDuringReadCancelsWorkAndCannotPublishOldResults() =
        runTest {
            val f = fixture()
            val gate = CompletableDeferred<Unit>()
            f.api.gate = gate
            f.repository.syncLibrary()
            runCurrent()
            f.repository.disconnect()
            gate.complete(Unit)
            runCurrent()
            assertTrue(f.api.writes.isEmpty())
            assertFalse(f.repository.state.value.connected)
            assertTrue(
                f.repository.state.value.syncResults
                    .isEmpty(),
            )
        }

    @Test fun synchronousPersonalSwitchCannotSendDataUnderStaleOwnerFlow() =
        runTest {
            val f = fixture()
            f.personal.bindAccount("different-account")
            f.repository.syncLibrary()
            runCurrent()
            assertTrue(f.api.writes.isEmpty())
        }

    @Test fun cancelRetainsCompletedCategoryAndAllowsRetry() =
        runTest {
            val f = fixture()
            f.repository.syncLibrary()
            runCurrent()
            assertEquals(listOf(PersonalCollection.Favorite), f.api.writes)
            f.repository.cancelLibrarySync()
            runCurrent()
            assertFalse(f.repository.state.value.busy)
            assertNull(f.repository.state.value.lastLibrarySyncAt)
            assertEquals(1, f.repository.state.value.syncResults.size)
        }

    @Test fun rateLimitAppliesToAllCategoriesAndCannotBeBypassedByRepeatedClick() =
        runTest {
            val f = fixture()
            f.api.rateLimited = true
            f.repository.syncLibrary()
            runCurrent()
            assertEquals(1, f.api.activityCalls)
            assertTrue(
                f.repository.state.value.syncResults
                    .all { it.error != null },
            )
            f.repository.syncLibrary()
            runCurrent()
            assertEquals(1, f.api.activityCalls)
            assertTrue(f.repository.state.value.libraryRetryAtEpochMs > NOW + testScheduler.currentTime)
            f.api.rateLimited = false
            advanceTimeBy(61_000)
            f.repository.syncLibrary()
            advanceTimeBy(3_000)
            runCurrent()
            assertNotNull(f.repository.state.value.lastLibrarySyncAt)
        }

    @Test fun stalePersonalOwnerCannotCaptureOrRecordPlayback() =
        runTest {
            val f = fixture()
            f.repository.setScrobbling(true)
            val owner = f.repository.capturePlaybackOwner()
            f.personal.bindAccount("different-account")
            assertNull(f.repository.capturePlaybackOwner())
            f.repository.recordPlayback(
                TraktPlaybackMedia("movie", TraktIds(tmdb = 1)),
                "session",
                TraktPlaybackAction.Stop,
                100,
                100,
                owner,
            )
            runCurrent()
            assertEquals(0, f.repository.state.value.pending)
        }

    private fun TestScope.fixture(): Fixture {
        val personal = PersonalLibraryRepository(MapSettings(), nowEpochMs = { NOW + testScheduler.currentTime })
        personal.bindAccount("account")
        val ref = PersonalMediaRef("tmdb:1", "Film", "Movie", tmdbId = 1)
        personal.setFavorite(ref, true)
        personal.setWatchLater(ref, true)
        personal.recordHistory(ref, 100, 100, true)
        val api = Api()
        val repository =
            TraktRepository(
                api,
                TraktRepositoryTest.FakeAuth { NOW + testScheduler.currentTime },
                TestSecureStore(),
                MutableStateFlow("account:default"),
                PersonalTraktImportSink(personal),
                backgroundScope,
                now = { NOW + testScheduler.currentTime },
            )
        repository.start()
        runCurrent()
        repository.connect(true)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(repository.state.value.connected)
        return Fixture(personal, api, repository)
    }

    private data class Fixture(
        val personal: PersonalLibraryRepository,
        val api: Api,
        val repository: TraktRepository,
    )

    private class Api : TraktApi {
        val writes = mutableListOf<PersonalCollection>()
        var favoriteFailure = false
        var rateLimited = false
        var activityCalls = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun libraryActivity(
            clientId: String,
            token: String,
        ): String {
            activityCalls++
            if (rateLimited) throw TraktApiException(429, 60)
            gate?.await()
            return "stable"
        }

        override suspend fun favorites(
            clientId: String,
            token: String,
            page: Int,
        ): TraktPage {
            if (favoriteFailure) error("offline")
            return TraktPage(emptyList(), false)
        }

        override suspend fun watchlist(
            clientId: String,
            token: String,
            page: Int,
        ) = TraktPage(emptyList(), false)

        override suspend fun history(
            clientId: String,
            token: String,
            page: Int,
            endAt: String,
        ) = TraktPage(emptyList(), false)

        override suspend fun writeLibrary(
            clientId: String,
            token: String,
            collection: PersonalCollection,
            media: TraktLibraryMedia,
            remove: Boolean,
            watchedAt: Long?,
        ) {
            writes += collection
        }

        override suspend fun scrobble(
            clientId: String,
            token: String,
            media: TraktPlaybackMedia,
            action: TraktPlaybackAction,
            progress: Float,
        ) {}
    }

    companion object {
        private const val NOW = 1_700_000_000_000L
    }
}
