package com.yfuse.feature.player

import android.app.AlertDialog
import android.content.ActivityNotFoundException
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
    positionMs: Long = 0L,
    headers: Map<String, String> = emptyMap(),
): Boolean {
    val uri = runCatching { Uri.parse(mediaUrl) }.getOrNull() ?: return false
    if (uri.scheme?.lowercase() !in setOf("http", "https", "content", "file")) return false
    val intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_TITLE, title)
            // The de-facto handover contract MX Player, VLC and Just Player all read: a title,
            // the position to start from, and request headers as alternating key/value pairs.
            putExtra("title", title)
            if (positionMs > 0L) putExtra("position", positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            if (headers.isNotEmpty()) {
                putExtra("headers", headers.flatMap { (key, value) -> listOf(key, value) }.toTypedArray())
            }
        }
    // No `resolveActivity` gate: since Android 11 it answers null for every package the
    // manifest's <queries> does not name, and the chooser itself reports the empty case.
    return try {
        context.startActivity(Intent.createChooser(intent, "选择外部播放器"))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private const val HANDOFF_PREFERENCES = "player_external_handoff"
private const val CREDENTIAL_HANDOFF_ACKNOWLEDGED = "credential_handoff_acknowledged"

/**
 * [openExternalPlayer], asking first - once per device - when [mediaUrl] carries the viewer's
 * sign-in (an Emby/Jellyfin api_key, a Plex token, a password in the address). The other app can
 * use it for as long as it stays valid, so the viewer should know before it leaves.
 *
 * Handing over a loopback address of Yfuse's own proxy instead would keep the credential here, but
 * that proxy lives only as long as this player - and Yfuse's process, which the system may end once
 * the other app is in front - so the other player's playback would stop partway. The confirmation is
 * the option that always works.
 */
internal fun openExternalPlayerConfirmingCredential(
    context: Context,
    mediaUrl: String,
    title: String,
    positionMs: Long,
    headers: Map<String, String>,
    onUnavailable: () -> Unit,
) {
    val open = {
        if (!openExternalPlayer(context, mediaUrl, title, positionMs, headers)) onUnavailable()
    }
    if (!mediaUrlCarriesCredential(mediaUrl)) {
        open()
        return
    }
    val preferences = context.getSharedPreferences(HANDOFF_PREFERENCES, Context.MODE_PRIVATE)
    if (preferences.getBoolean(CREDENTIAL_HANDOFF_ACKNOWLEDGED, false)) {
        open()
        return
    }
    AlertDialog
        .Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle("外部播放器会拿到登录凭据")
        .setMessage(
            "这个视频链接带有你的服务器登录凭据。交给外部播放器后，在凭据有效期间那个应用都能用它访问你的媒体库。" +
                "只在你信任它时继续；这台设备以后不再询问。",
        ).setPositiveButton("继续") { _, _ ->
            preferences.edit().putBoolean(CREDENTIAL_HANDOFF_ACKNOWLEDGED, true).apply()
            open()
        }.setNegativeButton("取消", null)
        .show()
}
