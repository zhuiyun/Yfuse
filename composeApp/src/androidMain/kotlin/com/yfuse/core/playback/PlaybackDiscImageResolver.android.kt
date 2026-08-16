package com.yfuse.core.playback

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

private object LocalDiscImageKindCache {
    private val values =
        LinkedHashMap<String, PlaybackDiscKind>(MAX_DISC_IMAGE_CACHE_ENTRIES, 0.75f, true)

    fun get(uri: String): PlaybackDiscKind? = synchronized(values) { values[uri.cacheKey()] }

    fun put(
        uri: String,
        kind: PlaybackDiscKind,
    ) = synchronized(values) {
        values[uri.cacheKey()] = kind
        while (values.size > MAX_DISC_IMAGE_CACHE_ENTRIES) values.remove(values.keys.first())
    }
}

/** Returns a cached classification without doing disk I/O on the playback thread. */
internal fun cachedLocalPlaybackDiscKind(uri: String): PlaybackDiscKind? =
    LocalDiscImageKindCache.get(uri)

/** Performs bounded disk I/O. The caller must run this on an I/O dispatcher. */
internal fun resolveLocalPlaybackDiscKind(
    context: Context,
    uri: String,
    declaredKind: PlaybackDiscKind,
): PlaybackDiscKind {
    if (declaredKind != PlaybackDiscKind.Iso || !uri.startsWith("file://", ignoreCase = true)) {
        return declaredKind
    }
    cachedLocalPlaybackDiscKind(uri)?.let { return it }
    val resolved =
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val output = ByteArrayOutputStream(DISC_IMAGE_READ_BUFFER_BYTES)
                val buffer = ByteArray(DISC_IMAGE_READ_BUFFER_BYTES)
                var remaining = MAX_DISC_IMAGE_INSPECTION_BYTES
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    remaining -= count
                }
                detectPlaybackDiscImageKind(output.toByteArray())
            }
        }.getOrNull() ?: declaredKind
    LocalDiscImageKindCache.put(uri, resolved)
    return resolved
}

private fun String.cacheKey(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

private const val MAX_DISC_IMAGE_INSPECTION_BYTES = 8 * 1024 * 1024
private const val DISC_IMAGE_READ_BUFFER_BYTES = 64 * 1024
private const val MAX_DISC_IMAGE_CACHE_ENTRIES = 12
