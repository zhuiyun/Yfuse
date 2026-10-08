package com.yfuse.feature.personal

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.account.AccountSession
import com.yfuse.core.account.AccountState
import com.yfuse.core.account.AccountUser
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.personal.PersonalProfile
import com.yfuse.core.sync.PendingSyncMutation
import com.yfuse.core.sync.ServerSyncState
import com.yfuse.core.sync.ServerSyncStatus
import com.yfuse.core.sync.SyncConflict
import com.yfuse.core.sync.SyncMutationKind
import com.yfuse.core.sync.playback.PlaybackCloudSyncState
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalCenterStoreTest {
    // The repository's profile and PIN work runs on Dispatchers.Default; a queued Main brings each
    // result back onto the test's thread, where the store expects it.
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun an_adult_profile_switches_at_once() =
        runTest(scheduler) {
            val personal = PersonalLibraryRepository(MapSettings())
            val other = personal.newProfile("客厅")
            val store = store(personal)

            store.accept(PersonalCenterIntent.SwitchProfile(other))

            store.await { it.library.activeProfile.id == other.id }
            assertNull(store.state.switching)
            assertNull(store.state.failure)
            store.dispose()
        }

    @Test
    fun a_child_profile_switches_only_with_the_guardian_pin() =
        runTest(scheduler) {
            val personal = PersonalLibraryRepository(MapSettings())
            personal.setGuardianPin(PIN.toCharArray()).getOrThrow()
            val adult = personal.state.value.activeProfile
            personal.switchProfile(personal.newProfile("小明", child = true).id).getOrThrow()
            val store = store(personal)

            store.accept(PersonalCenterIntent.SwitchProfile(adult))
            assertEquals(adult, store.state.switching)

            store.accept(PersonalCenterIntent.ConfirmSwitch(adult.id, "0000"))
            assertEquals("PIN 不正确", store.await { it.dialogError != null }.dialogError)
            assertEquals(adult, store.state.switching)
            assertTrue(store.state.library.activeProfile.child)

            store.accept(PersonalCenterIntent.ConfirmSwitch(adult.id, PIN))
            store.await { it.switching == null && it.library.activeProfile.id == adult.id }
            store.dispose()
        }

    @Test
    fun a_saved_profile_closes_the_editor_and_a_refused_one_says_why_inside_it() =
        runTest(scheduler) {
            val personal = PersonalLibraryRepository(MapSettings())
            val store = store(personal)

            store.accept(PersonalCenterIntent.EditProfile(null))
            assertEquals(ProfileEditorTarget(null), store.state.editor)

            store.accept(PersonalCenterIntent.SaveProfile(null, " ", child = false, serverIds = emptySet()))
            assertTrue(store.state.busy)
            assertEquals("资料名称需为 1–40 个字", store.await { it.dialogError != null }.dialogError)
            assertEquals(ProfileEditorTarget(null), store.state.editor)
            assertFalse(store.state.busy)

            store.accept(PersonalCenterIntent.SaveProfile(null, "客厅", child = false, serverIds = emptySet()))
            assertNull(store.state.dialogError)
            store.await { it.editor == null && it.library.profiles.any { profile -> profile.name == "客厅" } }
            assertFalse(store.state.busy)
            store.dispose()
        }

    @Test
    fun the_guardian_pin_dialog_closes_with_a_note_once_the_pin_is_saved() =
        runTest(scheduler) {
            val personal = PersonalLibraryRepository(MapSettings())
            val store = store(personal)

            store.accept(PersonalCenterIntent.EditGuardianPin)
            assertTrue(store.state.settingPin)

            store.accept(PersonalCenterIntent.SaveGuardianPin(currentPin = "", newPin = "12"))
            assertEquals("PIN 需为 6–12 位数字", store.await { it.dialogError != null }.dialogError)
            assertTrue(store.state.settingPin)

            store.accept(PersonalCenterIntent.SaveGuardianPin(currentPin = "", newPin = PIN))
            val saved = store.await { !it.settingPin && it.library.hasGuardianPin }
            assertEquals("家长 PIN 已保存", saved.message)
            assertNull(saved.dialogError)
            store.dispose()
        }

    @Test
    fun removing_a_profile_asks_first_and_keeping_it_leaves_it_there() =
        runTest(scheduler) {
            val personal = PersonalLibraryRepository(MapSettings())
            val other = personal.newProfile("客厅")
            val store = store(personal)

            store.accept(PersonalCenterIntent.AskRemoveProfile(other))
            assertEquals(other, store.state.removingProfile)
            store.accept(PersonalCenterIntent.KeepProfile)
            assertNull(store.state.removingProfile)
            runCurrent()
            assertTrue(personal.profiles.any { it.id == other.id })

            store.accept(PersonalCenterIntent.AskRemoveProfile(other))
            store.accept(PersonalCenterIntent.RemoveProfile(other))
            assertNull(store.state.removingProfile)
            store.await { state -> state.library.profiles.none { it.id == other.id } }
            store.dispose()
        }

    @Test
    fun a_failed_action_stays_on_the_page_and_retry_runs_it_again() =
        runTest(scheduler) {
            val sync = FakePersonalSync().apply { personalSync = Result.failure(IllegalStateException("网络不可用")) }
            val store = store(PersonalLibraryRepository(MapSettings()), sync)

            store.accept(PersonalCenterIntent.SyncNow)
            val failed = store.await { it.failure != null }
            assertEquals("网络不可用", failed.failure)
            assertNull(failed.message)

            sync.personalSync = Result.success(Unit)
            store.accept(PersonalCenterIntent.RetryFailure)
            assertNull(store.state.failure)
            assertEquals("个人数据已同步", store.await { it.message != null }.message)
            assertEquals(2, sync.personalSyncs)
            store.dispose()
        }

    @Test
    fun a_removed_record_leaves_the_list_at_once_and_the_profile_when_its_toast_goes() =
        runTest(scheduler) {
            val personal = PersonalLibraryRepository(MapSettings())
            personal.setFavorite(MEDIA, true)
            val entry = personal.favorites.single()
            val store = store(personal)

            store.accept(PersonalCenterIntent.RemoveEntry(entry))
            assertEquals(entry, store.state.pendingRemoval)
            assertEquals(1, store.state.removalGeneration)
            assertEquals(1, personal.favorites.size)

            store.accept(PersonalCenterIntent.UndoRemoval(entry))
            assertNull(store.state.pendingRemoval)
            assertEquals(1, personal.favorites.size)

            store.accept(PersonalCenterIntent.RemoveEntry(entry))
            assertEquals(2, store.state.removalGeneration)
            store.accept(PersonalCenterIntent.SettleRemoval)
            assertNull(store.state.pendingRemoval)
            assertTrue(personal.favorites.isEmpty())
            store.dispose()
        }

    @Test
    fun a_page_that_closes_commits_the_record_still_waiting_for_its_undo() =
        runTest(scheduler) {
            val personal = PersonalLibraryRepository(MapSettings())
            personal.setFavorite(MEDIA, true)
            val store = store(personal)

            store.accept(PersonalCenterIntent.RemoveEntry(personal.favorites.single()))
            store.dispose()

            assertTrue(personal.favorites.isEmpty())
        }

    @Test
    fun the_sync_rows_follow_the_account_and_start_each_sync_again() =
        runTest(scheduler) {
            val sync = FakePersonalSync()
            val store = store(PersonalLibraryRepository(MapSettings()), sync)
            assertFalse(store.state.signedIn)

            sync.account.value = SIGNED_IN
            store.await { it.signedIn }

            store.accept(PersonalCenterIntent.RefreshPlaybackSync)
            store.accept(PersonalCenterIntent.RetryServerSync)
            store.accept(PersonalCenterIntent.ResolveConflict(conflict("s1"), keepLocal = true))
            runCurrent()
            assertEquals(1, sync.playbackRefreshes)
            assertEquals(1, sync.serverSyncs)
            assertEquals(listOf(conflict("s1") to true), sync.resolved)
            store.dispose()
        }

    @Test
    fun the_server_sync_shows_only_what_the_active_profile_may_see() =
        runTest(scheduler) {
            val personal = PersonalLibraryRepository(MapSettings())
            val linked = personal.newProfile("客厅", serverIds = setOf("s1"))
            val sync = FakePersonalSync()
            sync.servers.value =
                ServerSyncState(
                    statuses = listOf(ServerSyncStatus("s1", "书房"), ServerSyncStatus("s2", "客厅")),
                    conflicts = listOf(conflict("s1"), conflict("s2")),
                )
            val store = store(personal, sync)
            assertEquals(listOf("s1", "s2"), store.state.serverStatuses.map { it.serverId })

            personal.switchProfile(linked.id).getOrThrow()

            val narrowed = store.await { state -> state.serverStatuses.map { it.serverId } == listOf("s1") }
            assertEquals(listOf(conflict("s1")), narrowed.serverConflicts)
            // 冲突 N 项 still counts every conflict the sync holds.
            assertEquals(2, narrowed.serverSync.conflicts.size)
            store.dispose()
        }

    @Test
    fun importing_the_servers_lists_reports_what_it_merged() =
        runTest(scheduler) {
            val server = SavedServer("one", "http://one", "One", "u", "User", "token")
            val store =
                store(
                    PersonalLibraryRepository(MapSettings()),
                    repo = testRepo { json("""{"Items":[],"TotalRecordCount":0}""") },
                )
            assertTrue(store.state.canImport)

            store.accept(PersonalCenterIntent.ImportFromServers(listOf(server)))

            val imported = store.await { it.message != null }
            assertEquals("已导入 0 项，现有个人选择已保留", imported.message)
            assertFalse(store.await { !it.busy }.busy)
            store.dispose()
        }

    @Test
    fun without_the_servers_lists_there_is_nothing_to_import() =
        runTest(scheduler) {
            val store = store(PersonalLibraryRepository(MapSettings()))

            assertFalse(store.state.canImport)
            store.accept(PersonalCenterIntent.ImportFromServers(emptyList()))
            runCurrent()
            assertFalse(store.state.busy)
            assertNull(store.state.message)
            store.dispose()
        }

    private fun store(
        personal: PersonalLibraryRepository,
        sync: FakePersonalSync = FakePersonalSync(),
        repo: EmbyRepository? = null,
    ): Store<PersonalCenterIntent, PersonalCenterState, Nothing> =
        PersonalCenterStoreFactory(DefaultStoreFactory(), personal, sync, repo).create()

    private suspend fun Store<*, PersonalCenterState, *>.await(
        predicate: (PersonalCenterState) -> Boolean,
    ): PersonalCenterState = states.first(predicate)

    private suspend fun PersonalLibraryRepository.newProfile(
        name: String,
        child: Boolean = false,
        serverIds: Set<String> = emptySet(),
    ): PersonalProfile {
        saveProfile(name = name, child = child, serverIds = serverIds).getOrThrow()
        return profiles.first { it.name == name }
    }

    private val PersonalLibraryRepository.profiles get() = state.value.profiles

    private val PersonalLibraryRepository.favorites get() = state.value.favorites

    private fun conflict(serverId: String) =
        SyncConflict(
            PendingSyncMutation(serverId, "item", "黑客帝国", SyncMutationKind.Favorite, true, false, 0L),
            serverValue = false,
        )

    private class FakePersonalSync : PersonalSync {
        override val account = MutableStateFlow<AccountState>(AccountState.SignedOut)
        override val playback = MutableStateFlow(PlaybackCloudSyncState())
        override val servers = MutableStateFlow(ServerSyncState())
        var personalSync: Result<Unit> = Result.success(Unit)
        var personalSyncs = 0
        var playbackRefreshes = 0
        var serverSyncs = 0
        val resolved = mutableListOf<Pair<SyncConflict, Boolean>>()

        override suspend fun syncPersonalNow(): Result<Unit> {
            personalSyncs++
            return personalSync
        }

        override fun refreshPlayback() {
            playbackRefreshes++
        }

        override suspend fun syncServers() {
            serverSyncs++
        }

        override suspend fun resolveConflict(
            conflict: SyncConflict,
            keepLocal: Boolean,
        ): Result<Unit> {
            resolved += conflict to keepLocal
            return Result.success(Unit)
        }
    }

    private companion object {
        const val PIN = "583921"
        val MEDIA = PersonalMediaRef("tmdb:603", "黑客帝国", "Movie", tmdbId = 603)
        val SIGNED_IN =
            AccountState.SignedIn(
                AccountSession(AccountUser("u", "zhuiyun", "追云", 0, 0L, 0L), "token", 0L, 0L),
            )
    }
}
