package com.yfuse.core2.android

import android.media.MediaCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AndroidYCoreCryptoInfoTest {
    @Test
    fun cenc_snapshot_copies_platform_arrays_and_redacts_key_material() {
        val key = ByteArray(16) { it.toByte() }
        val iv = ByteArray(16) { (it + 16).toByte() }
        val snapshot =
            YExtractorCryptoInfo(
                numberOfSubSamples = 2,
                clearBytes = intArrayOf(32, 0),
                encryptedBytes = intArrayOf(512, 1024),
                key = key,
                initializationVector = iv,
                mode = MediaCodec.CRYPTO_MODE_AES_CTR,
            )
        val platform = snapshot.toMediaCodecCryptoInfo()
        key.fill(0)
        iv.fill(0)

        assertContentEquals(ByteArray(16) { it.toByte() }, platform.key)
        assertContentEquals(ByteArray(16) { (it + 16).toByte() }, platform.iv)
        assertFalse("0, 1, 2" in snapshot.toString())
    }

    @Test
    fun non_ctr_sample_encryption_remains_fail_closed() {
        assertFailsWith<IllegalArgumentException> {
            YExtractorCryptoInfo(
                numberOfSubSamples = 1,
                clearBytes = intArrayOf(0),
                encryptedBytes = intArrayOf(16),
                key = ByteArray(16),
                initializationVector = ByteArray(16),
                mode = 2,
            )
        }
    }
}
