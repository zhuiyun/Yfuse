package com.yfuse.core2.android

import android.media.MediaCodec

internal data class YMediaCodecCryptoSnapshot(
    val numberOfSubSamples: Int,
    val clearBytes: IntArray,
    val encryptedBytes: IntArray,
    val key: ByteArray,
    val iv: ByteArray,
    val mode: Int,
    val encryptedBlocks: Int,
    val clearBlocks: Int,
)

internal data class YExtractorCryptoInfo(
    val numberOfSubSamples: Int,
    val clearBytes: IntArray?,
    val encryptedBytes: IntArray?,
    val key: ByteArray,
    val initializationVector: ByteArray,
    val mode: Int,
    val encryptedBlocks: Int = 0,
    val clearBlocks: Int = 0,
) {
    init {
        require(numberOfSubSamples > 0)
        require(clearBytes == null || clearBytes.size >= numberOfSubSamples)
        require(encryptedBytes == null || encryptedBytes.size >= numberOfSubSamples)
        require(key.size == AES_BLOCK_BYTES && initializationVector.size == AES_BLOCK_BYTES)
        require(mode in setOf(MediaCodec.CRYPTO_MODE_AES_CTR, MediaCodec.CRYPTO_MODE_AES_CBC)) {
            "Only CENC AES-CTR and CBCS AES-CBC samples are executable"
        }
        require(encryptedBlocks >= 0 && clearBlocks >= 0) { "Crypto pattern blocks cannot be negative" }
    }

    internal fun toMediaCodecCryptoSnapshot(): YMediaCodecCryptoSnapshot =
        YMediaCodecCryptoSnapshot(
            numberOfSubSamples = numberOfSubSamples,
            clearBytes = clearBytes?.copyOf() ?: IntArray(numberOfSubSamples),
            encryptedBytes = encryptedBytes?.copyOf() ?: IntArray(numberOfSubSamples),
            key = key.copyOf(),
            iv = initializationVector.copyOf(),
            mode = mode,
            encryptedBlocks = encryptedBlocks,
            clearBlocks = clearBlocks,
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
            if (
                snapshot.mode == MediaCodec.CRYPTO_MODE_AES_CBC ||
                snapshot.encryptedBlocks > 0 ||
                snapshot.clearBlocks > 0
            ) {
                setPattern(MediaCodec.CryptoInfo.Pattern(snapshot.encryptedBlocks, snapshot.clearBlocks))
            }
        }
    }

    override fun toString(): String =
        "YExtractorCryptoInfo(" +
            "subSamples=$numberOfSubSamples, mode=$mode, pattern=$encryptedBlocks/$clearBlocks, " +
            "key=<redacted>, iv=<redacted>)"
}

private const val AES_BLOCK_BYTES = 16
