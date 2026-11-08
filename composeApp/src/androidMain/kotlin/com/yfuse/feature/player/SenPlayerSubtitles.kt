package com.yfuse.feature.player

import android.content.Context
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.embyPlaybackHeaders
import com.yfuse.core.platform.AppBuildConfig
import com.yfuse.core2.android.AndroidExternalSubtitleLoader
import com.yfuse.core2.android.catalogTrack
import com.yfuse.core2.android.defaultExternalSubtitleId
import com.yfuse.core2.android.externalSubtitleHeaders
import com.yfuse.core2.android.externalSubtitleSources
import com.yfuse.core2.android.externalSubtitleTrackId
import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.api.YInitialTrackSelection
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.matchingPreference
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.senplayer.SenPlayerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** State belongs to the player's serial worker. Only bounded sidecar reads run on IO. */
internal class SenPlayerSubtitles(
    private val context: Context,
    private val scope: CoroutineScope,
    private val refresh: () -> Unit,
    private val fatal: (Exception) -> Unit,
) {
    private val loader = AndroidExternalSubtitleLoader(context)
    private var session: SenPlayerSession? = null
    private var sources = emptyMap<String, YExternalSubtitleSource>()
    private var headersFor: (String) -> Map<String, String> = { emptyMap() }
    private val registered = mutableMapOf<String, String>()
    private val files = mutableListOf<File>()
    private val pending = mutableMapOf<String, Job>()
    private val requested = mutableMapOf<Int, String>()
    private var initial: YInitialTrackSelection? = null
    private var initialPending = false
    private var secondaryActive = false
    private var applied: Pair<SenSubtitleStyle, Boolean>? = null
    var style = SenSubtitleStyle()
        private set

    fun open(
        current: SenPlayerSession,
        item: PlayerMediaItem,
        userAgent: String,
        selection: YInitialTrackSelection?,
    ) {
        clear()
        session = current
        sources =
            item
                .externalSubtitleSources()
                .mapIndexed {
                        index,
                        source,
                    ->
                    externalSubtitleTrackId(index) to source
                }.toMap()
        initial = selection
        initialPending = true
        val custom = item.customHeaders?.values.orEmpty()
        val agent = userAgent.takeIf(String::isNotBlank)?.let { mapOf("User-Agent" to it) }.orEmpty()
        val headers = agent + embyPlaybackHeaders(item.url) { AppBuildConfig.VERSION_NAME } + custom
        headersFor = { target -> externalSubtitleHeaders(item.url, headers, custom.keys, target) }
    }

    fun clear() {
        session = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        requested.clear()
        registered.clear()
        files.forEach { it.delete() }
        files.clear()
        sources = emptyMap()
        headersFor = { emptyMap() }
        initialPending = false
        secondaryActive = false
        initial = null
        applied = null
    }

    fun surfaceChanged() {
        applied = null
    }

    fun setStyle(value: SenSubtitleStyle) {
        if (style == value) return
        style = value
        applied = null
        session?.let { applyStyle(it, secondaryActive) }
    }

    fun observeSecondary(active: Boolean) {
        secondaryActive = active
        session?.let { applyStyle(it, active) }
    }

    private fun applyStyle(
        current: SenPlayerSession,
        secondary: Boolean,
    ) {
        val next = style to secondary
        if (applied == next) return
        applied = next
        try {
            style.configureRenderer(current, secondary)
            AppLog.info(
                "player.senplayer",
                "subtitle_renderer_ready",
                "Subtitle configuration accepted",
                mapOf("secondary" to secondary.toString()),
            )
        } catch (error: Exception) {
            if (!current.isUsable()) throw error
            reportFailure("configure", error)
            return
        }
        try {
            style.applyPosition(current)
        } catch (error: Exception) {
            if (!current.isUsable()) throw error
            AppLog.warning(
                "player.senplayer",
                "subtitle_position_rejected",
                "Native renderer kept its subtitle placement",
                attributes = mapOf("exceptionType" to error.javaClass.simpleName),
            )
        }
    }

    fun select(
        slot: Int,
        id: String,
    ) {
        val current = session ?: return
        if (slot == PRIMARY) initialPending = false
        requested[slot] = id
        pending.keys.filterNot(requested::containsValue).forEach { pending.remove(it)?.cancel() }
        val source = sources[id]
        val nativeId = registered[id]
        if (source == null || nativeId != null) {
            if (source == null && id != EngineTrack.OFF && id != "auto" && id.toLongOrNull() == null) return
            selectNative(current, slot, nativeId ?: id)
            return
        }
        if (id in pending) return
        val headers = headersFor(source.uri)
        // Native addSubtitle accepts a filesystem path. Stage exactly the decoded document
        // validated by the same bounded, cancellable loader YCore uses, preserving ASS styles.
        pending[id] =
            scope.launch {
                var file: File? = null
                try {
                    withContext(Dispatchers.IO) {
                        val document = loader.readDocument(source, headers)
                        val extension =
                            when (document.format) {
                                YSubtitleFormat.WebVtt -> "vtt"
                                YSubtitleFormat.MicroDvd -> "sub"
                                else -> document.format.name.lowercase()
                            }
                        val staged = File.createTempFile("sen-sub-", ".$extension", context.cacheDir)
                        file = staged
                        staged.writeText(document.text)
                    }
                    if (session !== current || id !in requested.values) return@launch
                    val staged = requireNotNull(file)
                    val native = current.addSubtitle(staged.absolutePath, source.catalogTrack(id).label)
                    registered[id] = native
                    files += staged
                    file = null
                    requested.filterValues { it == id }.keys.forEach { selectNative(current, it, native) }
                    refresh()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (session === current && id in requested.values) {
                        if (!current.isUsable()) fatal(error) else reportFailure("load", error)
                    }
                } finally {
                    file?.delete()
                    // A cancelled job from a previous title can share the same catalog id.
                    if (pending[id] === coroutineContext[Job]) pending.remove(id)
                }
            }
    }

    private fun selectNative(
        current: SenPlayerSession,
        slot: Int,
        id: String,
    ) {
        try {
            current.track(slot, id)
            applied = null
            if (slot == SECONDARY) secondaryActive = id != EngineTrack.OFF
            applyStyle(current, secondaryActive)
            refresh()
        } catch (error: Exception) {
            if (!current.isUsable()) throw error
            reportFailure("select", error)
        }
    }

    fun selectDefault(tracks: List<EngineTrack>) {
        if (!initialPending || tracks.isEmpty()) return
        initialPending = false
        val preference = initial?.subtitle
        val id =
            when {
                initial?.subtitlesDisabled == true -> EngineTrack.OFF
                preference != null ->
                    tracks
                        .map { YTrack(it.id, YTrackType.Subtitle, it.label, it.language, it.codec, it.selected) }
                        .matchingPreference(preference)
                        ?.id
                else -> sources.defaultExternalSubtitleId()
            }
        if (id != null) select(PRIMARY, id)
    }

    fun publicId(native: String): String = registered.entries.firstOrNull { it.value == native }?.key ?: native

    fun merge(native: List<EngineTrack>): List<EngineTrack> {
        val selected = native.firstOrNull { it.selected }?.id
        return native.filterNot { it.id in sources } +
            sources.map { (id, source) ->
                val track = source.catalogTrack(id)
                EngineTrack(id, track.label, track.language, id == selected, track.codec, source.forced)
            }
    }

    private fun reportFailure(
        stage: String,
        error: Exception,
    ) {
        AppLog.warning(
            "player.senplayer",
            "subtitle_operation_failed",
            "Subtitle operation failed",
            attributes = mapOf("stage" to stage, "exceptionType" to error.javaClass.simpleName),
        )
        PlayerNotices.show(if (stage == "load") "外挂字幕加载失败，可重新选择或导入本地文件" else "字幕未能应用，请重新选择字幕轨")
    }

    companion object {
        const val PRIMARY = 1
        const val SECONDARY = 2
    }
}

internal data class SenSubtitleStyle(
    val primaryScale: Float = 1f,
    val secondaryScale: Float = 1f,
    val primaryDelayMs: Long = 0L,
    val secondaryDelayMs: Long = 0L,
    val appearance: SubtitleAppearance = SubtitleAppearance(),
    val brightness: Float = 1f,
    val position: Float = DEFAULT_SUBTITLE_POSITION,
    val useBlackBar: Boolean = false,
) {
    fun configureRenderer(
        session: SenPlayerSession,
        secondary: Boolean,
    ) {
        fun rgba(argb: Long): String =
            String.format(Locale.ROOT, "#%08X", ((argb and 0xFFFFFF) shl 8) or ((argb ushr 24) and 0xFF))
        val styled = appearance.withBrightness(brightness)
        session.configureSubtitles(
            secondary,
            primaryScale.toDouble(),
            secondaryScale.toDouble(),
            primaryDelayMs / 1000.0,
            secondaryDelayMs / 1000.0,
            rgba(styled.textColorArgb),
            rgba(styled.outlineColorArgb),
            rgba(styled.backgroundColorArgb),
            appearance.outlineWidth.toDouble(),
            styled != SubtitleAppearance(),
        )
    }

    fun applyPosition(session: SenPlayerSession) {
        session.option("sub-use-margins", if (useBlackBar) "yes" else "no")
        val percent = (if (useBlackBar) 100 else (position.coerceIn(0.6f, 0.96f) * 100).toInt()).toString()
        session.option("sub-pos", percent)
    }
}
