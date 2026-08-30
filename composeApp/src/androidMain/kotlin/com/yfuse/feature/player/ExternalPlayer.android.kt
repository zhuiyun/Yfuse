package com.yfuse.feature.player

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * User-triggered escape hatch for a representation the in-app engines cannot render.
 *
 * The authenticated URL is handed directly to Android's chosen player and is never logged or
 * copied to an intermediate file. Only media-safe schemes are allowed.
 */
internal fun openExternalPlayer(
    context: Context,
    mediaUrl: String,
    title: String,
): Boolean {
    val uri = runCatching { Uri.parse(mediaUrl) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase() !in setOf("http", "https", "content", "file")) return false
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_TITLE, title)
        }
    if (intent.resolveActivity(context.packageManager) == null) return false
    context.startActivity(Intent.createChooser(intent, "选择外部播放器"))
    return true
}
