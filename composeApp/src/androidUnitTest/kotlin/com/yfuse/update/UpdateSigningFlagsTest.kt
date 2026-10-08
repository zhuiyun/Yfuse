package com.yfuse.update

import android.content.pm.PackageManager
import kotlin.test.Test
import kotlin.test.assertEquals

@Suppress("DEPRECATION")
class UpdateSigningFlagsTest {
    @Test
    fun android_9_and_10_also_ask_for_legacy_signatures_so_archives_are_signed() {
        val both = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES

        assertEquals(both, updateSigningFlags(28))
        assertEquals(both, updateSigningFlags(29))
    }

    @Test
    fun android_11_and_later_read_signing_certificates_only() {
        listOf(30, 34, 37).forEach { sdk ->
            assertEquals(PackageManager.GET_SIGNING_CERTIFICATES, updateSigningFlags(sdk))
        }
    }

    @Test
    fun android_8_reads_the_legacy_signatures() {
        listOf(26, 27).forEach { sdk ->
            assertEquals(PackageManager.GET_SIGNATURES, updateSigningFlags(sdk))
        }
    }
}
