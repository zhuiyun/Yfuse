package com.yfuse.feature.filesource

import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceException
import com.yfuse.core.filesource.FileSourceLibrary
import com.yfuse.core.filesource.FileSourceLibraryStore
import com.yfuse.core.filesource.FileSourceRegistry
import com.yfuse.core.filesource.FileSourceScanProgress
import com.yfuse.core.filesource.FileSourceScanner
import com.yfuse.core.filesource.LOG_CATEGORY
import com.yfuse.core.filesource.TmdbUnavailableException
import com.yfuse.core.filesource.userMessage
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 刮削: at most one scan per share, how far each has got, and the 片库 each leaves behind.
 *
 * A scan runs in the 服务器 tab's scope rather than a screen's, so leaving the tab — or the
 * share's dialog — does not stop a scan of a large NAS halfway. A failed scan keeps the library
 * the last one left: a share that is briefly offline must not empty its titles from 全部服务器.
 */
class FileSourceLibraryScans(
    private val registry: FileSourceRegistry,
    private val store: FileSourceLibraryStore,
    private val scanner: FileSourceScanner,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
) {
    val libraries: StateFlow<Map<String, FileSourceLibrary>> = store.libraries

    private val _progress = MutableStateFlow<Map<String, FileSourceScanProgress>>(emptyMap())

    /** The shares being scanned now; a share missing here is not being scanned. */
    val progress: StateFlow<Map<String, FileSourceScanProgress>> = _progress.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    init {
        scope.launch { store.ensureLoaded() }
    }

    fun scan(source: FileSource) {
        if (jobs[source.id]?.isActive == true) return
        _progress.update { it + (source.id to FileSourceScanProgress()) }
        jobs[source.id] =
            scope.launch {
                try {
                    store.ensureLoaded()
                    val credentials =
                        registry.credentials(source.id) ?: throw IllegalStateException("File source is gone")
                    val library =
                        scanner.scan(source, credentials, store.libraries.value[source.id]) { progress ->
                            _progress.update { current ->
                                if (source.id in
                                    current
                                ) {
                                    current + (source.id to progress)
                                } else {
                                    current
                                }
                            }
                        }
                    store.save(source.id, library)
                    log("library_scan_finished", source, library)
                    notify(library.summary(source.name))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logFailure(source, error)
                    notify(error.scanMessage(source))
                } finally {
                    _progress.update { it - source.id }
                    jobs.remove(source.id)
                }
            }
    }

    fun cancel(sourceId: String) {
        jobs.remove(sourceId)?.cancel()
        _progress.update { it - sourceId }
    }

    /** A removed share takes its titles out of 全部服务器 with it. */
    suspend fun forget(sourceId: String) {
        cancel(sourceId)
        store.remove(sourceId)
    }

    private fun log(
        event: String,
        source: FileSource,
        library: FileSourceLibrary,
    ) {
        AppLog.info(
            category = LOG_CATEGORY,
            event = event,
            message = "A file source library scan finished",
            attributes =
                mapOf(
                    "sourceId" to source.id,
                    "kind" to source.kind.name,
                    "titles" to library.titles.size.toString(),
                    "files" to library.files.size.toString(),
                    "unmatched" to library.unmatchedCount.toString(),
                    "partial" to library.partial.toString(),
                ),
        )
    }

    private fun logFailure(
        source: FileSource,
        error: Exception,
    ) {
        AppLog.warning(
            category = LOG_CATEGORY,
            event = "library_scan_failed",
            message = "A file source library scan failed",
            attributes =
                mapOf(
                    "sourceId" to source.id,
                    "kind" to source.kind.name,
                    "failure" to ((error as? FileSourceException)?.failure?.name ?: error::class.simpleName.orEmpty()),
                ),
        )
    }
}

/** The line under a share's name about its 片库: how a scan is going, or what the last one found. */
internal fun libraryStatus(
    progress: FileSourceScanProgress?,
    library: FileSourceLibrary?,
): String? =
    when {
        progress != null && progress.matching -> "正在识别 · ${progress.titlesLookedUp}/${progress.titlesToMatch}"
        progress != null -> "正在读取文件夹 · 已找到 ${progress.videosFound} 个视频"
        library == null -> null
        library.titles.isEmpty() -> "片库中没有识别出的影片"
        else ->
            listOfNotNull(
                "片库 ${library.titles.size} 部",
                "${library.unmatchedCount} 个未识别".takeIf { library.unmatchedCount > 0 },
            ).joinToString(" · ")
    }

/** `「NAS」已加入片库：86 部影片与剧集，3 个视频未能识别`. */
internal fun FileSourceLibrary.summary(sourceName: String): String {
    val head =
        if (titles.isEmpty()) {
            "「$sourceName」里没有识别出影片或剧集"
        } else {
            "「$sourceName」已加入片库：${titles.size} 部影片与剧集"
        }
    val notes =
        listOfNotNull(
            "$unmatchedCount 个视频未能识别".takeIf { unmatchedCount > 0 },
            "其余内容下次刮削时补全".takeIf { partial },
        )
    return (listOf(head) + notes).joinToString("，")
}

private fun Exception.scanMessage(source: FileSource): String =
    when (this) {
        is TmdbUnavailableException -> "无法连接 TMDB，「${source.name}」的片库没有更新"
        is FileSourceException -> failure.userMessage(source.kind)
        else -> "刮削「${source.name}」失败，请稍后重试"
    }
