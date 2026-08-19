package com.yfuse.feature.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerBackupPayloadTest {
    @Test
    fun qrPayloadRoundTripPreservesProtectedEnvelope() {
        val backup =
            """{"type":"yfuse-server-migration","v":2,"ciphertext":"encrypted-data"}"""

        val encoded = encodeQrPayload(backup)

        assertTrue(encoded.startsWith("YFUSE2:"))
        assertEquals(backup, decodeQrPayload(backup))
        assertEquals(backup, decodeQrPayload(encoded))
    }

    @Test
    fun v3QrPayloadUsesDedicatedPrefixAndPreservesEnvelope() {
        val backup =
            """{"type":"yfuse-server-migration","v":3,"relayId":"opaque","protectedV2":"ciphertext"}"""

        val encoded = encodeQrPayload(backup)

        assertTrue(encoded.startsWith("YFUSE3:"))
        assertEquals(backup, decodeQrPayload(encoded))
    }

    @Test
    fun legacyPlaintextQrIsExplicitlyRejected() {
        assertFailsWith<IllegalStateException> {
            decodeQrPayload("YFUSE1:H4sIAAAAAAAA")
        }
    }

    @Test
    fun corruptedCompressedPayloadIsRejected() {
        assertFailsWith<IllegalStateException> {
            decodeQrPayload("YFUSE3:not-valid-gzip")
        }
    }

    @Test
    fun oversizedPlainPayloadIsRejectedBeforeImport() {
        assertFailsWith<IllegalArgumentException> {
            decodeQrPayload("x".repeat(512 * 1_024 + 1))
        }
    }
}
