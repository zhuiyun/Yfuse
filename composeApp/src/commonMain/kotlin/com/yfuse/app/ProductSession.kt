package com.yfuse.app

import com.yfuse.core.account.AccountRepository
import com.yfuse.core.account.AccountState
import com.yfuse.core.personal.PersonalLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Process lifetime for account-scoped integrations; switching profiles cancels their work. */
class ProductSession(
    account: AccountRepository,
    personal: PersonalLibraryRepository,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val foreground = kotlinx.coroutines.flow.MutableStateFlow(false)
    val owner =
        combine(account.state, personal.policy) { accountState, policy ->
            (accountState as? AccountState.SignedIn)
                ?.session
                ?.user
                ?.id
                ?.let { "$it:${policy.profileId}" }
        }.stateIn(scope, SharingStarted.Eagerly, null)
}
