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
        assertTrue(policy.enabled)
        assertEquals(setOf("first-code-2026", "second-code-2026"), policy.invitationCodes)
    }
}
