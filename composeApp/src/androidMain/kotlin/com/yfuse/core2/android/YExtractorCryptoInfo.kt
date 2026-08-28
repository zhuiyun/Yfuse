package com.yfuse.core2.android

import android.media.MediaCodec

internal data class YMediaCodecCryptoSnapshot(
    val numberOfSubSamples: Int,
    val clearBytes: IntArray,
    val encryptedBytes: IntArray,
    val key: ByteArray,
    val iv: ByteArray,
    val mode: Int,
)

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

    internal fun toMediaCodecCryptoSnapshot(): YMediaCodecCryptoSnapshot =
        YMediaCodecCryptoSnapshot(
            numberOfSubSamples = numberOfSubSamples,
            clearBytes = clearBytes?.copyOf() ?: IntArray(numberOfSubSamples),
            encryptedBytes = encryptedBytes?.copyOf() ?: IntArray(numberOfSubSamples),
            key = key.copyOf(),
            iv = initializationVector.copyOf(),
            mode = mode,
        )

    fun toMediaCodecCryptoInfo(): MediaCodec.CryptoInfo {
        val snapshot = toMediaCodecCryptoSnapshot()
        return MediaCodec.CryptoInfo().apply {
            set(
                snapshot.numberOfSubSamples,
                snapshot.clearBytes,
                snapshot.encryptedBytes,
                snapshot.key,
                snapshot.iv,
                snapshot.mode,
            )
        }
    }

    override fun toString(): String = "YExtractorCryptoInfo(subSamples=$numberOfSubSamples, mode=$mode, key=<redacted>, iv=<redacted>)"
}

private const val AES_BLOCK_BYTES = 16
