package com.yfuse.feature.personal

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.designsystem.UndoWindow
import com.yfuse.core.model.SavedServer
import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalEntry
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalLibraryState
import com.yfuse.core.personal.PersonalProfile
import com.yfuse.core.personal.importServerCollections
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.ServerSyncState
import com.yfuse.core.sync.ServerSyncStatus
import com.yfuse.core.sync.SyncConflict
import com.yfuse.core.sync.playback.PlaybackCloudSyncState
import com.yfuse.core.sync.playback.PlaybackSyncManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The account and the syncs the personal pages report on and start again — [AccountRepository],
 * [PlaybackSyncManager] and [ServerSyncManager] in the app, see the function of the same name.
 */
interface PersonalSync {
    val account: StateFlow<AccountState>
    val playback: StateFlow<PlaybackCloudSyncState>
    val servers: StateFlow<ServerSyncState>

    suspend fun syncPersonalNow(): Result<Unit>

    fun refreshPlayback()

    /** 重试服务器同步: every server, past the per-server hold-off — a sync asked for by name. */
    suspend fun syncServers()

    suspend fun resolveConflict(
        conflict: SyncConflict,
        keepLocal: Boolean,
    ): Result<Unit>
}

fun PersonalSync(
    accountRepository: AccountRepository,
    playbackSync: PlaybackSyncManager,
    serverSync: ServerSyncManager,
): PersonalSync =
    object : PersonalSync {
        override val account = accountRepository.state
        override val playback = playbackSync.state
        override val servers = serverSync.state

        override suspend fun syncPersonalNow() = accountRepository.syncPersonalNow()

        override fun refreshPlayback() = playbackSync.refreshNow()

        override suspend fun syncServers() = serverSync.syncAll(force = true)

        override suspend fun resolveConflict(
            conflict: SyncConflict,
            keepLocal: Boolean,
        ) = serverSync.resolveConflict(conflict, keepLocal)
    }

/** Whose profile 新建/编辑家庭资料 is open on; a null [profile] is a new one. */
data class ProfileEditorTarget(
    val profile: PersonalProfile?,
)

/** What 我的内容, 家庭资料 and 同步状态与恢复 show. */
data class PersonalCenterState(
    val library: PersonalLibraryState = PersonalLibraryState(),
    /** 立即同步 and 刷新与重试 need a signed-in Yfuse account. */
    val signedIn: Boolean = false,
    val playbackSync: PlaybackCloudSyncState = PlaybackCloudSyncState(),
    val serverSync: ServerSyncState = ServerSyncState(),
    /** [serverSync]'s servers, and its conflicts, that the active profile may see. */
    val serverStatuses: List<ServerSyncStatus> = emptyList(),
    val serverConflicts: List<SyncConflict> = emptyList(),
    /** 从媒体服务器导入 reads the servers' own lists, and is only offered where it can. */
    val canImport: Boolean = false,
    /** A profile is being saved, or lists imported; the rows that would start another wait. */
    val busy: Boolean = false,
    /** What the last action that went through has to say. */
    val message: String? = null,
    /**
     * A failed action stays on the page in the error colour with 重试 beside it, instead of passing
     * as a caption in the grey of the page's own notes. A dialog tells its own failure inside it.
     */
    val failure: String? = null,
    val dialogError: String? = null,
    val editor: ProfileEditorTarget? = null,
    /** The profile a child profile asked to switch to, waiting for the 家长 PIN. */
    val switching: PersonalProfile? = null,
    val settingPin: Boolean = false,
    // Both removals used to run on the first tap, and neither has a way back on this page.
    val removingProfile: PersonalProfile? = null,
    /** The record waiting on its 撤销: already gone from the list, still in the profile. */
    val pendingRemoval: PersonalEntry? = null,
    /** One more for every removal, so each gets a toast of its own even when two read alike. */
    val removalGeneration: Int = 0,
)

sealed interface PersonalCenterIntent {
    /** 切换到此资料: at once, or through the 家长 PIN when a child profile is the one in use. */
    data class SwitchProfile(
        val profile: PersonalProfile,
    ) : PersonalCenterIntent

    /** The 家长 PIN given for the switch [SwitchProfile] asked for. */
    data class ConfirmSwitch(
        val profileId: String,
        val pin: String,
    ) : PersonalCenterIntent

    data object DismissSwitch : PersonalCenterIntent

    /** 新建家庭资料 for a null [profile], 编辑资料 otherwise. */
    data class EditProfile(
        val profile: PersonalProfile?,
    ) : PersonalCenterIntent

    data class SaveProfile(
        val profileId: String?,
        val name: String,
        val child: Boolean,
        val serverIds: Set<String>,
    ) : PersonalCenterIntent

    data object DismissEditor : PersonalCenterIntent

    /** 设置家长 PIN or 修改家长 PIN. */
    data object EditGuardianPin : PersonalCenterIntent

    data class SaveGuardianPin(
        val currentPin: String,
        val newPin: String,
    ) : PersonalCenterIntent

    data object DismissGuardianPin : PersonalCenterIntent

    /** 移除资料: asks before anything is removed. */
    data class AskRemoveProfile(
        val profile: PersonalProfile,
    ) : PersonalCenterIntent

    data class RemoveProfile(
        val profile: PersonalProfile,
    ) : PersonalCenterIntent

    data object KeepProfile : PersonalCenterIntent

    /** 立即同步. */
    data object SyncNow : PersonalCenterIntent

    /** 刷新与重试: pulls the latest playback progress. */
    data object RefreshPlaybackSync : PersonalCenterIntent

    data object RetryServerSync : PersonalCenterIntent

    /** 保留本机 when [keepLocal], 采用服务器 otherwise. */
    data class ResolveConflict(
        val conflict: SyncConflict,
        val keepLocal: Boolean,
    ) : PersonalCenterIntent

    /** 从媒体服务器导入, from [servers]: the ones the page offers the profile. */
    data class ImportFromServers(
        val servers: List<SavedServer>,
    ) : PersonalCenterIntent

    /** 重试 on the failure the page shows: the same action again. */
    data object RetryFailure : PersonalCenterIntent

    /** 移除记录, 先做，给 5 秒撤销: see [PersonalCenterState.pendingRemoval]. */
    data class RemoveEntry(
        val entry: PersonalEntry,
    ) : PersonalCenterIntent

    data class UndoRemoval(
        val entry: PersonalEntry,
    ) : PersonalCenterIntent

    /** The toast left — timed out, swiped away, the app or the page gone: the record goes now. */
    data object SettleRemoval : PersonalCenterIntent
}

/** A page action that can fail onto the page, kept so that 重试 runs it again as it was. */
private sealed interface Attempt {
    data class Switch(
        val profileId: String,
    ) : Attempt

    data class Remove(
        val profileId: String,
    ) : Attempt

    data object SyncNow : Attempt

    data class Import(
        val repo: EmbyRepository,
        val servers: List<SavedServer>,
    ) : Attempt

    data class Resolve(
        val conflict: SyncConflict,
        val keepLocal: Boolean,
    ) : Attempt
}

private sealed interface Action {
    data class Library(
        val state: PersonalLibraryState,
    ) : Action

    data class Account(
        val signedIn: Boolean,
    ) : Action

    data class Playback(
        val state: PlaybackCloudSyncState,
    ) : Action

    data class Servers(
        val state: ServerSyncState,
        val statuses: List<ServerSyncStatus>,
        val conflicts: List<SyncConflict>,
    ) : Action
}

private sealed interface Msg {
    data class Library(
        val state: PersonalLibraryState,
    ) : Msg

    data class Account(
        val signedIn: Boolean,
    ) : Msg

    data class Playback(
        val state: PlaybackCloudSyncState,
    ) : Msg

    data class Servers(
        val state: ServerSyncState,
        val statuses: List<ServerSyncStatus>,
        val conflicts: List<SyncConflict>,
    ) : Msg

    data class Busy(
        val value: Boolean,
    ) : Msg

    data class Message(
        val value: String,
    ) : Msg

    /** An attempt went through: the failure on the page, if any, goes. */
    data object Succeeded : Msg

    data class Failed(
        val message: String,
    ) : Msg

    data object FailureCleared : Msg

    data class DialogError(
        val value: String?,
    ) : Msg

    data class EditorOpened(
        val target: ProfileEditorTarget,
    ) : Msg

    data object EditorClosed : Msg

    data class SwitchAsked(
        val profile: PersonalProfile,
    ) : Msg

    data object SwitchClosed : Msg

    data object PinOpened : Msg

    data object PinClosed : Msg

    data object PinSaved : Msg

    data class RemovalAsked(
        val profile: PersonalProfile?,
    ) : Msg

    data class EntryHeld(
        val entry: PersonalEntry,
    ) : Msg

    data object EntryReleased : Msg
}

/**
 * 我的内容, 家庭资料 and 同步状态与恢复: one store for the three, as they were one page. [repo] is where
 * 从媒体服务器导入 reads the servers' own lists; without one the row is not offered.
 */
class PersonalCenterStoreFactory(
    private val storeFactory: StoreFactory,
    private val personal: PersonalLibraryRepository,
    private val sync: PersonalSync,
    private val repo: EmbyRepository?,
) {
    fun create(): Store<PersonalCenterIntent, PersonalCenterState, Nothing> =
        storeFactory.create(
            name = "PersonalCenterStore",
            // What the sources hold now, so the first frame is the page and not an empty one.
            initialState =
                PersonalCenterState(
                    library = personal.state.value,
                    signedIn = sync.account.value is AccountState.SignedIn,
                    playbackSync = sync.playback.value,
                    canImport = repo != null,
                ).withServers(visibleServers(sync.servers.value)),
            bootstrapper =
                coroutineBootstrapper<Action> {
                    personal.state
                        .onEach { dispatch(Action.Library(it)) }
                        .launchIn(this)
                    sync.account
                        .onEach { dispatch(Action.Account(it is AccountState.SignedIn)) }
                        .launchIn(this)
                    sync.playback
                        .onEach { dispatch(Action.Playback(it)) }
                        .launchIn(this)
                    // What the active profile may see changes with the profile, not only the sync.
                    combine(sync.servers, personal.policy) { state, _ -> visibleServers(state) }
                        .onEach { dispatch(it) }
                        .launchIn(this)
                },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private fun visibleServers(state: ServerSyncState): Action.Servers =
        Action.Servers(
            state = state,
            statuses = state.statuses.filter { personal.canAccessServer(it.serverId) },
            conflicts = state.conflicts.filter { personal.canAccessServer(it.mutation.serverId) },
        )

    private fun PersonalCenterState.withServers(servers: Action.Servers): PersonalCenterState =
        copy(serverSync = servers.state, serverStatuses = servers.statuses, serverConflicts = servers.conflicts)

    private inner class ExecutorImpl :
        CoroutineExecutor<PersonalCenterIntent, Action, PersonalCenterState, Msg, Nothing>() {
        /** 移除记录 waiting out its 撤销; see [UndoWindow]. */
        private val removals = UndoWindow<PersonalEntry> { personal.removeEntry(it) }

        /** What 重试 runs again: the action behind the failure the page shows. */
        private var failed: Attempt? = null

        override fun executeAction(action: Action) =
            when (action) {
                is Action.Library -> dispatch(Msg.Library(action.state))
                is Action.Account -> dispatch(Msg.Account(action.signedIn))
                is Action.Playback -> dispatch(Msg.Playback(action.state))
                is Action.Servers -> dispatch(Msg.Servers(action.state, action.statuses, action.conflicts))
            }

        override fun executeIntent(intent: PersonalCenterIntent) {
            when (intent) {
                is PersonalCenterIntent.SwitchProfile ->
                    if (state().library.activeProfile.child) {
                        dispatch(Msg.SwitchAsked(intent.profile))
                    } else {
                        attempt(Attempt.Switch(intent.profile.id))
                    }
                is PersonalCenterIntent.ConfirmSwitch -> confirmSwitch(intent.profileId, intent.pin)
                PersonalCenterIntent.DismissSwitch -> dispatch(Msg.SwitchClosed)
                is PersonalCenterIntent.EditProfile -> dispatch(Msg.EditorOpened(ProfileEditorTarget(intent.profile)))
                is PersonalCenterIntent.SaveProfile -> saveProfile(intent)
                PersonalCenterIntent.DismissEditor -> dispatch(Msg.EditorClosed)
                PersonalCenterIntent.EditGuardianPin -> dispatch(Msg.PinOpened)
                is PersonalCenterIntent.SaveGuardianPin -> saveGuardianPin(intent.currentPin, intent.newPin)
                PersonalCenterIntent.DismissGuardianPin -> dispatch(Msg.PinClosed)
                is PersonalCenterIntent.AskRemoveProfile -> dispatch(Msg.RemovalAsked(intent.profile))
                is PersonalCenterIntent.RemoveProfile -> {
                    attempt(Attempt.Remove(intent.profile.id))
                    dispatch(Msg.RemovalAsked(null))
                }
                PersonalCenterIntent.KeepProfile -> dispatch(Msg.RemovalAsked(null))
                PersonalCenterIntent.SyncNow -> attempt(Attempt.SyncNow)
                PersonalCenterIntent.RefreshPlaybackSync -> sync.refreshPlayback()
                PersonalCenterIntent.RetryServerSync -> scope.launch { sync.syncServers() }
                is PersonalCenterIntent.ResolveConflict -> attempt(Attempt.Resolve(intent.conflict, intent.keepLocal))
                is PersonalCenterIntent.ImportFromServers -> repo?.let { attempt(Attempt.Import(it, intent.servers)) }
                PersonalCenterIntent.RetryFailure -> {
                    dispatch(Msg.FailureCleared)
                    failed?.let(::attempt)
                }
                is PersonalCenterIntent.RemoveEntry -> {
                    removals.hold(intent.entry)
                    dispatch(Msg.EntryHeld(intent.entry))
                }
                is PersonalCenterIntent.UndoRemoval ->
                    if (removals.undo { it.identity == intent.entry.identity } != null) dispatch(Msg.EntryReleased)
                PersonalCenterIntent.SettleRemoval -> {
                    removals.settle()
                    dispatch(Msg.EntryReleased)
                }
            }
        }

        /** Leaving the page is the toast leaving too: nothing stays held behind a closed page. */
        override fun dispose() {
            removals.settle()
            super.dispose()
        }

        private fun attempt(action: Attempt) {
            scope.launch {
                run(action)
                    .onSuccess { dispatch(Msg.Succeeded) }
                    .onFailure { error ->
                        failed = action
                        dispatch(Msg.Failed(error.message ?: PERSONAL_ACTION_FAILED))
                    }
            }
        }

        private suspend fun run(action: Attempt): Result<Any?> =
            when (action) {
                is Attempt.Switch -> personal.switchProfile(action.profileId)
                is Attempt.Remove -> personal.deleteProfile(action.profileId)
                Attempt.SyncNow -> sync.syncPersonalNow().onSuccess { dispatch(Msg.Message("个人数据已同步")) }
                is Attempt.Import -> importLists(action)
                is Attempt.Resolve -> sync.resolveConflict(action.conflict, action.keepLocal)
            }

        private suspend fun importLists(action: Attempt.Import): Result<Int> {
            dispatch(Msg.Busy(true))
            return try {
                personal
                    .importServerCollections(action.repo, action.servers)
                    .onSuccess { dispatch(Msg.Message("已导入 " + it + " 项，现有个人选择已保留")) }
            } finally {
                dispatch(Msg.Busy(false))
            }
        }

        private fun saveProfile(intent: PersonalCenterIntent.SaveProfile) {
            dispatch(Msg.Busy(true))
            dispatch(Msg.DialogError(null))
            scope.launch {
                try {
                    personal
                        .saveProfile(intent.profileId, intent.name, intent.child, intent.serverIds)
                        .onSuccess { dispatch(Msg.EditorClosed) }
                        .onFailure { dispatch(Msg.DialogError(it.message ?: PERSONAL_ACTION_FAILED)) }
                } finally {
                    dispatch(Msg.Busy(false))
                }
            }
        }

        private fun confirmSwitch(
            profileId: String,
            pin: String,
        ) {
            dispatch(Msg.DialogError(null))
            scope.launch {
                personal
                    .switchProfile(profileId, pin.toCharArray())
                    .onSuccess { dispatch(Msg.SwitchClosed) }
                    .onFailure { dispatch(Msg.DialogError(it.message ?: PERSONAL_ACTION_FAILED)) }
            }
        }

        private fun saveGuardianPin(
            currentPin: String,
            newPin: String,
        ) {
            dispatch(Msg.DialogError(null))
            scope.launch {
                personal
                    .setGuardianPin(newPin.toCharArray(), currentPin.toCharArray())
                    .onSuccess { dispatch(Msg.PinSaved) }
                    .onFailure { dispatch(Msg.DialogError(it.message ?: PERSONAL_ACTION_FAILED)) }
            }
        }
    }

    private object ReducerImpl : Reducer<PersonalCenterState, Msg> {
        override fun PersonalCenterState.reduce(msg: Msg): PersonalCenterState =
            when (msg) {
                is Msg.Library -> copy(library = msg.state)
                is Msg.Account -> copy(signedIn = msg.signedIn)
                is Msg.Playback -> copy(playbackSync = msg.state)
                is Msg.Servers ->
                    copy(serverSync = msg.state, serverStatuses = msg.statuses, serverConflicts = msg.conflicts)
                is Msg.Busy -> copy(busy = msg.value)
                is Msg.Message -> copy(message = msg.value)
                Msg.Succeeded -> copy(failure = null)
                is Msg.Failed -> copy(message = null, failure = msg.message)
                Msg.FailureCleared -> copy(failure = null)
                is Msg.DialogError -> copy(dialogError = msg.value)
                is Msg.EditorOpened -> copy(editor = msg.target, dialogError = null)
                Msg.EditorClosed -> copy(editor = null)
                is Msg.SwitchAsked -> copy(switching = msg.profile, dialogError = null)
                Msg.SwitchClosed -> copy(switching = null)
                Msg.PinOpened -> copy(settingPin = true, dialogError = null)
                Msg.PinClosed -> copy(settingPin = false)
                Msg.PinSaved -> copy(settingPin = false, message = "家长 PIN 已保存")
                is Msg.RemovalAsked -> copy(removingProfile = msg.profile)
                is Msg.EntryHeld -> copy(pendingRemoval = msg.entry, removalGeneration = removalGeneration + 1)
                Msg.EntryReleased -> copy(pendingRemoval = null)
            }
    }
}

/** The one call that takes [entry] out of the active profile; a failure lands in the repository's error. */
private fun PersonalLibraryRepository.removeEntry(entry: PersonalEntry) {
    when (entry.collection) {
        PersonalCollection.Favorite -> setFavorite(entry.media, false)
        PersonalCollection.WatchLater -> setWatchLater(entry.media, false)
        PersonalCollection.History -> removeHistory(entry.media)
    }
}

private const val PERSONAL_ACTION_FAILED = "操作没有完成，请重试"
