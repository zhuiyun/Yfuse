package com.yfuse.feature.extras

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import com.yfuse.core.model.MediaTrailer
import com.yfuse.feature.player.PlayerLauncher
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.trailerPlaybackItem

/**
 * What a 预告片 key does with the trailer it is handed, on the phone and the television alike.
 *
 * A file the library holds plays in the app's own player, as an entry that reports to no server
 * (see [trailerPlaybackItem]). A link a server scraped opens in whatever app the system has for
 * it — the video site's own app, or a browser; nothing here fetches a stream out of a video site.
 */
@Stable
internal class TrailerLauncher(
    /** Hands [url] to the system; false when nothing on the device could open it. */
    private val openLink: (url: String) -> Boolean,
) {
    /** The entry waiting to be handed to the player; see [TrailerLaunchEffect]. */
    var queued: PlayerMediaItem? by mutableStateOf(null)
        private set

    /** Why the last link did not open, for the page to say; null once said. */
    var problem: String? by mutableStateOf(null)
        private set

    fun open(
        trailer: MediaTrailer,
        ownerTitle: String,
    ) {
        when (trailer) {
            is MediaTrailer.Local -> {
                // The player is about to take the screen: the page's theme song stops now.
                silenceThemeSong()
                queued = trailerPlaybackItem(trailer.streamUrl, trailerPlayerTitle(ownerTitle, trailer.title))
            }
            is MediaTrailer.Remote ->
                if (!openLink(trailer.url)) problem = "没有可以打开${inlineName(trailer.site)}链接的应用"
        }
    }

    fun launched() {
        queued = null
    }

    fun problemShown() {
        problem = null
    }
}

@Composable
internal fun rememberTrailerLauncher(): TrailerLauncher {
    val uriHandler = LocalUriHandler.current
    // ACTION_VIEW on Android; a device without an app for the address throws, which is the answer.
    return remember(uriHandler) { TrailerLauncher { url -> runCatching { uriHandler.openUri(url) }.isSuccess } }
}

/** Hands a queued trailer to the player. Placed once, on the page that owns [launcher]. */
@Composable
internal fun TrailerLaunchEffect(launcher: TrailerLauncher) {
    val item = launcher.queued ?: return
    // A launch that fails leaves the entry queued; the next press replaces it and tries again.
    PlayerLauncher(items = listOf(item), startIndex = 0, startPositionMs = 0L, onLaunched = launcher::launched)
}

/** The player's title for a trailer: `花样年华 · 预告片`, or the trailer's own name when it says more. */
internal fun trailerPlayerTitle(
    ownerTitle: String,
    trailerTitle: String,
): String {
    val owner = ownerTitle.trim()
    return when {
        owner.isEmpty() -> trailerTitle
        trailerTitle.contains(owner, ignoreCase = true) -> trailerTitle
        else -> "$owner · $trailerTitle"
    }
}

/** The line under a trailer's name where several are listed: where it plays, and for how long. */
internal fun trailerDescription(trailer: MediaTrailer): String =
    when (trailer) {
        is MediaTrailer.Local ->
            listOfNotNull("在应用内播放", trailer.durationMs?.let(::trailerDurationLabel)).joinToString(" · ")
        is MediaTrailer.Remote -> "在${inlineName(trailer.site)}打开"
    }

/** A name set into a Chinese sentence: a Latin one (`YouTube`) gets a space on each side, 哔哩哔哩 none. */
internal fun inlineName(name: String): String {
    val latinStart = name.firstOrNull()?.let { it.code < ASCII_END && it.isLetterOrDigit() } == true
    val latinEnd = name.lastOrNull()?.let { it.code < ASCII_END && it.isLetterOrDigit() } == true
    return (if (latinStart) " " else "") + name + (if (latinEnd) " " else "")
}

private const val ASCII_END = 0x80

/** `2:05`; a trailer is minutes long, so hours never show. */
internal fun trailerDurationLabel(durationMs: Long): String? {
    val seconds = (durationMs / 1_000L).takeIf { it > 0L } ?: return null
    return "${seconds / 60L}:${(seconds % 60L).toString().padStart(2, '0')}"
}
