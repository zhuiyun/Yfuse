package com.yfuse.core.widget

import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add

interface WidgetRuntime {
    suspend fun metadata(source: String): String

    suspend fun invoke(
        widget: InstalledWidget,
        function: String,
        argument: JsonElement,
        timeoutSeconds: Int,
    ): String
}

interface WidgetPersistence {
    suspend fun read(): String?

    suspend fun write(value: String)

    suspend fun readFeedCache(): String? = null

    suspend fun writeFeedCache(value: String) = Unit
}

/** One atomic snapshot owns scripts and their references. Failed writes never publish new UI state. */
class WidgetRepository(
    private val persistence: WidgetPersistence,
    val runtime: WidgetRuntime,
    private val scope: CoroutineScope,
    private val newId: () -> String,
    private val hash: (String) -> String,
    private val now: () -> Long,
    private val bundledSources: suspend () -> List<BundledWidgetSource> = { emptyList() },
) {
    private val mutex = Mutex()
    private val mutableDatabase = MutableStateFlow(WidgetDatabase())
    val database = mutableDatabase.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutableBundleError = MutableStateFlow<String?>(null)
    val bundleError = mutableBundleError.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private val cacheMutex = Mutex()
    private val feedCache = WidgetFeedCache(persistence)

    private data class Cached(
        val widgetId: String,
        val expires: Long,
        val items: List<WidgetMedia>,
    )

    private val cache = LinkedHashMap<String, Cached>()
    private val details = LinkedHashMap<Triple<String, String, String>, WidgetMedia>()
    private var cacheGeneration = 0L

    private data class FeedKey(
        val sectionId: String,
        val widgetId: String,
        val moduleId: String,
        val params: JsonObject,
        val digest: String?,
        val enabled: Boolean?,
    )

    private val feeds = MutableStateFlow<Map<FeedKey, WidgetFeed>>(emptyMap())
    private val mutableFeedRevision = MutableStateFlow(0L)
    val feedRevision = mutableFeedRevision.asStateFlow()

    /** Saved sections retain their data; only temporary browse/preview feeds use the 48-entry LRU. */
    fun feed(section: WidgetSection): WidgetFeed {
        val widget = database.value.widgets.firstOrNull { it.metadata.id == section.widgetId }
        val module = widget?.metadata?.modules?.firstOrNull { it.id == section.moduleId }
        val key =
            FeedKey(section.id, section.widgetId, section.moduleId, section.params, widget?.digest, widget?.enabled)
        while (true) {
            val previous = feeds.value
            val entry =
                previous[key] ?: createFeed(section, module)
            val next = (previous - key) + (key to entry)
            val saved = savedFeedKeys(database.value)
            val expired =
                next.keys
                    .filterNot { it in saved }
                    .dropLast(48)
                    .toSet()
            val bounded = next - expired
            if (feeds.compareAndSet(previous, bounded)) return entry
            if (previous[key] == null) entry.close()
        }
    }

    private fun createFeed(
        section: WidgetSection,
        module: WidgetModule?,
        initial: WidgetFeedSnapshot = WidgetFeedSnapshot(),
    ) = WidgetFeed(
        scope,
        now,
        module?.cacheDuration ?: 0,
        module?.params?.any { it.type == "page" } == true,
        initial.takeIf { it.items.isNotEmpty() }
            ?: WidgetFeedSnapshot(
                items =
                    if (module != null &&
                        database.value.widgets.any { it.metadata.id == section.widgetId && it.enabled }
                    ) {
                        feedCache.peek(feedCacheKey(section))
                    } else {
                        emptyList()
                    },
            ),
    ) { page, refresh -> load(section, page, refresh) }

    private fun feedCacheKey(section: WidgetSection): String {
        val widget = database.value.widgets.firstOrNull { it.metadata.id == section.widgetId }
        return hash("${section.widgetId}:${widget?.digest}:${section.moduleId}:${section.params}")
    }

    private fun savedFeedKeys(db: WidgetDatabase): Set<FeedKey> {
        val widgets = db.widgets.associateBy { it.metadata.id }
        return db.pages.flatMap { it.sections }.mapTo(mutableSetOf()) { section ->
            val widget = widgets[section.widgetId]
            FeedKey(section.id, section.widgetId, section.moduleId, section.params, widget?.digest, widget?.enabled)
        }
    }

    private fun pruneChangedSections(
        before: WidgetDatabase,
        after: WidgetDatabase,
    ) {
        val removedKeys = savedFeedKeys(before) - savedFeedKeys(after)
        if (removedKeys.isEmpty()) return
        while (true) {
            val previous = feeds.value
            val removed = previous.filterKeys { it in removedKeys }
            if (feeds.compareAndSet(previous, previous - removed.keys)) {
                removed.values.forEach { it.close() }
                return
            }
        }
    }

    /** A new script/query generation starts with the last successful picture still on screen. */
    private fun invalidateFeeds(
        ids: Set<String>? = null,
        replacementDatabase: WidgetDatabase? = null,
    ) {
        val savedBefore = savedFeedKeys(database.value)
        val savedAfter = replacementDatabase?.let(::savedFeedKeys).orEmpty()
        while (true) {
            val previous = feeds.value
            val removed = previous.filterKeys { ids == null || it.widgetId in ids }
            val retained = mutableSetOf<WidgetFeed>()
            val replacements =
                buildMap {
                    removed.forEach { (key, feed) ->
                        val widget =
                            replacementDatabase?.widgets?.firstOrNull {
                                it.metadata.id == key.widgetId &&
                                    it.enabled
                            }
                        val module = widget?.metadata?.modules?.firstOrNull { it.id == key.moduleId }
                        val nextKey = key.copy(digest = widget?.digest, enabled = widget?.enabled)
                        if (module != null && (key !in savedBefore || nextKey in savedAfter)) {
                            val section =
                                WidgetSection(
                                    key.sectionId,
                                    key.widgetId,
                                    key.moduleId,
                                    module.title,
                                    params = key.params,
                                )
                            put(nextKey, createFeed(section, module, feed.state.value))
                            retained += feed
                        }
                    }
                }
            if (feeds.compareAndSet(previous, previous - removed.keys + replacements)) {
                removed.values.forEach { it.close(preserveContent = it in retained) }
                return
            }
            replacements.values.forEach { it.close() }
        }
    }

    init {
        scope.launch {
            try {
                persistence.read()?.let { raw ->
                    val value = widgetJson.decodeFromString<WidgetDatabase>(raw)
                    validate(value)
                    feedCache.restore()
                    mutableDatabase.value = value
                }
                initializeBundles()
            } catch (e: CancellationException) {
                ready.cancel(e)
                throw e
            } catch (_: Exception) {
                mutableError.value = "组件配置读取失败，原始数据已保留。请恢复有效备份后重试。"
            } finally {
                ready.complete(Unit)
            }
        }
    }

    suspend fun awaitReady() = ready.await()

    private data class InspectedBundles(
        val bundles: List<Pair<BundledWidgetSource, InstalledWidget>>,
        val unavailableNames: List<String>,
    )

    private suspend fun inspectBundles(ids: Set<String>? = null): InspectedBundles {
        val sources =
            try {
                bundledSources().filter { ids == null || it.id in ids }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return InspectedBundles(emptyList(), listOf("内置组件资源"))
            }
        val bundles = mutableListOf<Pair<BundledWidgetSource, InstalledWidget>>()
        val unavailable = mutableListOf<String>()
        val duplicates =
            sources
                .groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        for (source in sources) {
            try {
                require(source.id !in duplicates) { "预置组件 ID 重复" }
                val widget = inspect(source.source, source.origin)
                validateBundledWidget(source, widget)
                validate(WidgetDatabase(widgets = listOf(widget), pages = listOf(source.page)))
                bundles += source to widget
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unavailable += source.page.title.ifBlank { source.id }
            }
        }
        ids?.filter { id -> sources.none { it.id == id } }?.let { unavailable += it }
        return InspectedBundles(bundles, unavailable.distinct())
    }

    private fun bundleFailureMessage(names: List<String>): String? =
        names.takeIf { it.isNotEmpty() }?.let {
            "内置组件暂未就绪：${it.joinToString("、")}。已有内容已保留，可在组件管理中重试恢复。"
        }

    private suspend fun initializeBundles(afterReady: Boolean = false) {
        try {
            val inspected = inspectBundles()
            if (inspected.bundles.isNotEmpty()) {
                if (afterReady) {
                    change { mergeBundledWidgets(it, inspected.bundles) }
                } else {
                    val updated = mergeBundledWidgets(database.value, inspected.bundles)
                    if (updated != database.value) {
                        validate(updated)
                        persistence.write(encodeSnapshot(updated))
                        mutableDatabase.value = updated
                    }
                }
            }
            mutableBundleError.value = bundleFailureMessage(inspected.unavailableNames)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed bundled update must not prevent editing or backing up a valid user snapshot.
            mutableBundleError.value = "内置组件保存失败，已有组件和页面已保留，可在组件管理中重试恢复。"
        }
    }

    /** Explicit user action: restore only selected missing presets, preserving all existing edits. */
    suspend fun restoreBundledWidgets(ids: Set<String> = BUNDLED_WIDGET_IDS): BundledWidgetRestoreResult {
        ready.await()
        check(mutableError.value == null) { mutableError.value.orEmpty() }
        if (ids.isEmpty()) return BundledWidgetRestoreResult(0, 0)
        val inspected = inspectBundles(ids)
        var result = BundledWidgetRestoreResult(0, 0, inspected.unavailableNames)
        if (inspected.bundles.isNotEmpty()) {
            change { current ->
                val updated = mergeBundledWidgets(current, inspected.bundles, restoreMissing = true)
                val previousWidgetIds = current.widgets.mapTo(mutableSetOf()) { it.metadata.id }
                val previousPageIds = current.pages.mapTo(mutableSetOf()) { it.id }
                result =
                    BundledWidgetRestoreResult(
                        restoredWidgets = updated.widgets.count { it.metadata.id !in previousWidgetIds },
                        restoredPages = updated.pages.count { it.id !in previousPageIds },
                        unavailableNames = inspected.unavailableNames,
                        incompleteNames =
                            inspected.bundles.mapNotNull { (source, _) ->
                                source.page.title.takeIf {
                                    updated.widgets.none { it.metadata.id == source.id } ||
                                        updated.pages.none { it.id == source.page.id }
                                }
                            },
                    )
                updated
            }
        }
        mutableBundleError.value = bundleFailureMessage(result.unavailableNames + result.incompleteNames)
        return result
    }

    suspend fun inspect(
        source: String,
        origin: String,
    ): InstalledWidget {
        require(source.encodeToByteArray().size <= MAX_WIDGET_SOURCE_BYTES) { "JS 文件不能超过 1 MB" }
        require(source.isNotBlank()) { "JS 文件为空" }
        val normalized = source.removePrefix("\uFEFF")
        val metadata = parseWidgetMetadata(runtime.metadata(normalized), "local-${hash(origin).take(24)}")
        return InstalledWidget(metadata, normalized, origin.take(4096), hash(normalized))
    }

    suspend fun install(widget: InstalledWidget) =
        change { db ->
            val previous = db.widgets.firstOrNull { it.metadata.id == widget.metadata.id }
            db.copy(
                widgets =
                    db.widgets.filterNot { it.metadata.id == widget.metadata.id } +
                        widget.copy(enabled = previous?.enabled ?: true),
            )
        }

    suspend fun enable(
        id: String,
        enabled: Boolean,
    ) = change { db ->
        db.copy(widgets = db.widgets.map { if (it.metadata.id == id) it.copy(enabled = enabled) else it })
    }

    /** Keep the person's layout; missing sources have an explicit reinstall/remove state. */
    suspend fun uninstall(id: String) =
        change { db ->
            db.copy(
                widgets = db.widgets.filterNot { it.metadata.id == id },
                removedBundledWidgets =
                    if (id in BUNDLED_WIDGET_IDS ||
                        id in db.bundledDigests
                    ) {
                        db.removedBundledWidgets + id
                    } else {
                        db.removedBundledWidgets
                    },
            )
        }

    suspend fun savePage(page: WidgetPage) =
        change { db ->
            db.copy(
                pages =
                    if (db.pages.any { it.id == page.id }) {
                        db.pages.map {
                            if (it.id ==
                                page.id
                            ) {
                                page
                            } else {
                                it
                            }
                        }
                    } else {
                        db.pages + page
                    },
            )
        }

    fun newPage(title: String) = WidgetPage(newId(), title.trim().ifBlank { "我的首页" })

    fun newSection(
        widget: InstalledWidget,
        module: WidgetModule,
    ) = WidgetSection(newId(), widget.metadata.id, module.id, module.title)

    fun duplicate(section: WidgetSection) = section.copy(id = newId(), title = "${section.title} 副本".take(80))

    suspend fun removePage(id: String) =
        change { db ->
            val widgetId = id.removePrefix("page-")
            val bundled = id.startsWith("page-") && (widgetId in BUNDLED_WIDGET_IDS || widgetId in db.bundledDigests)
            db.copy(
                pages = db.pages.filterNot { it.id == id },
                defaultPageId = db.defaultPageId?.takeUnless { it == id },
                removedBundledPages = if (bundled) db.removedBundledPages + id else db.removedBundledPages,
            )
        }

    suspend fun movePage(
        id: String,
        offset: Int,
    ) = change { db ->
        val pages = db.pages.toMutableList()
        val index = pages.indexOfFirst { it.id == id }
        if (index >= 0) pages.add((index + offset).coerceIn(0, pages.lastIndex), pages.removeAt(index))
        db.copy(pages = pages)
    }

    suspend fun setDefault(id: String?) =
        change { db ->
            require(id == null || db.pages.any { it.id == id && !it.hidden }) { "请选择可见页面" }
            db.copy(defaultPageId = id)
        }

    suspend fun backup(): String {
        ready.await()
        check(mutableError.value == null) { mutableError.value.orEmpty() }
        return widgetJson.encodeToString(database.value)
    }

    suspend fun restore(raw: String) {
        require(raw.encodeToByteArray().size <= MAX_WIDGET_DATABASE_BYTES) { "备份文件过大" }
        val value = widgetJson.decodeFromString<WidgetDatabase>(raw)
        validate(value)
        // Defaults omitted by another exporter can make the persisted snapshot larger than its input.
        val snapshot = encodeSnapshot(value)
        // Imported backups carry code: inspect every script before atomically replacing anything.
        value.widgets.forEach { saved ->
            val checked = inspect(saved.source, saved.origin)
            require(checked.metadata == saved.metadata && checked.digest == saved.digest) { "备份中的脚本与组件信息不一致" }
        }
        ready.await()
        mutex.withLock {
            persistence.write(snapshot)
            cacheMutex.withLock {
                cacheGeneration++
                cache.clear()
                details.clear()
            }
            invalidateFeeds(replacementDatabase = value)
            mutableDatabase.value = value
            mutableFeedRevision.value++
            mutableError.value = null
            mutableBundleError.value = null
        }
        // Old backups can predate presets; reconcile immediately instead of waiting for a process restart.
        initializeBundles(afterReady = true)
    }

    suspend fun load(
        section: WidgetSection,
        page: Int = 1,
        refresh: Boolean = false,
    ): List<WidgetMedia> {
        ready.await()
        val widget =
            database.value.widgets.firstOrNull { it.metadata.id == section.widgetId } ?: error("组件已卸载，请重新安装或移除此栏目")
        check(widget.enabled) { "组件已停用，可在组件中心启用" }
        val module = widget.metadata.modules.firstOrNull { it.id == section.moduleId } ?: error("组件更新后已移除此模块，请重新选择")
        val params = module.parameterValues(section.params, page)
        module.parameterError(section.params)?.let { error(it) }
        val key = "${widget.metadata.id}:${widget.digest}:${module.id}:$params"
        val (generation, cached) = cacheMutex.withLock { cacheGeneration to cache[key]?.takeIf { it.expires > now() } }
        if (!refresh && cached != null) return cached.items
        val items = parseWidgetMedia(runtime.invoke(widget, module.functionName, params, module.timeoutSeconds))
        cacheMutex.withLock {
            if (generation == cacheGeneration && database.value.widgets.any { it == widget }) {
                cache[key] = Cached(widget.metadata.id, now() + module.cacheDuration * 1000L, items)
            }
            while (cache.size > 48) cache.remove(cache.keys.first())
        }
        if (page == 1 &&
            database.value.pages.any { page -> page.sections.any { it == section } } &&
            database.value.widgets.any { it == widget }
        ) {
            feedCache.save(feedCacheKey(section), items)
        }
        return items
    }

    suspend fun detail(
        widgetId: String,
        media: WidgetMedia,
        forceRefresh: Boolean = false,
    ): WidgetMedia {
        ready.await()
        val widget = database.value.widgets.firstOrNull { it.metadata.id == widgetId && it.enabled } ?: error("组件不可用")
        if (!media.requiresWidgetDetailLookup()) return media
        val detailKey = Triple(widgetId, widget.digest, media.link)
        val generation = cacheMutex.withLock { cacheGeneration }
        if (!forceRefresh) cacheMutex.withLock { details[detailKey] }?.let { return mergeDetail(media, it) }
        val raw =
            try {
                runtime.invoke(widget, "loadDetail", JsonPrimitive(media.link), 30)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (failure.message == "组件未提供此函数") {
                    media.directWidgetLinkOrNull()?.let { return media.copy(videoUrl = it) }
                }
                if (failure.message == "组件未提供此函数" &&
                    widget.metadata.modules.any { it.id == "loadResource" && it.type == "stream" }
                ) {
                    return media
                }
                throw failure
            }
        val element = widgetJson.parseToJsonElement(raw)
        val detail =
            when (element) {
                is JsonObject -> parseWidgetItem(element)
                is JsonArray -> media.copy(children = parseWidgetMedia(raw))
                else -> error("详情函数必须返回详情或播放线路")
            }
        cacheMutex.withLock {
            if (generation == cacheGeneration) {
                details[detailKey] = detail
                while (details.size > 24) details.remove(details.keys.first())
            }
        }
        return mergeDetail(media, detail)
    }

    /** Resolve a stream only after an explicit play tap, so live signatures are not pre-consumed. */
    suspend fun playback(
        context: WidgetSourceContext,
        media: WidgetMedia,
    ): WidgetMedia {
        ready.await()
        val widget =
            database.value.widgets.firstOrNull { it.metadata.id == context.widgetId && it.enabled } ?: error("组件不可用")
        val resource = widget.metadata.modules.firstOrNull { it.id == "loadResource" && it.type == "stream" }
        if (resource != null) {
            val args = context.withCurrentSection(database.value).resourceArguments(resource, media)
            AppLog.info(
                category = "widget",
                event = "resource_lookup_started",
                message = "Resolving a widget playback resource",
                attributes =
                    mapOf(
                        "widgetId" to context.widgetId,
                        "mediaType" to args.text("mediaType"),
                        "hasTitle" to args.text("title").isNotBlank().toString(),
                        "season" to args.text("season"),
                        "episode" to args.text("episode"),
                    ),
            )
            val routes =
                parseWidgetMedia(runtime.invoke(widget, resource.functionName, args, resource.timeoutSeconds))
                    .filter { it.videoUrl.isNotBlank() }
            AppLog.info(
                category = "widget",
                event = "resource_lookup_completed",
                message = "Widget playback resolver returned",
                attributes = mapOf("widgetId" to context.widgetId, "routeCount" to routes.size.toString()),
            )
            require(routes.isNotEmpty()) { "组件未返回播放地址" }
            return media.copy(videoUrl = "", children = routes)
        }
        if (media.videoUrl.isNotBlank() &&
            (!media.requiresWidgetDetailLookup() || media.mediaType.lowercase() in setOf("movie", "tv", "series"))
        ) {
            return media
        }
        val resolved = detail(context.widgetId, media, forceRefresh = true)
        require(resolved.videoUrl.isNotBlank() || resolved.children.isNotEmpty()) { "组件未返回播放地址" }
        return resolved
    }

    private fun mergeDetail(
        media: WidgetMedia,
        detail: WidgetMedia,
    ): WidgetMedia =
        detail.copy(
            id = media.id,
            title = detail.title.takeUnless { it == "未命名内容" } ?: media.title,
            poster = detail.poster.ifBlank { media.poster },
            backdrop = detail.backdrop.ifBlank { media.backdrop },
            description = detail.description.ifBlank { media.description },
            mediaType = media.mediaType,
            type = media.type,
            link = media.link,
            tmdbId = detail.tmdbId.ifBlank { media.tmdbId },
            year = detail.year.ifBlank { media.year },
            season = detail.season ?: media.season,
            episode = detail.episode ?: media.episode,
            headers = media.headers + detail.headers,
            videoUrl = detail.videoUrl.ifBlank { media.videoUrl },
            children = detail.children.ifEmpty { media.children },
            resourceMetadata = JsonObject(media.resourceMetadata + detail.resourceMetadata),
        )

    private suspend fun change(transform: (WidgetDatabase) -> WidgetDatabase) {
        ready.await()
        mutex.withLock {
            check(mutableError.value == null) { mutableError.value.orEmpty() }
            var updated = transform(database.value)
            if (updated.pages.none { it.id == updated.defaultPageId && !it.hidden }) {
                updated =
                    updated.copy(defaultPageId = null)
            }
            validate(updated)
            val snapshot = encodeSnapshot(updated)
            persistence.write(snapshot)
            val previousWidgets = database.value.widgets.associateBy { it.metadata.id }
            val updatedWidgets = updated.widgets.associateBy { it.metadata.id }
            val changedIds =
                (previousWidgets.keys + updatedWidgets.keys).filterTo(mutableSetOf()) {
                    previousWidgets[it] != updatedWidgets[it]
                }
            if (changedIds.isNotEmpty()) {
                cacheMutex.withLock {
                    cacheGeneration++
                    cache.entries.removeAll { it.value.widgetId in changedIds }
                    details.keys.removeAll { it.first in changedIds }
                }
                invalidateFeeds(changedIds, updated)
            }
            val previousDatabase = database.value
            mutableDatabase.value = updated
            pruneChangedSections(previousDatabase, updated)
            if (changedIds.isNotEmpty()) mutableFeedRevision.value++
        }
    }

    private fun encodeSnapshot(value: WidgetDatabase): String {
        val snapshot = widgetJson.encodeToString(value)
        require(snapshot.encodeToByteArray().size <= MAX_WIDGET_DATABASE_BYTES) { "组件与页面配置已达到存储上限" }
        return snapshot
    }

    private fun validate(db: WidgetDatabase) {
        require(db.schema == 1) { "不支持此备份版本" }
        require(db.bundledStateVersion in 0..1) { "不支持此预置组件记录版本" }
        require(
            db.removedBundledWidgets.size <= 64 &&
                db.removedBundledWidgets.all { it.length in 1..160 } &&
                db.removedBundledPages.size <= 64 &&
                db.removedBundledPages.all { it.length in 1..200 },
        ) { "预置组件删除记录无效" }
        require(
            db.bundledDigests.size <= 64 &&
                db.bundledDigests.all { (id, digest) -> id.length in 1..160 && digest.length in 1..128 },
        ) { "预置组件记录无效" }
        require(
            db.widgets
                .map { it.metadata.id }
                .distinct()
                .size == db.widgets.size,
        ) { "组件 ID 重复" }
        require(db.widgets.all { it.source.encodeToByteArray().size <= MAX_WIDGET_SOURCE_BYTES }) { "脚本文件过大" }
        require(
            db.pages
                .map { it.id }
                .distinct()
                .size == db.pages.size,
        ) { "页面 ID 重复" }
        db.pages.forEach { page ->
            require(
                page.title.isNotBlank() && page.title.length <= 80,
            ) { "页面名称需为 1–80 字" }
            require(
                page.sections
                    .map { it.id }
                    .distinct()
                    .size == page.sections.size,
            ) { "栏目 ID 重复" }
            page.sections.forEach {
                require(
                    it.title.isNotBlank() && it.title.length <= 120 && it.limit in 1..100,
                ) { "栏目配置无效" }
            }
        }
    }
}

expect fun widgetRepository(): WidgetRepository

/** Platform actions use the same installer, and never expose server credentials to scripts. */
@androidx.compose.runtime.Composable
expect fun rememberWidgetFileImport(
    onFile: (String, String) -> Unit,
    onError: (String) -> Unit,
): () -> Unit

@androidx.compose.runtime.Composable
expect fun rememberWidgetBackupFiles(
    repository: WidgetRepository,
    onError: (String) -> Unit,
): WidgetBackupActions

data class WidgetBackupActions(
    val export: () -> Unit,
    val restore: () -> Unit,
)

expect suspend fun downloadWidgetSource(url: String): String

expect fun openWidgetVideo(
    media: WidgetMedia,
    request: WidgetPlaybackRequest? = null,
)

expect fun searchWidgetMedia(title: String)
