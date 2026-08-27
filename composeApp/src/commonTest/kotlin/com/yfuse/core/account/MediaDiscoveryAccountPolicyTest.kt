package com.yfuse.core.account

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaDiscoveryAccountPolicyTest {
    @Test
    fun only_the_exact_zhuiyun_username_can_use_media_discovery() {
        assertTrue(signedIn(username = "zhuiyun", nickname = "任意昵称").canUseMediaDiscovery())
        assertFalse(signedIn(username = "ZHUIYUN", nickname = "zhuiyun").canUseMediaDiscovery())
        assertFalse(signedIn(username = "other", nickname = "zhuiyun").canUseMediaDiscovery())
        assertFalse(AccountState.SignedOut.canUseMediaDiscovery())
        assertFalse(AccountState.Restoring.canUseMediaDiscovery())
    }

    private fun signedIn(
        username: String,
        nickname: String,
    ): AccountState.SignedIn =
        AccountState.SignedIn(
            session =
                AccountSession(
                    user =
                        AccountUser(
                            id = "user-id",
                            username = username,
                            nickname = nickname,
                            avatarId = 0,
                            createdAtEpochMs = 0L,
                            updatedAtEpochMs = 0L,
                        ),
                    accessToken = "token",
                    accessExpiresAtEpochMs = Long.MAX_VALUE,
                    refreshExpiresAtEpochMs = Long.MAX_VALUE,
                ),
        )
}
