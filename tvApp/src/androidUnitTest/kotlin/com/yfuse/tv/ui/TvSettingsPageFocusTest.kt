package com.yfuse.tv.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.yfuse.core.account.AccountState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TvSettingsPageFocusTest {
    @Test
    fun back_from_sessions_returns_to_account_before_the_settings_root() {
        assertEquals(TvSettingsPage.Account, TvSettingsPage.AccountSessions.parentPage)
        assertEquals(TvSettingsPage.Root, TvSettingsPage.Account.parentPage)
        assertEquals(TvSettingsPage.Root, TvSettingsPage.Playback.parentPage)
    }

    @Test
    fun delayedContentGetsFocusButMessagesAndLeavingPagesDoNotStealIt() =
        runTest {
            val clock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + clock)
            val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(NoNodes(), recomposer)
            val account = mutableStateOf<AccountState>(AccountState.Restoring)
            val active = mutableStateOf(true)
            var requests = 0
            var time = 0L

            fun frame() {
                Snapshot.sendApplyNotifications()
                runCurrent()
                clock.sendFrame(++time * 16_000_000)
                runCurrent()
            }
            try {
                composition.setContent {
                    RequestTvSettingsPageFocus(TvSettingsPage.Account, active.value, account.value) { requests++ }
                }
                frame()
                advanceTimeBy(2_000)
                frame()
                assertEquals(0, requests)
                account.value = AccountState.RestoreFailed("offline")
                frame()
                assertEquals(1, requests)
                account.value = AccountState.RestoreFailed("new message")
                frame()
                assertEquals(1, requests)
                account.value = AccountState.Restoring
                frame()
                account.value = AccountState.SignedOut
                frame()
                assertEquals(2, requests)
                active.value = false
                account.value = AccountState.RestoreFailed("late response")
                frame()
                assertEquals(2, requests)
            } finally {
                composition.dispose()
                recomposer.cancel()
                runner.join()
            }
        }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun insertBottomUp(
            index: Int,
            instance: Unit,
        ) = Unit

        override fun remove(
            index: Int,
            count: Int,
        ) = Unit

        override fun move(
            from: Int,
            to: Int,
            count: Int,
        ) = Unit

        override fun onClear() = Unit
    }
}
