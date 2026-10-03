package com.yfuse.feature.profile

import com.yfuse.core.security.ServerMigrationCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ProtectedExportPassphraseTest {
    @Test
    fun matching_passphrase_within_the_crypto_rules_is_accepted() {
        assertNull(protectedExportPassphraseError("correct horse battery", "correct horse battery"))
        assertNull(protectedExportPassphraseError("a".repeat(12), "a".repeat(12)))
        assertNull(protectedExportPassphraseError("a".repeat(256), "a".repeat(256)))
    }

    @Test
    fun short_long_or_blank_passphrases_are_refused_with_the_rule_that_failed() {
        assertEquals("保护口令至少需要 12 个字符", protectedExportPassphraseError("a".repeat(11), "a".repeat(11)))
        assertEquals("保护口令最多 256 个字符", protectedExportPassphraseError("a".repeat(257), "a".repeat(257)))
        assertEquals("保护口令不能只包含空白字符", protectedExportPassphraseError(" ".repeat(12), " ".repeat(12)))
        assertEquals("保护口令至少需要 12 个字符", protectedExportPassphraseError("", ""))
    }

    @Test
    fun a_mistyped_confirmation_is_caught_before_the_file_is_sealed() {
        assertEquals(
            "两次输入的口令不一致",
            protectedExportPassphraseError("correct horse battery", "correct horse batterY"),
        )
        assertEquals("两次输入的口令不一致", protectedExportPassphraseError("correct horse battery", ""))
    }

    @Test
    fun the_dialog_accepts_exactly_what_the_package_crypto_accepts() {
        listOf("a".repeat(12), "口令".repeat(6), " padded passphrase ", "x".repeat(256)).forEach { passphrase ->
            assertNull(protectedExportPassphraseError(passphrase, passphrase), passphrase)
            ServerMigrationCrypto.requireStrongPassphrase(passphrase.toCharArray())
        }
        listOf("a".repeat(11), "\t".repeat(12), "x".repeat(257)).forEach { passphrase ->
            assertNotNull(protectedExportPassphraseError(passphrase, passphrase), passphrase)
            assertFailsWith<IllegalArgumentException> {
                ServerMigrationCrypto.requireStrongPassphrase(passphrase.toCharArray())
            }
        }
    }
}
