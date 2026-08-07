package com.yfuse.core.security

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecureStoreEnvelopeCodecTest {
    @Test
    fun envelopeRoundTrips() {
        val expected = SecureStoreEnvelope(
            nonce = ByteArray(12) { it.toByte() },
            ciphertext = ByteArray(32) { (it * 3).toByte() },
        )

        val actual = SecureStoreEnvelopeCodec.decode(SecureStoreEnvelopeCodec.encode(expected))

        assertTrue(expected.nonce.contentEquals(actual.nonce))
        assertTrue(expected.ciphertext.contentEquals(actual.ciphertext))
    }

    @Test
    fun decoderRejectsModifiedHeaderTruncationAndTrailingBytes() {
        val encoded = SecureStoreEnvelopeCodec.encode(
            SecureStoreEnvelope(ByteArray(12), ByteArray(16)),
        )
        val wrongMagic = encoded.copyOf().apply { this[0] = 0 }
        val wrongVersion = encoded.copyOf().apply { this[4] = 2 }
        val wrongNonceSize = encoded.copyOf().apply { this[5] = 11 }
        val truncated = encoded.copyOf(encoded.size - 1)
        val trailing = encoded + byteArrayOf(0)

        listOf(wrongMagic, wrongVersion, wrongNonceSize, truncated, trailing).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                SecureStoreEnvelopeCodec.decode(invalid)
            }
        }
    }
}
