package com.yfuse.core2.android

import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.embyPlaybackHeaders
import com.yfuse.core.platform.AppBuildConfig
import com.yfuse.core2.api.YExternalSubtitleSource
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.feature.player.externalSubtitleFormatHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Owner-thread catalog; IO completions return through the player's command queue. */
internal class AndroidExternalSubtitleSession(
    private val scope: CoroutineScope,
    private val load: suspend (YExternalSubtitleSource, Map<String, String>, String) -> AndroidLoadedExternalSubtitle,
    private val completed: (Completion) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Completion(
        val generation: Long,
        val id: String,
        val subtitle: AndroidLoadedExternalSubtitle?,
        val errorType: String? = null,
    )

    private var generation = 0L
    private var sources = emptyMap<String, YExternalSubtitleSource>()
    private var headersFor: (String) -> Map<String, String> = { emptyMap() }
    private val jobs = mutableMapOf<String, Job>()
    private val loaded = mutableSetOf<String>()
    private val permits = Semaphore(2)
    var tracks = emptyList<AndroidLoadedExternalSubtitle>()
        private set
    var defaultId: String? = null
        private set

    fun reset(item: YMediaItem) = reset(item.allExternalSubtitles, item.headers, item.uri, item.originBoundHeaderNames)

    fun reset(
        sources: List<YExternalSubtitleSource>,
        headers: Map<String, String>,
        origin: String? = null,
        originBoundNames: Set<String> = emptySet(),
    ) {
        close()
        headersFor = { target -> externalSubtitleHeaders(origin, headers, originBoundNames, target) }
        this.sources = sources.mapIndexed { index, source -> externalSubtitleTrackId(index) to source }.toMap()
        tracks =
            this.sources.map { (id, source) ->
                AndroidLoadedExternalSubtitle(
                    source.catalogTrack(id),
                    emptyList(),
                )
            }
        defaultId = this.sources.defaultExternalSubtitleId()
    }

    fun request(id: String?) {
        if (id == null || id in loaded || jobs[id]?.isActive == true) return
        val source = sources[id] ?: return
        val generation = generation
        val headers = headersFor(source.uri)
        jobs[id] =
            scope.launch(dispatcher) {
                val result =
                    try {
                        permits.withPermit { Completion(generation, id, load(source, headers, id)) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        Completion(generation, id, null, error.javaClass.simpleName)
                    }
                currentCoroutineContext().ensureActive()
                completed(result)
            }
    }

    fun accept(result: Completion): Boolean {
        if (result.generation != generation || result.id !in sources) return false
        jobs.remove(result.id)
        // Failures are terminal until the user selects this track again, not a retry per video frame.
        loaded += result.id
        result.errorType?.let { type ->
            val source = sources[result.id]
            val scheme = source?.uri?.substringBefore(':')
            val safeScheme = scheme?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9+.-]{0,31}")) }
            val formatHint = source?.format?.name ?: source?.uri?.let(::externalSubtitleFormatHint)
            AppLog.warning(
                category = "player.core2",
                event = "external_subtitle_load_failed",
                message = "External subtitle failed; video playback continues",
                attributes =
                    mapOf(
                        "trackId" to result.id,
                        "exceptionType" to type,
                        "sourceScheme" to safeScheme.orEmpty(),
                        "formatHint" to formatHint.orEmpty(),
                    ),
            )
        }
        result.subtitle?.let { subtitle -> tracks = tracks.map { if (it.track.id == result.id) subtitle else it } }
        return true
    }

    fun retry(id: String?) {
        if (id != null && tracks.firstOrNull { it.track.id == id }?.cues.isNullOrEmpty()) loaded.remove(id)
    }

    fun close() {
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        headersFor = { emptyMap() }
        loaded.clear()
        sources = emptyMap()
        tracks = emptyList()
        defaultId = null
    }
}

internal fun Map<String, YExternalSubtitleSource>.defaultExternalSubtitleId(): String? =
    entries.firstOrNull { it.value.forced || it.value.default }?.key ?: keys.singleOrNull()

internal fun YExternalSubtitleSource.catalogTrack(id: String): YTrack {
    val codec = format?.name ?: externalSubtitleFormatHint(uri)
    val label = listOfNotNull(language?.takeIf(String::isNotBlank), codec?.uppercase(), "外挂字幕").joinToString(" · ")
    return YTrack(id, YTrackType.Subtitle, label, language, codec)
}

internal fun externalSubtitleHeaders(
    origin: String?,
    headers: Map<String, String>,
    originBoundNames: Set<String>,
    target: String,
): Map<String, String> =
    scopedMediaHeaders(headers, origin, target, originBoundNames) +
        embyPlaybackHeaders(target) { AppBuildConfig.VERSION_NAME }
