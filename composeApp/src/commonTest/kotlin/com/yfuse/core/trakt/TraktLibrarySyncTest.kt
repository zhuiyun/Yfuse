package com.yfuse.core.trakt

import com.russhwolf.settings.MapSettings
import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.security.TestSecureStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktLibrarySyncTest {
    @Test fun listsMergeBothDirectionsAndRepeatedSyncDoesNotWriteAgain() =
        runTest {
            for (collection in listOf(PersonalCollection.Favorite, PersonalCollection.WatchLater)) {
                val f = Fixture()
                f.set(collection, 1, true)
                f.remote += movie(2)
                val first = f.sync(collection)
                assertNull(first.error)
                assertEquals(1, first.uploaded)
                assertEquals(1, first.imported)
                val repeated = f.sync(collection)
                assertEquals(0, repeated.uploaded)
                assertEquals(0, repeated.imported)
                assertEquals(1, f.writes.size)
                assertEquals(
                    2,
                    f.personal
                        .snapshot()
                        .entries
                        .count { !it.deleted },
                )
            }
        }

    @Test fun localAndRemoteListRemovalPropagateAfterRestart() =
        runTest {
            val f = Fixture()
            f.remote += listOf(movie(1), movie(2))
            f.sync(PersonalCollection.Favorite)
            f.personal.setFavorite(ref(1), false)
            f.remote.removeAll { it.movie?.ids?.tmdb == 2 }
            val result = f.sync(PersonalCollection.Favorite)
            assertNull(result.error)
            assertEquals(2, result.removed)
            assertTrue(
                f.personal.state.value.favorites
                    .isEmpty(),
            )
            assertTrue(f.remote.isEmpty())
            assertEquals(0, f.sync(PersonalCollection.Favorite).removed)
        }

    @Test fun remoteReadditionRevivesUnchangedSyncedTombstone() =
        runTest {
            val f = Fixture()
            f.set(PersonalCollection.WatchLater, 1, true)
            f.sync(PersonalCollection.WatchLater)
            f.personal.setWatchLater(ref(1), false)
            f.sync(PersonalCollection.WatchLater)
            f.remote += movie(1)
            assertEquals(1, f.sync(PersonalCollection.WatchLater).imported)
            assertEquals(1, f.personal.state.value.watchLater.size)
        }

    @Test fun unsyncedLocalEditWinsRemoteDeletionConflict() =
        runTest {
            val f = Fixture()
            f.set(PersonalCollection.Favorite, 1, true)
            f.sync(PersonalCollection.Favorite)
            f.personal.setFavorite(ref(1), false)
            f.personal.setFavorite(ref(1), true)
            f.remote.clear()
            assertEquals(1, f.sync(PersonalCollection.Favorite).uploaded)
            assertEquals(1, f.personal.state.value.favorites.size)
        }

    @Test fun firstMergeRetainsLocalDeletionDecision() =
        runTest {
            val f = Fixture()
            f.personal.setFavorite(ref(1), false)
            f.remote += movie(1)
            assertEquals(1, f.sync(PersonalCollection.Favorite).removed)
            assertTrue(
                f.personal.state.value.favorites
                    .isEmpty(),
            )
        }

    @Test fun failedPageDoesNotImportOrInferAnyDeletion() =
        runTest {
            val f = Fixture()
            f.remote += movie(1)
            f.sync(PersonalCollection.Favorite)
            f.remote.clear()
            f.pageFailure = true
            val result = f.sync(PersonalCollection.Favorite)
            assertNotNull(result.error)
            assertEquals(1, f.personal.state.value.favorites.size)
            assertEquals(0, result.removed)
        }

    @Test fun changingRemoteSnapshotDoesNotDeleteLocalList() =
        runTest {
            val f = Fixture()
            f.remote += movie(1)
            f.sync(PersonalCollection.WatchLater)
            f.remote.clear()
            f.changingActivity = true
            assertNotNull(f.sync(PersonalCollection.WatchLater).error)
            assertEquals(1, f.personal.state.value.watchLater.size)
        }

    @Test fun failedAcknowledgementIsReconciledBeforeRetryingHistory() =
        runTest {
            val f = Fixture()
            f.personal.recordHistory(ref(1), 100, 100, true)
            f.failAfterWrite = true
            assertNotNull(f.sync(PersonalCollection.History).error)
            f.failAfterWrite = false
            assertNull(f.sync(PersonalCollection.History).error)
            assertEquals(1, f.writes.size)
        }

    @Test fun historyImportsLatestWatchOnceAndPreservesIncompleteProgressAndDeletion() =
        runTest {
            val f = Fixture()
            f.personal.recordHistory(ref(1), 25, 100, false)
            f.personal.removeHistory(ref(2))
            f.remote += listOf(movie(1), movie(2), movie(3), movie(3).copy(watchedAt = "2025-01-01T00:00:00Z"))
            val result = f.sync(PersonalCollection.History)
            assertEquals(1, result.imported)
            assertEquals(0, result.uploaded)
            assertFalse(
                f.personal.state.value.history
                    .first { it.media.tmdbId == 1 }
                    .completed,
            )
            assertEquals(
                25,
                f.personal.state.value.history
                    .first { it.media.tmdbId == 1 }
                    .positionMs,
            )
            assertTrue(
                f.personal.state.value.history
                    .none { it.media.tmdbId == 2 },
            )
            assertEquals(4, f.remote.size)
        }

    @Test fun episodeIdsMatchCoordinatesWithoutUploadingSeriesAsEpisode() =
        runTest {
            val f = Fixture()
            val episode = ref(99).copy(mediaType = "Episode")
            f.personal.recordHistory(episode, 100, 100, true)
            f.remote +=
                TraktListItem(
                    "episode",
                    show = TraktTitle("Show", ids = TraktIds(tmdb = 5)),
                    episode = TraktEpisode("Episode", 2, 4, TraktIds(tmdb = 99)),
                    watchedAt = "2026-10-20T00:00:00Z",
                )
            assertEquals(0, f.sync(PersonalCollection.History).uploaded)
            assertEquals(1, f.personal.state.value.history.size)
        }

    @Test fun unsupportedItemsAndPendingScrobblesAreNotExported() =
        runTest {
            val f = Fixture()
            f.personal.recordHistory(ref(1), 100, 100, true)
            f.pending = true
            assertEquals(1, f.sync(PersonalCollection.History).skipped)
            assertTrue(f.writes.isEmpty())
            f.personal.setFavorite(ref(2).copy(mediaType = "Episode"), true)
            f.personal.setFavorite(ref(3).copy(mediaKey = "emby:private", tmdbId = null), true)
            assertEquals(2, f.sync(PersonalCollection.Favorite).skipped)
        }

    @Test fun profileSwitchWhileReadingCannotImportIntoAnotherProfile() =
        runTest {
            val f = Fixture()
            f.remote += movie(1)
            f.onRead = { f.personal.bindAccount("another-account") }
            assertFailsWith<CancellationException> { f.sync(PersonalCollection.Favorite) }
            assertTrue(
                f.personal.state.value.favorites
                    .isEmpty(),
            )
        }

    @Test fun editDuringWriteDelayIsCheckedAgainBeforeNetworkMutation() =
        runTest {
            val f = Fixture()
            f.personal.setFavorite(ref(1), true)
            f.beforeWrite = { f.personal.setFavorite(ref(1), false) }
            assertNotNull(f.sync(PersonalCollection.Favorite).error)
            assertTrue(f.writes.isEmpty())
        }

    @Test fun malformedRemoteIdentityDoesNotLookLikeADeletion() =
        runTest {
            val f = Fixture()
            f.remote += movie(1)
            f.sync(PersonalCollection.Favorite)
            f.remote.clear()
            f.remote += TraktListItem("movie", movie = TraktTitle("Broken"))
            assertNotNull(f.sync(PersonalCollection.Favorite).error)
            assertEquals(1, f.personal.state.value.favorites.size)
        }

    @Test fun storageFailureDoesNotClaimSuccessfulImport() =
        runTest {
            val f = Fixture()
            f.remote += movie(1)
            f.secure.failWrites = true
            assertNotNull(f.sync(PersonalCollection.Favorite).error)
            f.secure.failWrites = false
            assertNull(f.sync(PersonalCollection.Favorite).error)
            assertEquals(1, f.personal.state.value.favorites.size)
        }

    @Test fun encryptedCheckpointsAreChunkedAndFailedReplacementRetainsPriorSnapshot() {
        val store = TestSecureStore()
        val checkpoint = TraktSyncCheckpoint(store, "account-profile")
        val values =
            (1..1000).associate {
                "movie:tmdb:$it" to
                    TraktSyncMark(
                        com.yfuse.core.personal
                            .PersonalStamp(it.toLong(), "device"),
                        false,
                        true,
                    )
            }
        checkpoint.write(PersonalCollection.Favorite, values)
        assertEquals(values, checkpoint.read(PersonalCollection.Favorite))
        assertTrue(store.storedKeys().size > 1)
        store.failWrites = true
        assertFailsWith<IllegalStateException> { checkpoint.write(PersonalCollection.Favorite, emptyMap()) }
        assertEquals(values, checkpoint.read(PersonalCollection.Favorite))
        assertTrue(TraktSyncCheckpoint(store, "different-account").read(PersonalCollection.Favorite).isEmpty())
    }

    @Test fun unmatchedItemDoesNotBlockFollowingItemsOrBecomeAcknowledged() =
        runTest {
            val f = Fixture()
            f.set(PersonalCollection.Favorite, 1, true)
            f.set(PersonalCollection.Favorite, 2, true)
            f.unmatchedId = 1
            val first = f.sync(PersonalCollection.Favorite)
            assertNull(first.error)
            assertEquals(1, first.unmatched)
            assertEquals(1, first.uploaded)
            assertEquals(
                2,
                f.remote
                    .single()
                    .movie
                    ?.ids
                    ?.tmdb,
            )
            f.unmatchedId = null
            assertEquals(1, f.sync(PersonalCollection.Favorite).uploaded)
            assertEquals(2, f.remote.size)
        }

    @Test fun fullPersonalLibraryReportsCapacityAndRetainsRemoteAndLocalContents() =
        runTest {
            val f = Fixture()
            f.personal.mergeRemote(
                com.yfuse.core.personal.PersonalSnapshot(
                    entries =
                        (1..1000).map {
                            com.yfuse.core.personal.PersonalEntry(
                                "default",
                                PersonalCollection.History,
                                ref(it),
                                com.yfuse.core.personal
                                    .PersonalStamp(it.toLong(), "device"),
                            )
                        },
                ),
            )
            f.remote += movie(1001)
            val result = f.sync(PersonalCollection.Favorite)
            assertTrue(result.error.orEmpty().contains("1,000"))
            assertEquals(0, result.imported)
            assertEquals(
                1000,
                f.personal
                    .snapshot()
                    .entries.size,
            )
            assertEquals(1, f.remote.size)
            assertTrue(
                f.personal.state.value.favorites
                    .isEmpty(),
            )
        }

    private class Fixture {
        val personal = PersonalLibraryRepository(MapSettings(), nowEpochMs = { 1_792_454_400_000L })
        val secure = TestSecureStore()
        val remote = mutableListOf<TraktListItem>()
        val writes = mutableListOf<Boolean>()
        var pageFailure = false
        var changingActivity = false
        var failAfterWrite = false
        var pending = false
        var unmatchedId: Int? = null
        var onRead: () -> Unit = {}
        var beforeWrite: () -> Unit = {}
        private var activityCounter = 0

        fun set(
            collection: PersonalCollection,
            id: Int,
            value: Boolean,
        ) {
            if (collection == PersonalCollection.Favorite) {
                personal.setFavorite(ref(id), value)
            } else {
                personal.setWatchLater(ref(id), value)
            }
        }

        suspend fun sync(collection: PersonalCollection) =
            TraktLibrarySync(
                PersonalTraktImportSink(personal),
                TraktSyncCheckpoint(secure, "test"),
                readPage = { _, page ->
                    onRead()
                    if (pageFailure && page == 2) error("offline")
                    TraktPage(remote.toList(), pageFailure && page == 1)
                },
                activity = { if (changingActivity) (++activityCounter).toString() else "stable" },
                write = { _, media, remove, _, guard ->
                    beforeWrite()
                    guard()
                    if (media.ids.tmdb == unmatchedId) throw TraktUnmatchedItemException()
                    writes += remove
                    if (remove) {
                        remote.removeAll { it.libraryMedia()?.aliases()?.any(media.aliases()::contains) == true }
                    } else {
                        remote += movie(media.ids.tmdb!!)
                    }
                    if (failAfterWrite) error("response lost")
                },
                ensureCurrent = {},
                pendingHistory = { pending },
                progress = {},
            ).sync(collection)
    }

    companion object {
        private fun ref(id: Int) = PersonalMediaRef("tmdb:$id", "Movie $id", "Movie", tmdbId = id)

        private fun movie(id: Int) =
            TraktListItem(
                "movie",
                movie = TraktTitle("Movie $id", ids = TraktIds(tmdb = id)),
                watchedAt = "2026-10-20T00:00:00Z",
            )
    }
}
