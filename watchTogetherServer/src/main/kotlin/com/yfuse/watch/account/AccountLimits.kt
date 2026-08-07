package com.yfuse.watch.account

/**
 * Account API body limits share one source of truth so increasing the accepted decoded
 * ciphertext also increases the largest legal JSON request and response.
 */
internal object AccountLimits {
    const val MAX_CIPHERTEXT_BYTES = 256 * 1024

    /**
     * Space for the sync envelope, wrapper metadata, JSON punctuation, and future fields.
     * This is deliberately much larger than the current envelope's serialized overhead.
     */
    const val MAX_SYNC_JSON_OVERHEAD_BYTES = 32 * 1024

    val MAX_BASE64URL_CIPHERTEXT_BYTES = base64UrlEncodedLength(MAX_CIPHERTEXT_BYTES)
    val MAX_REQUEST_BYTES = checkedBodyLimit()
    val MAX_RESPONSE_BYTES = checkedBodyLimit()

    private fun checkedBodyLimit(): Int {
        val limit = MAX_BASE64URL_CIPHERTEXT_BYTES.toLong() + MAX_SYNC_JSON_OVERHEAD_BYTES
        check(limit <= Int.MAX_VALUE) { "Account API body limit exceeds Int capacity" }
        return limit.toInt()
    }
}

internal fun base64UrlEncodedLength(decodedBytes: Int): Int {
    require(decodedBytes >= 0) { "Decoded byte count must not be negative" }
    val encodedBytes = (decodedBytes.toLong() * 4L + 2L) / 3L
    require(encodedBytes <= Int.MAX_VALUE) { "Encoded byte count exceeds Int capacity" }
    return encodedBytes.toInt()
}
