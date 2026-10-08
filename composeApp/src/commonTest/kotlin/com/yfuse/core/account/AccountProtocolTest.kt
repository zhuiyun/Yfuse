package com.yfuse.core.account

import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.toBase64Url
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class AccountProtocolTest {
    @Test
    fun preloginAnswersMapToHowThePasswordIsProved() {
        assertSame(LoginProof.Legacy, null.toLoginProof())
        assertSame(LoginProof.Password, PreloginResponse(authProtocol = 1).toLoginProof())
        val salt = ByteArray(16) { it.toByte() }
        val derived =
            assertIs<LoginProof.DerivedKey>(
                PreloginResponse(2, ACCOUNT_KDF, 700_000, salt.toBase64Url()).toLoginProof(),
            )
        assertContentEquals(salt, derived.salt)
        assertEquals(700_000, derived.iterations)
    }

    @Test
    fun parametersThatWeakenTheDerivedKeyAreRefused() {
        val salt = ByteArray(16).toBase64Url()
        assertFailsWith<IllegalArgumentException> { PreloginResponse(2, ACCOUNT_KDF, 100_000, salt).toLoginProof() }
        assertFailsWith<IllegalArgumentException> { PreloginResponse(2, "SHA1", 600_000, salt).toLoginProof() }
        assertFailsWith<IllegalArgumentException> {
            PreloginResponse(2, ACCOUNT_KDF, 600_000, ByteArray(8).toBase64Url()).toLoginProof()
        }
        assertFailsWith<IllegalArgumentException> { PreloginResponse(2, ACCOUNT_KDF, 600_000, null).toLoginProof() }
        assertFailsWith<IllegalStateException> { PreloginResponse(3).toLoginProof() }
    }

    @Test
    fun theMemoryOfUpgradedAccountsSurvivesSignOutAndIgnoresCase() {
        val store = TestSecureStore()
        val memory = AccountProtocolMemory(store, VaultCrypto())
        memory.requirePasswordMayBeSent("viewer")
        memory.rememberDerivedKey(" Viewer ")
        store.put("refresh_token", byteArrayOf(1))

        memory.preservedAcross(store::clear)

        assertEquals(setOf("account_protocol2_names"), store.storedKeys())
        assertFailsWith<IllegalStateException> { memory.requirePasswordMayBeSent("VIEWER") }
        memory.requirePasswordMayBeSent("someone-else")
        memory.forget("viewer")
        memory.requirePasswordMayBeSent("viewer")
    }
}
