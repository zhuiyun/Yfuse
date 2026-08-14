package com.yfuse.core.account

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountAccessTokenSourceTest {
    @Test
    fun token_is_only_released_to_the_exact_account_service_origin() =
        runTest {
            val source = AccountAccessTokenSource("https://47.112.219.60")
            source.bind(provider = { "secret-access" }, refreshProvider = { "fresh-access" })

            assertEquals("secret-access", source.validAccessTokenFor("wss://47.112.219.60"))
            assertEquals("secret-access", source.validAccessTokenFor("https://47.112.219.60/watch"))
            assertNull(source.validAccessTokenFor("wss://evil.example"))
            assertNull(source.validAccessTokenFor("https://47.112.219.60.evil.example"))
            assertNull(source.validAccessTokenFor("ws://47.112.219.60"))
            assertNull(source.validAccessTokenFor("wss://47.112.219.60:8443"))
        }

    @Test
    fun session_availability_is_memory_only_and_fail_closed() {
        val source = AccountAccessTokenSource()
        assertFalse(source.sessionAvailable.value)
        source.markAvailable()
        assertTrue(source.sessionAvailable.value)
        source.markUnavailable()
        assertFalse(source.sessionAvailable.value)
    }
}
