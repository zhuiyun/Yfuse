package com.yfuse.feature.filesource

import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FileSourceProgress
import com.yfuse.core.filesource.FileSourceProgressRecorder
import com.yfuse.core.filesource.FileSourceQueuePlan
import com.yfuse.core.filesource.LOG_CATEGORY
import com.yfuse.core.filesource.fileBaseName
import com.yfuse.core.filesource.isFileSourceItemId
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.feature.player.PlaybackState
import com.yfuse.feature.player.PlayerExternalSubtitle
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerMediaVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A queue entry played straight from a 文件来源.
 *
 * Like an outside address (`isExternalPlayback`) it has no server identity: no server id, no play
 * session, no token. Unlike one its id is deterministic — `filesource:<source>:<path hash>` — so
 * the file's resume point outlives the queue. Nothing that reports to a media server may adopt it;
 * see `playbackReportingTarget`.
 */
val PlayerMediaItem.isFileSourcePlayback: Boolean
    get() = serverId == null && isFileSourceItemId(id)

/** A prepared queue, waiting for the screen to hand it to the player. */
data class FileSourceLaunch(
    val items: List<PlayerMediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
)

/**
 * Fetches the queue's sidecars and builds its playback items.
 *
 * Sidecars come first for the tapped video, then the ones autoplay reaches next; see
 * [FileSourceQueuePlan.subtitleFetchOrder]. Whatever is not cached within [subtitleBudgetMs] is
 * left off its item rather than holding the launch — a sidecar that failed costs a subtitle track,
 * never the playback.
 */
internal suspend fun prepareFileSourceLaunch(
    source: FileSource,
    credentials: FileSourceCredentials,
    plan: FileSourceQueuePlan,
    client: FileSourceClient,
    progress: Map<String, FileSourceProgress>,
    subtitleBudgetMs: Long = SUBTITLE_BUDGET_MS,
): FileSourceLaunch {
    val sidecars = fetchSidecars(source, credentials, plan, client, subtitleBudgetMs)
    val items = fileSourcePlaybackItems(source, credentials, plan, sidecars, progress)
    val start = items[plan.startIndex]
    return FileSourceLaunch(
        items = items,
        startIndex = plan.startIndex,
        startPositionMs = progress[start.id]?.resumePositionMs ?: 0L,
    )
}

/**
 * The playback items for [plan]: the share's own address for each file, the login in memory for
 * YCore's transports, and [sidecars] — keyed by queue index and subtitle name — as local files.
 */
internal fun fileSourcePlaybackItems(
    source: FileSource,
    credentials: FileSourceCredentials,
    plan: FileSourceQueuePlan,
    sidecars: Map<Pair<Int, String>, String>,
    progress: Map<String, FileSourceProgress>,
): List<PlayerMediaItem> {
    val transportCredentials = credentials.transportCredentials(source.kind)
    return plan.entries.mapIndexed { index, queued ->
        val url = source.url(queued.path, directory = false)
        val subtitles =
            queued.subtitles.mapNotNull { paired ->
                val uri = sidecars[index to paired.entry.name] ?: return@mapNotNull null
                PlayerExternalSubtitle(
                    uri = uri,
                    language = paired.language,
                    codec = paired.codec,
                    default = paired.default,
                    forced = paired.forced,
                )
            }
        val container = queued.entry.extension
        val version =
            PlayerMediaVersion(
                id = FILE_VERSION_ID,
                label = container.uppercase(),
                detail =
                    listOfNotNull(
                        container.uppercase(),
                        queued.entry.sizeBytes?.let(::formatFileSize),
                    ).joinToString(" · "),
                url = url,
                transcodeUrl = "",
                fallbackTranscodeUrl = "",
                container = container,
                // An ISO goes through YCore's disc route, which reads it over smb:// or http(s) too.
                discSource = queued.entry.isDiscImage,
                sourceSizeBytes = queued.entry.sizeBytes,
                externalSubtitles = subtitles,
                playMethod = PlaybackMethod.DirectPlay,
                serverTranscodeSupported = false,
            )
        val resume = progress[queued.itemId]
        PlayerMediaItem(
            id = queued.itemId,
            url = url,
            transcodeUrl = "",
            fallbackTranscodeUrl = "",
            title = fileBaseName(queued.entry.name),
            serverId = null,
            versions = listOf(version),
            versionId = FILE_VERSION_ID,
            progress = resume?.fraction,
            externalSubtitles = subtitles,
            durationMsHint = resume?.durationMs ?: 0L,
            transportCredentials = transportCredentials,
            playMethod = PlaybackMethod.DirectPlay,
            serverTranscodeSupported = false,
        )
    }
}

private suspend fun fetchSidecars(
    source: FileSource,
    credentials: FileSourceCredentials,
    plan: FileSourceQueuePlan,
    client: FileSourceClient,
    budgetMs: Long,
): Map<Pair<Int, String>, String> {
    val order = plan.subtitleFetchOrder()
    if (order.isEmpty()) return emptyMap()
    val results = mutableMapOf<Pair<Int, String>, String>()
    val lock = Mutex()
    val permits = Semaphore(SIDECAR_CONCURRENCY)
    var failures = 0
    coroutineScope {
        // Launched in fetch order; the fair semaphore then hands the permits out in that order.
        val jobs =
            order.map { (index, paired) ->
                launch {
                    permits.withPermit {
                        val path = plan.entries[index].path.dropLast(1) + paired.entry.name
                        try {
                            val uri = client.cacheSubtitle(source, credentials, path, paired.entry)
                            lock.withLock { results[index to paired.entry.name] = uri }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            lock.withLock { failures++ }
                        }
                    }
                }
            }
        if (withTimeoutOrNull(budgetMs) { jobs.joinAll() } == null) jobs.forEach { it.cancel() }
    }
    if (failures > 0 || results.size < order.size) {
        AppLog.warning(
            category = LOG_CATEGORY,
            event = "sidecars_incomplete",
            message = "Some subtitles were left off the queue",
            attributes =
                mapOf(
                    "sourceId" to source.id,
                    "wanted" to order.size.toString(),
                    "cached" to results.size.toString(),
                    "failed" to failures.toString(),
                ),
        )
    }
    return results
}

/**
 * Feeds the player's reports for 文件来源 items into their resume points and ignores every other
 * item: a server's items have the server to remember them.
 *
 * Nothing is recorded for an item until it has actually played. An engine reports position 0
 * while it opens a file and seeks to the resume point, and a player closed in those seconds would
 * otherwise have overwritten 45 minutes with nothing.
 */
class FileSourcePlaybackProgress(
    private val recorder: FileSourceProgressRecorder,
) {
    private var playingItemId: String? = null

    fun onProgress(
        item: PlayerMediaItem?,
        state: PlaybackState,
    ) {
        val id = item?.takeIf { it.isFileSourcePlayback }?.id ?: return
        if (state.error != null) return
        if (state.playing) playingItemId = id
        if (playingItemId != id) return
        recorder.onPosition(id, state.positionMs, state.durationMs, state.playing)
    }

    fun onState(
        item: PlayerMediaItem?,
        state: PlaybackState,
    ) {
        val id = item?.takeIf { it.isFileSourcePlayback }?.id ?: return
        if (state.ended && playingItemId == id) {
            recorder.onEnded(id, state.durationMs)
        } else {
            onProgress(item, state)
        }
    }

    fun flush() = recorder.flush()
}

/** `58.3 GB`, `734 MB`: one decimal from a gigabyte up, whole megabytes below. */
internal fun formatFileSize(bytes: Long): String {
    val megabytes = bytes / (1024.0 * 1024.0)
    if (megabytes < 1024.0) return "${megabytes.toLong().coerceAtLeast(1L)} MB"
    val tenths = (megabytes / 1024.0 * 10).toLong()
    return "${tenths / 10}.${tenths % 10} GB"
}

private const val FILE_VERSION_ID = "file"
private const val SIDECAR_CONCURRENCY = 4

/** Long enough for a season's sidecars over a slow tunnel; short enough not to feel like a hang. */
private const val SUBTITLE_BUDGET_MS = 8_000L
