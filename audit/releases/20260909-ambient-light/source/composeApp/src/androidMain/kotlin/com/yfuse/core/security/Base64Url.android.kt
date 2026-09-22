package com.yfuse.core.security

import java.util.Base64

actual fun ByteArray.toBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

actual fun String.base64UrlToBytes(): ByteArray {
    require(length <= MAX_BASE64URL_INPUT_CHARS) {
        "Base64URL input exceeds $MAX_BASE64URL_INPUT_CHARS characters"
    }
    require(length % 4 != 1) { "Invalid Base64URL length" }
    require(all(::isBase64UrlCharacter)) {
        "Base64URL must use the URL-safe alphabet without padding"
    }
    val decoded =
        try {
            Base64.getUrlDecoder().decode(this)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid Base64URL input", error)
        }
    require(decoded.toBase64Url() == this) { "Base64URL input is not canonical" }
    return decoded
}

private fun isBase64UrlCharacter(value: Char): Boolean =
    value in 'A'..'Z' ||
        value in 'a'..'z' ||
        value in '0'..'9' ||
        value == '-' ||
        value == '_'

private const val MAX_BASE64URL_INPUT_CHARS = 4 * 1024 * 1024
