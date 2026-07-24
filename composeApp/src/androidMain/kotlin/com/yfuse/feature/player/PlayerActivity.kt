package com.yfuse.feature.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalGlass
import com.yfuse.core.designsystem.YfuseTheme
import com.yfuse.core.designsystem.glass
import com.yfuse.core.model.PlayerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.GlobalContext

/**
 * Fullscreen playback lives in its own activity so landscape is declared in the
 * manifest rather than forced at runtime (which misbehaves on some devices).
 */
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URLS = "yfuse.urls"
        private const val EXTRA_TRANSCODE = "yfuse.transcodeUrls"
        private const val EXTRA_TITLES = "yfuse.titles"
        private const val EXTRA_INDEX = "yfuse.index"
        private const val EXTRA_POSITION = "yfuse.positionMs"
        private const val EXTRA_ENGINE = "yfuse.engine"

        fun intent(
            context: Context,
            items: List<PlayerMediaItem>,
            startIndex: Int,
            startPositionMs: Long,
            engine: PlayerEngine,
        ): Intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(EXTRA_URLS, items.map { it.url }.toTypedArray())
            putExtra(EXTRA_TRANSCODE, items.map { it.transcodeUrl }.toTypedArray())
            putExtra(EXTRA_TITLES, items.map { it.title }.toTypedArray())
            putExtra(EXTRA_INDEX, startIndex)
            putExtra(EXTRA_POSITION, startPositionMs)
            putExtra(EXTRA_ENGINE, engine.name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val initialEngine = intent.getStringExtra(EXTRA_ENGINE)
            ?.let { name -> PlayerEngine.entries.firstOrNull { it.name == name } }
            ?: PlayerEngine.Exo

        val urls = intent.getStringArrayExtra(EXTRA_URLS).orEmpty()
        val transcodeUrls = intent.getStringArrayExtra(EXTRA_TRANSCODE).orEmpty()
        val titles = intent.getStringArrayExtra(EXTRA_TITLES).orEmpty()
        val items = urls.mapIndexed { index, url ->
            PlayerMediaItem(
                id = index.toString(),
                url = url,
                transcodeUrl = transcodeUrls.getOrElse(index) { "" },
                title = titles.getOrElse(index) { "" },
            )
        }

        val accent = runCatching {
            GlobalContext.get().get<ThemePreferences>().accent.value
        }.getOrDefault(AccentColor.Blue)

        setContent {
            // Always the dark palette: the controls float over the picture.
            YfuseTheme(dark = true, accent = accent) {
                PlayerRoot(
                    items = items,
                    startIndex = intent.getIntExtra(EXTRA_INDEX, 0),
                    startPositionMs = intent.getLongExtra(EXTRA_POSITION, 0L),
                    initialEngine = initialEngine,
                    onBack = { finish() },
                )
            }
        }
    }
}

/**
 * Owns the live engine and the shared control layer. Switching engines reads
 * the outgoing engine's position first, so the replacement picks up where it
 * left off instead of restarting the entry.
 */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerRoot(
    items: List<PlayerMediaItem>,
    startIndex: Int,
    startPositionMs: Long,
    initialEngine: PlayerEngine,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var kind by remember { mutableStateOf(initialEngine) }
    // Where a newly built engine should start: index + position, updated on
    // every handover so the switch is seamless.
    var resume by remember { mutableStateOf(startIndex to startPositionMs) }
    var filled by remember { mutableStateOf(false) }

    val engine: VideoEngine = remember(kind, resume) {
        when (kind) {
            PlayerEngine.Mpv -> MpvVideoEngine(context, items, resume.first, resume.second)
            else -> ExoVideoEngine(context, items, resume.first, resume.second, scope)
        }
    }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    val state by engine.state.collectAsState()

    fun switchEngine(target: PlayerEngine) {
        if (target == kind) return
        // Read the position before the old engine is torn down.
        engine.pause()
        resume = state.currentIndex to engine.currentPositionMs()
        kind = target
    }

    val exo = engine as? ExoVideoEngine
    val idleTranscoding = remember { MutableStateFlow(false) }
    val transcoding by (exo?.transcoding ?: idleTranscoding).collectAsState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (engine) {
            is MpvVideoEngine -> MpvSurface(engine, Modifier.fillMaxSize())
            is ExoVideoEngine -> ExoSurface(engine, filled, Modifier.fillMaxSize())
        }

        PlayerControls(
            state = state,
            titles = items.map { it.title },
            filled = filled,
            onBack = onBack,
            onPlayPause = { if (state.playing) engine.pause() else engine.play() },
            onSeek = engine::seekTo,
            onSelectItem = engine::selectItem,
            onSelectAudio = engine::selectAudioTrack,
            onSelectSubtitle = engine::selectSubtitleTrack,
            onSpeed = engine::setSpeed,
            onToggleFill = {
                filled = !filled
                (engine as? MpvVideoEngine)?.setFill(filled)
            },
            topBarExtras = {
                PlayerEngine.selectable.forEach { candidate ->
                    TopPill(
                        label = candidate.label,
                        selected = candidate == kind,
                        onClick = { switchEngine(candidate) },
                    )
                }
                // Manual escape hatch when the picture is black but audio plays.
                if (exo != null) {
                    TopPill(
                        label = if (transcoding) "转码中" else "转码",
                        selected = transcoding,
                        onClick = { exo.switchToTranscode() },
                    )
                }
            },
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ExoSurface(engine: ExoVideoEngine, filled: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                keepScreenOn = true
            }
        },
        update = { view ->
            // Reassigned on update too: a fresh engine reuses this same view.
            if (view.player !== engine.player) view.player = engine.player
            view.resizeMode = if (filled) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = modifier,
    )
}

/** Small glass pill for the top-bar extras (engine picker, transcode). */
@Composable
private fun RowScope.TopPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val glass = LocalGlass.current
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .glass(GlassShapes.pill, strong = selected)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) accent else glass.onGlass,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
