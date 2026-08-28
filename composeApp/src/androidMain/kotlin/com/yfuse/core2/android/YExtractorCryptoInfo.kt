package com.yfuse.core2.android

import android.media.MediaCodec

internal data class YExtractorCryptoInfo(
    val numberOfSubSamples: Int,
    val clearBytes: IntArray?,
    val encryptedBytes: IntArray?,
    val key: ByteArray,
    val initializationVector: ByteArray,
    val mode: Int,
) {
    init {
        require(numberOfSubSamples > 0)
        require(clearBytes == null || clearBytes.size >= numberOfSubSamples)
        require(encryptedBytes == null || encryptedBytes.size >= numberOfSubSamples)
        require(key.size == AES_BLOCK_BYTES && initializationVector.size == AES_BLOCK_BYTES)
        require(mode == MediaCodec.CRYPTO_MODE_AES_CTR) { "Only CENC AES-CTR samples are executable" }
    }

    fun toMediaCodecCryptoInfo(): MediaCodec.CryptoInfo =
        MediaCodec.CryptoInfo().apply {
            set(
                numberOfSubSamples,
                clearBytes?.copyOf(),
                encryptedBytes?.copyOf(),
                key.copyOf(),
                initializationVector.copyOf(),
                mode,
            )
        }

    override fun toString(): String = "YExtractorCryptoInfo(subSamples=$numberOfSubSamples, mode=$mode, key=<redacted>, iv=<redacted>)"
}

private const val AES_BLOCK_BYTES = 16
