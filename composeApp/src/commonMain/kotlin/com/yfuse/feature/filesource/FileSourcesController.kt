package com.yfuse.feature.filesource

import com.yfuse.core.filesource.FileSource
import com.yfuse.core.filesource.FileSourceAddress
import com.yfuse.core.filesource.FileSourceClient
import com.yfuse.core.filesource.FileSourceCredentials
import com.yfuse.core.filesource.FileSourceDraft
import com.yfuse.core.filesource.FileSourceEntry
import com.yfuse.core.filesource.FileSourceException
import com.yfuse.core.filesource.FileSourceKind
import com.yfuse.core.filesource.FileSourceProgress
import com.yfuse.core.filesource.FileSourceProgressStore
import com.yfuse.core.filesource.FileSourceRegistry
import com.yfuse.core.filesource.LOG_CATEGORY
import com.yfuse.core.filesource.browsable
import com.yfuse.core.filesource.defaultPort
import com.yfuse.core.filesource.fileSourceItemId
import com.yfuse.core.filesource.pairSubtitles
import com.yfuse.core.filesource.planFileSourceQueue
import com.yfuse.core.filesource.resolveAddress
import com.yfuse.core.filesource.userMessage
import com.yfuse.core.filesource.withPastedAddress
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What 添加/编辑文件来源 holds while it is open. */
data class FileSourceFormState(
    val editingId: String? = null,
    val draft: FileSourceDraft = FileSourceDraft(),
    /** Editing a source that has a password stored: a blank 密码 keeps it. */
    val passwordStored: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val editing: Boolean get() = editingId != null
}

/** One row of a folder, with what the row shows worked out once rather than on every frame. */
data class FolderRow(
    val entry: FileSourceEntry,
    /** The playback item id of a video, which its resume point is keyed by; null for a folder. */
    val itemId: String?,
    val subtitleCount: Int,
)

/** A folder as the browser knows it. */
sealed interface FolderListing {
    data object Loading : FolderListing

    data class Loaded(
        /** Everything the share returned, subtitles included: a queue pairs them from this. */
        val entries: List<FileSourceEntry>,
        val rows: List<FolderRow>,
    ) : FolderListing

    data class Failed(
        val message: String,
    ) : FolderListing
}

/** The browser: which source, which folder, and what has been read so far. */
data class FileBrowserState(
    val source: FileSource,
    /** Folder names below the source's root; empty for the root itself. */
    val path: List<String> = emptyList(),
    val folders: Map<List<String>, FolderListing> = emptyMap(),
    /** Folders being read again while their previous rows stay on screen. */
    val refreshing: Set<List<String>> = emptySet(),
    /** The video whose queue is being prepared, shown with a spinner in its row. */
    val preparing: String? = null,
) {
    val listing: FolderListing? get() = folders[path]
}

/**
 * Everything the 服务器 tab does with 文件来源: the list, the add/edit form, the browser, and
 * the queue a tapped video becomes.
 *
 * Plain state flows rather than a store: every action here is one request whose result replaces
 * one piece of state, and none of it is shared with another screen.
 */
class FileSourcesController(
    private val registry: FileSourceRegistry,
    private val client: FileSourceClient,
    private val progressStore: FileSourceProgressStore,
    private val scope: CoroutineScope,
) {
    val sources: StateFlow<List<FileSource>> = registry.sources
    val progress: StateFlow<Map<String, FileSourceProgress>> = progressStore.progress

    private val _form = MutableStateFlow<FileSourceFormState?>(null)
    val form: StateFlow<FileSourceFormState?> = _form.asStateFlow()

    private val _browser = MutableStateFlow<FileBrowserState?>(null)
    val browser: StateFlow<FileBrowserState?> = _browser.asStateFlow()

    private val _launch = MutableStateFlow<FileSourceLaunch?>(null)

    /** A queue ready for the player; the screen hands it over and calls [consumeLaunch]. */
    val launch: StateFlow<FileSourceLaunch?> = _launch.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var submitJob: Job? = null
    private var playJob: Job? = null
    private val folderJobs = mutableMapOf<List<String>, Job>()

    /** Decrypted once per browsing session, not once per folder; dropped when the browser closes. */
    private var sessionCredentials: Pair<String, FileSourceCredentials>? = null

    // ------------------------------------------------------------------ the form

    fun openAdd() {
        _form.value = FileSourceFormState()
    }

    fun openEdit(source: FileSource) {
        _form.value =
            FileSourceFormState(
                editingId = source.id,
                draft = source.toDraft(),
                passwordStored = source.username.isNotBlank(),
            )
    }

    fun editDraft(transform: (FileSourceDraft) -> FileSourceDraft) {
        _form.update { form ->
            form?.takeUnless { it.submitting }?.copy(draft = transform(form.draft), error = null)
                ?: form
        }
    }

    fun dismissForm() {
        submitJob?.cancel()
        _form.value = null
    }

    /** Only a connection-related field may be refused by the share, so only those are tested. */
    fun submit() {
        val form = _form.value ?: return
        if (form.submitting) return
        val address =
            when (val resolved = form.draft.resolveAddress()) {
                is FileSourceAddress.Invalid -> {
                    _form.value = form.copy(error = resolved.message)
                    return
                }
                is FileSourceAddress.Valid -> resolved
            }
        // The kind a pasted address picked, so the saved source is the kind its address says.
        val draft = form.draft.withPastedAddress()
        val existing = form.editingId?.let(registry::source)
        val source =
            FileSource(
                id = existing?.id ?: registry.newSourceId(),
                kind = draft.kind,
                name = address.name,
                origin = address.origin,
                rootSegments = address.rootSegments,
                username = draft.username.trim(),
            )
        // Blank while editing means 不修改; a guest source stores no password at all.
        val password =
            when {
                source.username.isBlank() -> ""
                existing != null && draft.password.isEmpty() -> null
                else -> draft.password
            }
        val renameOnly =
            existing != null &&
                password == null &&
                existing.copy(name = source.name) == source
        _form.value = form.copy(submitting = true, error = null)
        submitJob =
            scope.launch {
                try {
                    if (!renameOnly) {
                        val credentials =
                            FileSourceCredentials(
                                username = source.username,
                                password = password ?: registry.credentials(source.id)?.password.orEmpty(),
                            )
                        client.list(source, credentials, emptyList())
                    }
                    registry.save(source, password)
                    _form.value = null
                    sessionCredentials = null
                    if (existing == null) {
                        _notice.value = "已添加「${source.name}」"
                        // Straight into the share: what it holds is the reason it was added.
                        open(source)
                    } else {
                        _notice.value = "已保存「${source.name}」"
                        if (_browser.value?.source?.id == source.id) open(source)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = error.userMessageFor(source.kind)
                    logFailure("connection_test_failed", source, error)
                    _form.update { it?.copy(submitting = false, error = message) }
                }
            }
    }

    fun remove(source: FileSource) {
        scope.launch {
            try {
                registry.remove(source.id)
                progressStore.removeSource(source.id)
                if (_browser.value?.source?.id == source.id) closeBrowser()
                _notice.value = "已移除「${source.name}」"
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logFailure("remove_failed", source, error)
                _notice.value = "无法移除「${source.name}」，请重试"
            }
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** Shown when a LAN share cannot be reached because 附近的设备 was refused. */
    fun showNotice(message: String) {
        _notice.value = message
    }

    // ------------------------------------------------------------------ the browser

    fun open(source: FileSource) {
        folderJobs.values.forEach(Job::cancel)
        folderJobs.clear()
        playJob?.cancel()
        sessionCredentials = null
        _browser.value = FileBrowserState(source)
        load(emptyList())
    }

    fun openFolder(name: String) {
        val browser = _browser.value ?: return
        val path = browser.path + name
        _browser.value = browser.copy(path = path, preparing = null)
        if (browser.folders[path] !is FolderListing.Loaded) load(path)
    }

    /** Up one folder; out of the browser from its root. */
    fun navigateUp() {
        val browser = _browser.value ?: return
        if (browser.path.isEmpty()) {
            closeBrowser()
        } else {
            _browser.value = browser.copy(path = browser.path.dropLast(1), preparing = null)
        }
    }

    fun closeBrowser() {
        folderJobs.values.forEach(Job::cancel)
        folderJobs.clear()
        playJob?.cancel()
        sessionCredentials = null
        _browser.value = null
    }

    fun refresh() {
        val browser = _browser.value ?: return
        load(browser.path)
    }

    /**
     * Plays [entry] and the folder around it. [fromStart] ignores the resume point — 从头播放 —
     * without forgetting it, so backing out early still leaves the old point in place.
     */
    fun play(
        entry: FileSourceEntry,
        fromStart: Boolean = false,
    ) {
        val browser = _browser.value ?: return
        val listing = browser.listing as? FolderListing.Loaded ?: return
        playJob?.cancel()
        _browser.value = browser.copy(preparing = entry.name)
        playJob =
            scope.launch {
                try {
                    val credentials = credentials(browser.source)
                    val plan = planFileSourceQueue(browser.source.id, browser.path, listing.entries, entry)
                    val prepared = prepareFileSourceLaunch(browser.source, credentials, plan, client, progress.value)
                    _launch.value = if (fromStart) prepared.copy(startPositionMs = 0L) else prepared
                    AppLog.info(
                        category = LOG_CATEGORY,
                        event = "queue_prepared",
                        message = "A file source queue was handed to the player",
                        attributes =
                            mapOf(
                                "sourceId" to browser.source.id,
                                "kind" to browser.source.kind.name,
                                "itemCount" to prepared.items.size.toString(),
                                "subtitles" to
                                    prepared.items[prepared.startIndex]
                                        .externalSubtitles.size
                                        .toString(),
                                "resumed" to (prepared.startPositionMs > 0L && !fromStart).toString(),
                            ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logFailure("queue_prepare_failed", browser.source, error)
                    _notice.value = error.userMessageFor(browser.source.kind)
                } finally {
                    _browser.update { current ->
                        current?.copy(preparing = current.preparing?.takeIf { it != entry.name })
                    }
                }
            }
    }

    fun consumeLaunch() {
        _launch.value = null
    }

    /** 标为未看: the resume point and the watched mark both go. */
    fun clearProgress(itemId: String) {
        progressStore.clear(itemId)
    }

    fun markWatched(itemId: String) {
        progressStore.markWatched(itemId, durationMs = progressStore.get(itemId)?.durationMs ?: 0L)
    }

    private fun load(path: List<String>) {
        val browser = _browser.value ?: return
        val source = browser.source
        folderJobs.remove(path)?.cancel()
        _browser.update { current ->
            current?.let {
                if (it.folders[path] is FolderListing.Loaded) {
                    it.copy(refreshing = it.refreshing + setOf(path))
                } else {
                    it.copy(folders = it.folders + (path to FolderListing.Loading))
                }
            }
        }
        folderJobs[path] =
            scope.launch {
                val listing =
                    try {
                        val entries = client.list(source, credentials(source), path)
                        FolderListing.Loaded(entries, folderRows(source.id, path, entries))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logFailure("list_failed", source, error)
                        FolderListing.Failed(error.userMessageFor(source.kind))
                    }
                _browser.update { current ->
                    current?.takeIf { it.source.id == source.id }?.let {
                        val keep = listing is FolderListing.Failed && it.folders[path] is FolderListing.Loaded
                        it.copy(
                            // A refresh that failed keeps the rows it had and says why in a notice.
                            folders = if (keep) it.folders else it.folders + (path to listing),
                            refreshing = it.refreshing - setOf(path),
                        )
                    } ?: current
                }
                if (listing is FolderListing.Failed && _browser.value?.folders?.get(path) is FolderListing.Loaded) {
                    _notice.value = listing.message
                }
                folderJobs.remove(path)
            }
    }

    private suspend fun credentials(source: FileSource): FileSourceCredentials {
        sessionCredentials?.takeIf { it.first == source.id }?.let { return it.second }
        val loaded = registry.credentials(source.id) ?: throw IllegalStateException("File source is gone")
        sessionCredentials = source.id to loaded
        return loaded
    }

    private fun logFailure(
        event: String,
        source: FileSource,
        error: Exception,
    ) {
        AppLog.warning(
            category = LOG_CATEGORY,
            event = event,
            message = "A file source request failed",
            attributes =
                mapOf(
                    "sourceId" to source.id,
                    "kind" to source.kind.name,
                    "failure" to ((error as? FileSourceException)?.failure?.name ?: "Unknown"),
                    "status" to ((error as? FileSourceException)?.status?.toString() ?: "none"),
                    "exception" to error::class.simpleName.orEmpty(),
                ),
        )
    }
}

/** The rows a folder shows, each video with its playback id and how many sidecars go with it. */
internal fun folderRows(
    sourceId: String,
    path: List<String>,
    entries: List<FileSourceEntry>,
): List<FolderRow> =
    entries.browsable().map { entry ->
        FolderRow(
            entry = entry,
            itemId = if (entry.isVideo) fileSourceItemId(sourceId, path + entry.name) else null,
            subtitleCount = if (entry.isVideo) pairSubtitles(entry, entries).size else 0,
        )
    }

/** The form a saved source is edited in: its address split back into the fields it came from. */
internal fun FileSource.toDraft(): FileSourceDraft {
    val scheme = origin.substringBefore("://")
    val authority = origin.substringAfter("://")
    val bracketEnd = authority.indexOf(']')
    val portSeparator = authority.lastIndexOf(':').takeIf { it > bracketEnd }
    val https = scheme == "https"
    // An origin drops the scheme's own port, but a blank 端口 means the kind's default: an Alist
    // behind a plain-HTTP proxy on 80 must not come back as 5244 when it is edited.
    val schemePort =
        when (scheme) {
            "https" -> "443"
            "http" -> "80"
            else -> "445"
        }
    val impliedPort = schemePort.takeIf { it != kind.defaultPort(https).toString() }.orEmpty()
    return FileSourceDraft(
        kind = kind,
        https = https,
        host = if (portSeparator == null) authority else authority.substring(0, portSeparator),
        port = portSeparator?.let { authority.substring(it + 1) } ?: impliedPort,
        path = rootSegments.joinToString("/"),
        username = username,
        password = "",
        name = name,
    )
}

private fun Exception.userMessageFor(kind: FileSourceKind): String =
    (this as? FileSourceException)?.failure?.userMessage(kind) ?: "无法连接，请稍后重试"
