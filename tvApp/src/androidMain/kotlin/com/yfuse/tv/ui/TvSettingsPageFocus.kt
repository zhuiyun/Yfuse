package com.yfuse.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.yfuse.core.account.AccountState

/** Retry when asynchronous account content attaches, without refocusing on sync/status updates. */
@Composable
internal fun RequestTvSettingsPageFocus(
    page: TvSettingsPage,
    active: Boolean,
    account: AccountState,
    requestFocus: suspend () -> Unit,
) {
    val action by rememberUpdatedState(requestFocus)
    val target =
        if (page == TvSettingsPage.Account) {
            when (account) {
                AccountState.Restoring -> null
                AccountState.SignedOut -> "signed-out"
                is AccountState.RestoreFailed -> "retry"
                is AccountState.SignedIn -> "signed-in:${account.session.user.id}"
            }
        } else {
            page.name
        }
    LaunchedEffect(active, page, target) {
        if (active && page != TvSettingsPage.Root && target != null) action()
    }
}
