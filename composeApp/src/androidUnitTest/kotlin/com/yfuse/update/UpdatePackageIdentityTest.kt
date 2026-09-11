package com.yfuse.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePackageIdentityTest {
    private val installed = UpdatePackageIdentity("com.yfuse", 100L, setOf("release-signer"))
    private val update = installed.copy(versionCode = 101L)

    @Test
    fun matching_package_and_signer_with_the_exact_new_version_is_accepted() {
        assertTrue(isExpectedUpdatePackage(installed, update, expectedVersionCode = 101))
    }

    @Test
    fun another_app_signed_with_the_same_key_is_rejected() {
        assertFalse(isExpectedUpdatePackage(installed, update.copy(packageName = "com.other.app"), 101))
    }

    @Test
    fun equal_or_older_versions_are_rejected_even_when_the_manifest_matches() {
        listOf(99, 100).forEach { version ->
            assertFalse(isExpectedUpdatePackage(installed, update.copy(versionCode = version.toLong()), version))
        }
    }

    @Test
    fun an_apk_version_different_from_the_manifest_is_rejected() {
        assertFalse(isExpectedUpdatePackage(installed, update, expectedVersionCode = 102))
    }

    @Test
    fun a_different_or_additional_archive_signer_is_rejected() {
        listOf(setOf("other-signer"), setOf("release-signer", "other-signer")).forEach { signers ->
            assertFalse(isExpectedUpdatePackage(installed, update.copy(signerDigests = signers), 101))
        }
    }

    @Test
    fun unreadable_archive_or_missing_signing_metadata_is_rejected() {
        assertFalse(isExpectedUpdatePackage(installed, null, 101))
        assertFalse(isExpectedUpdatePackage(installed, update.copy(signerDigests = emptySet()), 101))
        assertFalse(isExpectedUpdatePackage(installed.copy(signerDigests = emptySet()), update, 101))
    }

    @Test
    fun long_android_version_codes_are_not_truncated_to_the_manifest_integer() {
        val versionWithHighBits = (1L shl 32) + 101L
        assertFalse(isExpectedUpdatePackage(installed, update.copy(versionCode = versionWithHighBits), 101))
        assertFalse(isExpectedUpdatePackage(installed.copy(versionCode = versionWithHighBits), update, 101))
    }

    @Test
    fun an_installed_signer_history_keeps_the_existing_certificate_policy() {
        assertTrue(
            isExpectedUpdatePackage(
                installed.copy(signerDigests = setOf("previous-signer", "release-signer")),
                update,
                101,
            ),
        )
    }
}
