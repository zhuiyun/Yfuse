package com.yfuse.feature.profile

import kotlin.test.Test
import kotlin.test.assertEquals

class AccountInviteUiContractTest {
    @Test
    fun invitation_expiry_is_rendered_as_an_explicit_utc_time() {
        assertEquals("2023-11-14 22:13 UTC", formatInviteExpiryUtc(1_700_000_000_000L))
    }

    @Test
    fun invite_code_is_grouped_for_readability_without_changing_the_credential() {
        val code = "00fkGXQc35Ma6egzQ5lcLuWlqAxAKgSGJk7lfc7qAvk"

        assertEquals(
            "00fkGXQc  35Ma6egz  Q5lcLuWl  qAxAKgSG  Jk7lfc7q  Avk",
            formatInviteCodeForDisplay(code),
        )
        assertEquals(code, formatInviteCodeForDisplay(code).replace("  ", ""))
    }
}
