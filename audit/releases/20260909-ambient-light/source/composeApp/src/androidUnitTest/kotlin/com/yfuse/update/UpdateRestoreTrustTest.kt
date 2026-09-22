package com.yfuse.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class UpdateRestoreTrustTest {
    private val sourceUrl = "https://updates.example.com/yfuse/update-v2.json"
    private val record =
        UpdateDownloadRecord(
            UpdateManifest(
                versionCode = 208,
                versionName = "1.0.46",
                apkUrl = "https://updates.example.com/yfuse/Yfuse-208.apk",
                sha256 = "a".repeat(64),
                size = 1_000L,
            ),
            validator = "etag:package-208",
        )

    @Test
    fun an_unsigned_download_resumes_without_a_key_but_not_after_installing_a_build_with_a_key() {
        assertSame(
            record,
            record.validateForRestore(sourceUrl, "") { _, _, _ -> error("No key configured") },
        )
        val error =
            assertFailsWith<UpdateManifestRejectedException> {
                record.validateForRestore(sourceUrl, "newly-configured-key") { _, _, _ -> true }
            }
        assertEquals(UpdateManifestTrust.RejectedUnsigned, error.verdict)
    }

    @Test
    fun signed_records_are_reverified_using_the_current_key_and_exact_stored_payload() {
        val signed = record.copy(manifest = record.manifest.copy(signature = "previous-signature"))
        val error =
            assertFailsWith<UpdateManifestRejectedException> {
                signed.validateForRestore(sourceUrl, "current-key") { key, payload, signature ->
                    assertEquals("current-key", key)
                    assertEquals(signed.manifest.signedPayload().toList(), payload.toList())
                    assertEquals("previous-signature", signature)
                    false
                }
            }
        assertEquals(UpdateManifestTrust.RejectedInvalidSignature, error.verdict)
        assertSame(signed, signed.validateForRestore(sourceUrl, "current-key") { _, _, _ -> true })
    }

    @Test
    fun missing_keys_do_not_allow_restoring_another_origin_or_a_malformed_digest() {
        for (manifest in listOf(
            record.manifest.copy(apkUrl = "https://another.example.com/yfuse/Yfuse-208.apk"),
            record.manifest.copy(apkUrl = "http://updates.example.com/yfuse/Yfuse-208.apk"),
            record.manifest.copy(sha256 = "invalid"),
        )) {
            assertFailsWith<IllegalArgumentException> {
                record.copy(manifest = manifest).validateForRestore(sourceUrl, "") { _, _, _ -> true }
            }
        }
    }
}
