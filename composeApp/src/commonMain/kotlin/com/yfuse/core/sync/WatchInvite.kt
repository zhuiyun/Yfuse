package com.yfuse.core.sync

/**
 * A share-able pointer to a watch-together room.
 *
 * The room code alone is not enough to make joining one tap: the guest's copy of the film
 * lives on *their* server under *their* item id, so the invite has to carry enough to
 * resolve it locally. [mediaKey] is the room's cross-server identity (`tmdb:…`, `imdb:…`,
 * or `emby:…` as a same-server fallback — see `PlayerStore.watchKey`) and [title] lets the
 * confirmation sheet name the film before that lookup has finished.
 *
 * [endpoint] is optional and only present when the host is not on the default relay. It is
 * deliberately surfaced to the guest before use rather than applied silently: a link is an
 * untrusted input, and the relay it names learns what its users are watching.
 */
data class WatchInvite(
    val roomCode: String,
    val mediaKey: String? = null,
    val title: String? = null,
    val endpoint: String? = null,
) {
    /** The deep link half of a share — paired with human-readable text by [shareText]. */
    fun toUri(): String = buildString {
        append(SCHEME)
        append("://")
        append(HOST)
        append('/')
        append(roomCode)
        val params = buildList {
            mediaKey?.let { add("k=" + encodeComponent(it)) }
            title?.let { add("t=" + encodeComponent(it)) }
            endpoint?.let { add("e=" + encodeComponent(it)) }
        }
        if (params.isNotEmpty()) {
            append('?')
            append(params.joinToString("&"))
        }
    }

    /**
     * What actually gets pasted into a chat app. The link carries the one-tap path; the
     * spelled-out code is the fallback for anyone whose messenger won't linkify a custom
     * scheme, or who doesn't have the app installed yet (there's no domain to hang an
     * Android App Link off, so the scheme is all we have).
     */
    fun shareText(): String = buildString {
        append("用 Yfuse 一起看")
        title?.let { append("《$it》") }
        append("\n房间码 ")
        append(roomCode)
        append('\n')
        append(toUri())
    }

    companion object {
        const val SCHEME = "yfuse"
        const val HOST = "watch"
        const val CODE_LENGTH = 6

        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        /** Normalizes user-typed input: strips separators, upper-cases, drops characters
         *  that aren't in the server's room-code alphabet (so `O`/`0` confusion fails fast
         *  and visibly rather than silently joining nothing). */
        fun normalizeCode(raw: String): String =
            raw.uppercase().filter { it in CODE_ALPHABET }.take(CODE_LENGTH)

        fun isCompleteCode(raw: String): Boolean = normalizeCode(raw).length == CODE_LENGTH

        /**
         * Parses a `yfuse://watch/CODE?...` link. Returns null for anything that isn't one,
         * including a well-formed link whose code isn't a plausible room code.
         *
         * Hand-rolled rather than using a URI parser because this lives in `commonMain`,
         * where `java.net.URI` isn't available.
         */
        fun parse(raw: String): WatchInvite? {
            val trimmed = raw.trim()
            val prefix = "$SCHEME://$HOST/"
            if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
            val body = trimmed.removeRange(0, prefix.length)
            val query = body.substringAfter('?', "")
            val code = normalizeCode(body.substringBefore('?'))
            if (code.length != CODE_LENGTH) return null

            val params = query.split('&')
                .filter { it.isNotBlank() }
                .mapNotNull { pair ->
                    val key = pair.substringBefore('=')
                    val value = pair.substringAfter('=', "")
                    if (key.isBlank()) null else key to decodeComponent(value)
                }
                .toMap()

            return WatchInvite(
                roomCode = code,
                mediaKey = params["k"]?.takeIf { it.isNotBlank() },
                title = params["t"]?.takeIf { it.isNotBlank() },
                endpoint = params["e"]?.takeIf { it.isNotBlank() },
            )
        }

        /**
         * Finds an invite inside arbitrary shared text — the whole [shareText] block pasted
         * from a chat app, or just a bare 6-character code someone typed out. Used by the
         * paste-to-join entry so users don't have to surgically extract the link.
         */
        fun parseFromText(raw: String): WatchInvite? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null

            val linkStart = trimmed.indexOf("$SCHEME://$HOST/", ignoreCase = true)
            if (linkStart >= 0) {
                val candidate = trimmed.substring(linkStart).takeWhile { !it.isWhitespace() }
                parse(candidate)?.let { return it }
            }

            // A bare code, possibly surrounded by other words ("房间码 ABC123").
            trimmed.split(' ', '\n', '\t', '\r', '：', ':')
                .map { normalizeCode(it) }
                .firstOrNull { it.length == CODE_LENGTH }
                ?.let { return WatchInvite(roomCode = it) }

            return null
        }
    }
}

/**
 * Minimal percent-encoding for the invite's query values. Only `commonMain`-safe APIs are
 * available here, so this encodes conservatively: anything outside an unreserved ASCII set
 * (including all non-ASCII, which titles are full of) becomes UTF-8 percent-escapes.
 */
private fun encodeComponent(value: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    return buildString {
        value.encodeToByteArray().forEach { byte ->
            val char = byte.toInt().toChar()
            if (byte >= 0 && char in unreserved) {
                append(char)
            } else {
                append('%')
                append(HEX_DIGITS[(byte.toInt() shr 4) and 0x0F])
                append(HEX_DIGITS[byte.toInt() and 0x0F])
            }
        }
    }
}

private fun decodeComponent(value: String): String {
    if ('%' !in value && '+' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        when (val char = value[index]) {
            '%' -> {
                val hex = value.drop(index + 1).take(2)
                val byte = hex.toIntOrNull(16)
                if (hex.length == 2 && byte != null) {
                    bytes.add(byte.toByte())
                    index += 3
                } else {
                    // Malformed escape — keep the literal character rather than dropping
                    // data, so a mangled link still shows something recognizable.
                    bytes.add(char.code.toByte())
                    index++
                }
            }
            '+' -> {
                bytes.add(' '.code.toByte())
                index++
            }
            else -> {
                bytes.add(char.code.toByte())
                index++
            }
        }
    }
    return bytes.toByteArray().decodeToString()
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * The room's cross-server identity for a title.
 *
 * Prefers an external provider id so two people whose Emby servers hold different files can
 * still be recognised as watching the same thing; falls back to `emby:<id>`, which only
 * matches on the server it came from.
 *
 * Known limitation: for an *episode*, provider ids are frequently absent, so the key
 * degrades to the server-specific fallback. Cross-server watch-together therefore works
 * reliably for films and same-server-only for most series; the player surfaces an explicit
 * sync warning when that fallback cannot be matched instead of silently doing nothing.
 */
fun Map<String, String>.watchKey(fallbackId: String): String {
    val preferred = listOf("Tmdb", "Tvdb", "Imdb")
    for (provider in preferred) {
        entries.firstOrNull { it.key.equals(provider, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
            ?.let { return "${provider.lowercase()}:$it" }
    }
    return "emby:$fallbackId"
}
