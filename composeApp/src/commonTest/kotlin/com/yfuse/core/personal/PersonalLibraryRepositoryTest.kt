package com.yfuse.core.personal

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.sync.playback.PlaybackMutationKind
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.core.sync.playback.PlaybackSyncTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalLibraryRepositoryTest {
    private val media = PersonalMediaRef("tmdb:603", "黑客帝国", "Movie", tmdbId = 603)

    @Test
    fun concurrentAdditionsMergeAndOfflineCopiesCannotResurrectDeletedItems() {
        val phone = PersonalLibraryRepository(MapSettings())
        val tablet = PersonalLibraryRepository(MapSettings())
        phone.setFavorite(media, true)
        tablet.setWatchLater(media.copy(mediaKey = "tmdb:604", title = "续集"), true)
        val left = mergePersonalSnapshots(phone.snapshot(), tablet.snapshot())
        val right = mergePersonalSnapshots(tablet.snapshot(), phone.snapshot())
        assertEquals(left, right)
        tablet.mergeRemote(left)
        tablet.setFavorite(media, false)
        phone.mergeRemote(tablet.snapshot())
        assertTrue(
            phone.state.value.favorites
                .isEmpty(),
        )
        phone.mergeRemote(left)
        assertTrue(
            phone.state.value.favorites
                .isEmpty(),
        )
        assertEquals(1, phone.state.value.watchLater.size)
        assertFalse(phone.importFavorite(media))
    }

    @Test
    fun accountsAndProfilesRetainSeparateListsAndCalendarAcrossRestart() =
        runTest {
            val settings = MapSettings()
            val personal = PersonalLibraryRepository(settings)
            val calendar = CalendarFollowStore(settings, personal)
            personal.bindAccount("alice")
            personal.setFavorite(media, true)
            calendar.follow(FollowedSeries(1, "主资料追剧"))
            personal.saveProfile(name = "另一位", child = false, serverIds = emptySet()).getOrThrow()
            val other =
                personal.state.value.profiles
                    .last()
                    .id
            personal.switchProfile(other).getOrThrow()
            assertTrue(
                personal.state.value.favorites
                    .isEmpty(),
            )
            assertTrue(calendar.followed.value.isEmpty())
            calendar.follow(FollowedSeries(2, "另一个追剧"))
            personal.bindAccount("bob")
            assertTrue(
                personal.state.value.favorites
                    .isEmpty(),
            )
            assertTrue(calendar.followed.value.isEmpty())
            personal.bindAccount("alice")
            assertEquals(listOf(2), calendar.followed.value.map { it.tmdbId })
            personal.switchProfile(DEFAULT_PERSONAL_PROFILE).getOrThrow()
            assertEquals(
                media,
                personal.state.value.favorites
                    .single()
                    .media,
            )
            assertEquals(listOf(1), calendar.followed.value.map { it.tmdbId })
            assertEquals(personal.snapshot(), PersonalLibraryRepository(settings).snapshot())
        }

    @Test
    fun childAccessIsClosedUntilLinkedAndPinCannotBeBypassedBySignOutOrBadAttempts() =
        runTest {
            var now = 1_000L
            val settings = MapSettings()
            val personal = PersonalLibraryRepository(settings, nowEpochMs = { now })
            personal.bindAccount("owner")
            val registry = ServerRegistry(settings, TestSecureStore(), personal = personal)
            val adult = SavedServer("adult", "https://media.example", "影院", "adult", "家长", "token")
            val kid = adult.copy(id = "kid", userId = "kid", userName = "儿童")
            registry.addOrUpdate(adult)
            registry.addOrUpdate(kid)
            assertTrue(personal.saveProfile(name = "孩子", child = true, serverIds = setOf(kid.id)).isFailure)
            personal.setGuardianPin("583921".toCharArray()).getOrThrow()
            personal.saveProfile(name = "孩子", child = true, serverIds = setOf(kid.id)).getOrThrow()
            val child =
                personal.state.value.profiles
                    .last()
                    .id
            personal.switchProfile(child).getOrThrow()
            assertEquals(
                listOf(kid.id),
                registry.data.value.servers
                    .map { it.id },
            )
            assertNull(registry.serverById(adult.id))
            assertEquals(2, registry.allDataForSync().servers.size)
            assertFailsWith<IllegalStateException> { registry.addOrUpdate(adult) }
            assertTrue(registry.exportProtectedBackup("secret-password".toCharArray(), 1_000).isFailure)
            personal.bindAccount(null)
            assertEquals(child, personal.activeProfileId)
            repeat(5) { assertTrue(personal.switchProfile(DEFAULT_PERSONAL_PROFILE, "0000".toCharArray()).isFailure) }
            assertTrue(personal.switchProfile(DEFAULT_PERSONAL_PROFILE, "583921".toCharArray()).isFailure)
            now += 60_001
            personal.switchProfile(DEFAULT_PERSONAL_PROFILE, "583921".toCharArray()).getOrThrow()
            assertEquals(2, registry.data.value.servers.size)
            assertFalse(
                settings.keys.any {
                    runCatching { settings.getStringOrNull(it) }.getOrNull()?.contains("\"583921\"") == true
                },
            )
        }

    @Test
    fun delayedOldProfileResultCannotAddHistoryFollowsOrNotifyNewProfile() =
        runTest {
            val settings = MapSettings()
            val personal = PersonalLibraryRepository(settings)
            val calendar = CalendarFollowStore(settings, personal)
            val oldToken = personal.scopeToken
            val oldReminderSettings = calendar.reminderSettings()
            val response = CompletableDeferred<Unit>()
            var notifications = 0
            val pending =
                async {
                    response.await()
                    personal.recordHistory(media, 10_000, 100_000, false, oldToken)
                    calendar.runInScope(oldToken) {
                        calendar.follow(FollowedSeries(3, "旧资料返回"))
                        notifications++
                    }
                }
            personal.saveProfile(name = "新资料", child = false, serverIds = emptySet()).getOrThrow()
            personal
                .switchProfile(
                    personal.state.value.profiles
                        .last()
                        .id,
                ).getOrThrow()
            response.complete(Unit)
            assertFalse(pending.await())
            assertEquals(0, notifications)
            assertTrue(
                personal.state.value.history
                    .isEmpty(),
            )
            assertTrue(calendar.followed.value.isEmpty())
            oldReminderSettings.putBoolean("calendar.reminder.sent.test", true)
            assertFalse(calendar.reminderSettings().getBoolean("calendar.reminder.sent.test", false))
        }

    @Test
    fun playbackKeysAndAcknowledgementsRemainAttachedToTheirOriginalProfile() =
        runTest {
            val personal = PersonalLibraryRepository(MapSettings())
            val store = PlaybackSyncStore(MapSettings(), nowEpochMs = { 1_000 }, personal = personal)
            store.updatePlayback(
                media.mediaKey,
                emptyList(),
                12_000,
                100_000,
                false,
                "session",
                "server",
                "item",
                PlaybackMutationKind.AutoProgress,
                PlaybackSyncTrigger.Pause,
            )
            val old = store.find(media.mediaKey)!!
            personal.saveProfile(name = "平板用户", child = false, serverIds = emptySet()).getOrThrow()
            personal
                .switchProfile(
                    personal.state.value.profiles
                        .last()
                        .id,
                ).getOrThrow()
            assertNull(store.find(media.mediaKey))
            store.updatePlayback(
                media.mediaKey,
                emptyList(),
                2_000,
                100_000,
                false,
                "new-session",
                "server",
                "item",
                PlaybackMutationKind.AutoProgress,
                PlaybackSyncTrigger.Pause,
            )
            val fresh = store.find(media.mediaKey)!!
            assertNotEquals(old.document.state.profileId, fresh.document.state.profileId)
            store.markUploaded(
                media.mediaKey,
                emptyList(),
                "server",
                "opaque",
                old.mutationId,
                9,
                old.document.state.profileId,
            )
            assertTrue(store.find(media.mediaKey)!!.dirty)
            personal.switchProfile(DEFAULT_PERSONAL_PROFILE).getOrThrow()
            assertFalse(store.find(media.mediaKey)!!.dirty)
            assertEquals(
                12_000L,
                store
                    .find(media.mediaKey)!!
                    .document.state.positionMs,
            )
        }

    @Test
    fun changesDuringUploadRemainPendingAndTraktImportPreservesLocalHistory() {
        val personal = PersonalLibraryRepository(MapSettings())
        personal.setWatchLater(media, true)
        val sending = personal.snapshot()
        personal.setFavorite(media, true)
        personal.finishSync(sending, 1_000)
        assertTrue(personal.state.value.pendingSync)
        personal.recordHistory(media, 30_000, 100_000, false)
        assertFalse(personal.importWatched(media, 20))
        assertFalse(
            personal.state.value.history
                .single()
                .completed,
        )
        personal.removeHistory(media)
        assertFalse(personal.importWatched(media, 30))
    }

    @Test
    fun serverLocalIdsRemainDistinctAndMalformedVersionsCannotPoisonMerge() {
        val personal = PersonalLibraryRepository(MapSettings())
        val local = media.copy(mediaKey = "emby:1", tmdbId = null, serverId = "server-a")
        personal.setFavorite(local, true)
        personal.setFavorite(local.copy(title = "不同作品", serverId = "server-b"), true)
        assertEquals(2, personal.state.value.favorites.size)
        val malicious =
            personal.snapshot().let { snapshot ->
                snapshot.copy(
                    entries = snapshot.entries.map { it.copy(stamp = PersonalStamp(Long.MAX_VALUE, "remote")) },
                )
            }
        assertFailsWith<IllegalArgumentException> { personal.mergeRemote(malicious) }
        personal.setFavorite(media, true)
        assertEquals(3, personal.state.value.favorites.size)
    }

    @Test
    fun anotherProfileDoesNotInheritSharedMediaServerProgress() =
        runTest {
            val personal = PersonalLibraryRepository(MapSettings())
            val store = PlaybackSyncStore(MapSettings(), personal = personal)
            assertTrue(store.seedServerProgressIfAbsent("server", "item", 50_000, true))
            personal.saveProfile(name = "另一个资料", child = false, serverIds = emptySet()).getOrThrow()
            personal
                .switchProfile(
                    personal.state.value.profiles
                        .last()
                        .id,
                ).getOrThrow()
            assertFalse(store.seedServerProgressIfAbsent("server", "item", 50_000, true))
            assertFalse(store.absorbServerProgress("server", "item", 80_000, true))
            assertNull(store.stateForServerItem("server", "item"))
            personal.switchProfile(DEFAULT_PERSONAL_PROFILE).getOrThrow()
            assertEquals(50_000L, store.stateForServerItem("server", "item")?.positionMs)
        }

    @Test
    fun serverFanOutWaitsForItsOwningProfileWithoutBlockingAnotherProfile() =
        runTest {
            val personal = PersonalLibraryRepository(MapSettings())
            val store = PlaybackSyncStore(MapSettings(), personal = personal)
            store.seedServerProgressIfAbsent("server", "item", 5_000, false)
            val original = store.find("emby:item", serverId = "server")!!.document
            store.enqueueServerApply(original, listOf("server"))
            assertEquals(1, store.pendingServerApplies(0).size)
            personal.saveProfile(name = "另一人", child = false, serverIds = emptySet()).getOrThrow()
            personal
                .switchProfile(
                    personal.state.value.profiles
                        .last()
                        .id,
                ).getOrThrow()
            assertTrue(store.pendingServerApplies(0).isEmpty())
            assertNull(store.nextServerApplyAtEpochMs())
            val other = original.copy(state = original.state.copy(profileId = personal.activeProfileId))
            store.enqueueServerApply(other, listOf("server"))
            val task = store.pendingServerApplies(0).single()
            assertEquals(personal.activeProfileId, task.document.state.profileId)
            store.markServerApplySucceeded(task.id, "server")
            personal.switchProfile(DEFAULT_PERSONAL_PROFILE).getOrThrow()
            assertEquals(original, store.pendingServerApplies(0).single().document)
            assertEquals(1, store.serverApplyCount())
        }

    @Test
    fun fullLibraryRejectsNewWritesWithoutCrashingOrDiscardingItsSnapshot() {
        val settings = MapSettings()
        val personal = PersonalLibraryRepository(settings)
        val full =
            PersonalSnapshot(
                entries =
                    (1..1_000).map { index ->
                        PersonalEntry(
                            DEFAULT_PERSONAL_PROFILE,
                            PersonalCollection.Favorite,
                            media.copy(mediaKey = "tmdb:${index + 10_000}", tmdbId = index + 10_000),
                            PersonalStamp(index.toLong(), "seed"),
                        )
                    },
                follows =
                    (1..1_000).map { index ->
                        PersonalFollow(
                            DEFAULT_PERSONAL_PROFILE,
                            FollowedSeries(index, "剧 $index"),
                            PersonalStamp(index.toLong(), "seed"),
                        )
                    },
            )
        personal.mergeRemote(full)
        val original = personal.snapshot()
        assertFalse(personal.setFavorite(media, true))
        assertFalse(personal.setWatchLater(media, true))
        personal.recordHistory(media, 1_000, 10_000, false)
        val calendar = CalendarFollowStore(settings, personal)
        calendar.follow(FollowedSeries(2_000, "超出容量的追剧"))
        assertEquals(original, personal.snapshot())
        assertEquals(1_000, calendar.followed.value.size)
        assertTrue(
            personal.state.value.error
                ?.contains("个人数据过多") == true,
        )
        val incoming =
            PersonalSnapshot(
                entries =
                    listOf(
                        PersonalEntry(
                            DEFAULT_PERSONAL_PROFILE,
                            PersonalCollection.WatchLater,
                            media,
                            PersonalStamp(2_000, "remote"),
                        ),
                    ),
            )
        assertFailsWith<IllegalArgumentException> { personal.mergeRemote(incoming) }
        assertEquals(original, personal.snapshot())
        assertEquals(original, PersonalLibraryRepository(settings).snapshot())
    }
}
