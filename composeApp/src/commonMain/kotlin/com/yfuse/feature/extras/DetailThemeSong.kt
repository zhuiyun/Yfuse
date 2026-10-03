package com.yfuse.feature.extras

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.rememberRouteVisibility
import com.yfuse.core.model.ThemeSong
import com.yfuse.feature.detail.DetailComponent
import com.yfuse.feature.player.ActivePlayback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 详情页主题曲: the title's theme music, faded in once its detail page has settled — only with the
 * setting on ([com.yfuse.core.data.PlaybackPreferences.detailThemeSong], off by default).
 *
 * Leaving the page fades it out, and coming back to the page plays it again. 播放, a player already
 * up, or the app leaving the foreground cut it at once instead, and the page stays silent from
 * then on: whoever has just watched the title does not need its theme played at them on the way out.
 * One song plays in the whole app at a time, and the platform side keeps to audio focus and to a
 * muted device — see [ThemeSongOutput].
 */
@Composable
fun DetailThemeSong(component: DetailComponent) {
    val enabled by component.dependencies.playbackPreferences.detailThemeSong
        .collectAsState()
    if (!enabled) return
    val state by component.store.states.collectAsState(component.store.state)
    val server = state.server ?: return
    val itemId = state.detail?.id ?: return
    PageThemeSong(
        pageKey = "${server.id}:$itemId",
        interrupted = state.resolvingPlay,
        load = { component.themeSongs(server, itemId) },
    )
}

@Composable
private fun PageThemeSong(
    pageKey: String,
    interrupted: Boolean,
    load: suspend () -> List<ThemeSong>,
) {
    val songs by produceState<List<ThemeSong>>(emptyList(), pageKey) { value = load() }
    val routeVisible by rememberRouteVisibility()
    val playerUp by remember { ActivePlayback.state.map { it.active }.distinctUntilChanged() }
        .collectAsState(ActivePlayback.state.value.active)
    // Saveable, so a page the viewer comes back to through the stack remembers it was cut short.
    var silenced by rememberSaveable(pageKey) { mutableStateOf(false) }
    var settled by remember(pageKey) { mutableStateOf(false) }

    LaunchedEffect(interrupted, playerUp) {
        if (interrupted || playerUp) {
            silenced = true
            silenceThemeSong()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    silenced = true
                    silenceThemeSong()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Settled: in view, and past its arrival — music starting under a page still sliding in
    // reads as the tap having made a noise.
    LaunchedEffect(pageKey, routeVisible) {
        settled = false
        if (routeVisible) {
            delay(THEME_SONG_SETTLE_MS)
            settled = true
        }
    }
    ThemeSongOutput(themeSongToPlay(songs, settled = settled && routeVisible, silenced = silenced))
}

/** The song the page should be playing now, if any: the first the server lists, once settled. */
internal fun themeSongToPlay(
    songs: List<ThemeSong>,
    settled: Boolean,
    silenced: Boolean,
): ThemeSong? = songs.firstOrNull()?.takeIf { settled && !silenced }

/**
 * Plays [song] while it is non-null — faded in, one song for the whole app — and fades it out when
 * it turns null or this leaves the composition. A song another page has since taken is left alone.
 */
@Composable
internal expect fun ThemeSongOutput(song: ThemeSong?)

/** Cuts the theme song at once, wherever it was started: a player is about to take the screen. */
internal expect fun silenceThemeSong()

/** After the page's own arrival, and long enough that passing through a page stays quiet. */
private const val THEME_SONG_SETTLE_MS = 1_200L
