package com.yfuse.core2.android

/**
 * Publishes only the validated, immutable prefix of an in-flight range. The producer may
 * append to the array, but must allocate a new array when restarting an unvalidated response.
 * Readers own their copies; neither socket cancellation nor a retry can change them.
 */
internal class YProgressiveTransportBlock {
    private data class Prefix(
        val bytes: ByteArray,
        val size: Int,
        val contentLength: Long?,
    )

    @Volatile
    private var prefix: Prefix? = null

    fun publish(
        bytes: ByteArray,
        size: Int,
        contentLength: Long?,
    ) {
        require(size in 0..bytes.size)
        prefix = Prefix(bytes, size, contentLength)
    }

    fun clear() {
        prefix = null
    }

    fun read(
        offset: Int,
        maximumBytes: Int,
    ): Slice? {
        require(offset >= 0 && maximumBytes > 0)
        val available = prefix ?: return null
        if (offset >= available.size) return null
        val count = minOf(maximumBytes, available.size - offset)
        return Slice(available.bytes.copyOfRange(offset, offset + count), offset, available.contentLength)
    }

    data class Slice(
        val bytes: ByteArray,
        val offset: Int,
        val contentLength: Long?,
    )
}
