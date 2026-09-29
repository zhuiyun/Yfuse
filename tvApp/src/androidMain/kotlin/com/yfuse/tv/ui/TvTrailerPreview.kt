package com.yfuse.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.designsystem.CALM_DURATION_SCALE
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.calmMotion
import com.yfuse.core.model.MediaTrailer
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.TmdbItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext

/**
 * S5 静音预告: once focus has rested on a hero for [TRAILER_PREVIEW_DWELL_MS], its still crossfades
 * into the title's own trailer, muted, over [TRAILER_PREVIEW_FADE_MS]; the moment focus leaves the
 * hero, the page goes, or the app is sent away, it is gone and the decoder with it.
 *
 * Only a file the library holds is previewed — never a video site's link — and one preview plays in
 * the whole app at a time (see TvTrailerPreviewPlayer). Nothing plays under 减少动画 or while the
 * device saves power, and the look-up behind it runs beside focus and never holds it: a viewer
 * passing over the hero has moved on before anything is asked of the server twice.
 *
 * Draw it over the hero's still and under its scrim, so the title stays as readable as before.
 * [previewKey] names the title (null when there is nothing to preview); [lookup] finds its trailer
 * once — the answer, "none" included, is kept for the session.
 */
@Composable
internal fun TvHeroTrailerPreview(
    previewKey: String?,
    focused: Boolean,
    lookup: suspend () -> MediaTrailer.Local?,
    modifier: Modifier = Modifier,
    /** 播放 is on its way: the player is about to take the one decoder a television may have. */
    suspended: Boolean = false,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val fadeMs = if (calmMotion()) (TRAILER_PREVIEW_FADE_MS * CALM_DURATION_SCALE).toInt() else TRAILER_PREVIEW_FADE_MS
    val powerSaving = rememberPowerSaveCheck()
    val resumed = rememberResumed()
    val latestLookup by rememberUpdatedState(lookup)
    var playing by remember(previewKey) { mutableStateOf<MediaTrailer.Local?>(null) }
    val wanted = previewKey != null && focused && resumed && !suspended && !reduceMotion
    LaunchedEffect(previewKey, wanted) {
        playing = null
        if (!wanted || previewKey == null) return@LaunchedEffect
        val found = async { TvTrailerPreviewCache.lookup(previewKey) { latestLookup() } }
        delay(TRAILER_PREVIEW_DWELL_MS)
        val trailer = found.await() ?: return@LaunchedEffect
        if (powerSaving()) return@LaunchedEffect
        playing = trailer
    }
    val trailer = playing ?: return
    key(trailer.streamUrl) {
        var firstFrame by remember { mutableStateOf(false) }
        val alpha by animateFloatAsState(
            targetValue = if (firstFrame) 1f else 0f,
            animationSpec = if (firstFrame) Motion.tween(fadeMs) else snap(),
            label = "tv-trailer-preview",
        )
        TvTrailerPreviewSurface(
            url = trailer.streamUrl,
            onFirstFrame = { firstFrame = true },
            // Played through: back to the still, and no second showing until focus comes again.
            onEnded = { playing = null },
            modifier = modifier.graphicsLayer { this.alpha = alpha },
        )
    }
}

/** A library title's own trailer on [server], for a hero showing that title. */
internal suspend fun libraryTrailerPreview(
    server: SavedServer,
    itemId: String,
): MediaTrailer.Local? =
    GlobalContext
        .get()
        .get<EmbyRepository>()
        .localTrailers(server, itemId)
        .firstOrNull()

/**
 * A TMDB title's trailer, through the copy the default server holds of it — 首页's 今日精选 are
 * TMDB's picks, and only a copy in the library has a trailer file to show.
 */
internal suspend fun tmdbTrailerPreview(item: TmdbItem): MediaTrailer.Local? {
    val koin = GlobalContext.get()
    val server = koin.get<ServerRegistry>().defaultServer ?: return null
    val repo = koin.get<EmbyRepository>()
    val copy = repo.findByTmdbId(server, item.id, item.mediaType).getOrNull() ?: return null
    return repo.localTrailers(server, copy.id).firstOrNull()
}

/** Titles already asked about, trailer or none, so resting on a hero twice asks the server once. */
internal object TvTrailerPreviewCache {
    private val lock = Mutex()
    private val found = LinkedHashMap<String, MediaTrailer.Local?>()

    suspend fun lookup(
        key: String,
        load: suspend () -> MediaTrailer.Local?,
    ): MediaTrailer.Local? {
        lock.withLock { if (found.containsKey(key)) return found[key] }
        // A look-up that fails costs the preview and nothing else; the answer is kept all the same.
        val trailer =
            try {
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                null
            }
        lock.withLock {
            found[key] = trailer
            // Oldest first out: a session browses more titles than it keeps coming back to.
            while (found.size > MAX_CACHED_TITLES) found.remove(found.keys.first())
        }
        return trailer
    }
}

/** Resumed, and so in front of the viewer: a paused television is showing something else. */
@Composable
private fun rememberResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> resumed = true
                    Lifecycle.Event.ON_PAUSE -> resumed = false
                    else -> Unit
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

/** 焦点停留 1.2 秒: long enough that moving across a hero never starts one. */
internal const val TRAILER_PREVIEW_DWELL_MS = 1_200L

/** 淡入 600 ms; under 静息 shortened as every other fade is. */
internal const val TRAILER_PREVIEW_FADE_MS = 600

private const val MAX_CACHED_TITLES = 64
