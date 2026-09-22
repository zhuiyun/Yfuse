package com.yfuse.core.security

/** RFC 4648 URL-safe Base64 without `=` padding. */
expect fun ByteArray.toBase64Url(): String

/** Decodes canonical RFC 4648 URL-safe Base64 without accepting padding or other alphabets. */
expect fun String.base64UrlToBytes(): ByteArray
