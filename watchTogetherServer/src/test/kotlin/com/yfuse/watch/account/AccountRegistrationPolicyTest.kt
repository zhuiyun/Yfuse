package com.yfuse.watch.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountRegistrationPolicyTest {

    @Test
    fun directPolicyConstructionAlsoDefaultsToClosed() {
        assertFalse(AccountRegistrationPolicy().enabled)
    }

    @Test
    fun productionEnvironmentDefaultsRegistrationToClosed() {
        assertFalse(AccountRegistrationPolicy.fromEnvironment(emptyMap()).enabled)
        assertFalse(
            AccountRegistrationPolicy.fromEnvironment(
                mapOf("ACCOUNT_REGISTRATION_ENABLED" to ""),
            ).enabled,
        )
    }

    @Test
    fun operatorCanExplicitlyOpenRegistrationForProvisioning() {
        assertTrue(
            AccountRegistrationPolicy.fromEnvironment(
                mapOf("ACCOUNT_REGISTRATION_ENABLED" to "true"),
            ).enabled,
        )
    }

    @Test
    fun invitationCodesOpenOnlyInvitationDrivenRegistration() {
        val policy = AccountRegistrationPolicy.fromEnvironment(
            mapOf("ACCOUNT_REGISTRATION_INVITE_CODES" to "first-code-2026, second-code-2026"),
        )
        assertFalse(policy.enabled)
        assertEquals(setOf("first-code-2026", "second-code-2026"), policy.invitationCodes)
    }

    @Test
    fun issuerAndTtlConfigurationAreExplicitAndNormalized() {
        val policy = AccountRegistrationPolicy.fromEnvironment(
            mapOf(
                "ACCOUNT_INVITE_ISSUER_USERNAMES" to " ZHUIYUN, operator ",
                "ACCOUNT_ISSUED_INVITE_TTL_HOURS" to "48",
            ),
        )
        assertEquals(setOf("zhuiyun", "operator"), policy.inviteIssuerUsernames)
        assertEquals(48L * 60 * 60_000L, policy.issuedInviteTtlMs)
    }
}
