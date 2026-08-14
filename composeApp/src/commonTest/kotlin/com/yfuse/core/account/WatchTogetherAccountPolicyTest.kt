package com.yfuse.core.account

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherAccountPolicyTest {
    @Test
    fun only_a_signed_in_account_can_use_watch_together() {
        assertFalse(AccountState.SignedOut.canUseWatchTogether())
        assertFalse(AccountState.Restoring.canUseWatchTogether())
        assertFalse(AccountState.RestoreFailed("failed").canUseWatchTogether())
        assertTrue(
            AccountState.SignedIn(
                AccountSession(
                    user = AccountUser("u", "user", "User", 0, 1, 1),
                    accessToken = "token",
                    accessExpiresAtEpochMs = Long.MAX_VALUE,
                    refreshExpiresAtEpochMs = Long.MAX_VALUE,
                ),
            ).canUseWatchTogether(),
        )
    }
}
