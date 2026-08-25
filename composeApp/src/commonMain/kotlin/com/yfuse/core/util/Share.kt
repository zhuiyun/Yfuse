package com.yfuse.core.util

import androidx.compose.runtime.Composable

/**
 * Sends text out of the app (system share sheet) or onto the clipboard.
 *
 * Watch-together invites are the only caller so far: the whole point of an invite is that it
 * leaves Yfuse and lands in whatever messenger the two people already use.
 */
interface ShareHandler {
    fun shareText(text: String)

    /** Shares a standards-compliant .ics attachment when the platform supports files. */
    fun shareCalendar(content: String) {
        shareText(content)
    }

    fun copyText(text: String)

    fun copySensitiveText(text: String)

    fun copyRoomCode(roomCode: String)
}

@Composable
expect fun rememberShareHandler(): ShareHandler
