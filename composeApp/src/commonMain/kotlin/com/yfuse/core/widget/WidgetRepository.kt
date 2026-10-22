package com.yfuse.core.widget

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
}

/** One atomic snapshot owns scripts and their references. Failed writes never publish new UI state. */
class WidgetRepository(
    private val persistence: WidgetPersistence,
    val runtime: WidgetRuntime,
    private val scope: CoroutineScope,
    private val newId: () -> String,
    private val hash: (String) -> String,
    private val now: () -> Long,
) {
    private val mutex = Mutex()
    private val mutableDatabase = MutableStateFlow(WidgetDatabase())
    val database = mutableDatabase.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private val cacheMutex = Mutex()

    private data class Cached(
        val expires: Long,
        val items: List<WidgetMedia>,
    )

    private val cache = LinkedHashMap<String, Cached>()

    init {
        scope.launch {
            try {
                persistence.read()?.let { raw ->
                    val value = widgetJson.decodeFromString<WidgetDatabase>(raw)
                    validate(value)
                    mutableDatabase.value = value
                }
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
        change { db -> db.copy(widgets = db.widgets.filterNot { it.metadata.id == id }) }

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
            db.copy(
                pages = db.pages.filterNot { it.id == id },
                defaultPageId = db.defaultPageId?.takeUnless { it == id },
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
        require(raw.encodeToByteArray().size <= 24 * MAX_WIDGET_SOURCE_BYTES) { "备份文件过大" }
        val value = widgetJson.decodeFromString<WidgetDatabase>(raw)
        validate(value)
        // Imported backups carry code: inspect every script before atomically replacing anything.
        value.widgets.forEach { saved ->
            val checked = inspect(saved.source, saved.origin)
            require(checked.metadata == saved.metadata && checked.digest == saved.digest) { "备份中的脚本与组件信息不一致" }
        }
        ready.await()
        mutex.withLock {
            persistence.write(widgetJson.encodeToString(value))
            mutableDatabase.value = value
            mutableError.value = null
            cacheMutex.withLock { cache.clear() }
        }
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
        if (!refresh) cacheMutex.withLock { cache[key]?.takeIf { it.expires > now() } }?.let { return it.items }
        val items = parseWidgetMedia(runtime.invoke(widget, module.functionName, params, module.timeoutSeconds))
        cacheMutex.withLock {
            cache[key] = Cached(now() + module.cacheDuration * 1000L, items)
            while (cache.size > 48) cache.remove(cache.keys.first())
        }
        return items
    }

    suspend fun detail(
        widgetId: String,
        media: WidgetMedia,
    ): WidgetMedia {
        val widget = database.value.widgets.firstOrNull { it.metadata.id == widgetId && it.enabled } ?: error("组件不可用")
        if (media.link.isBlank()) return media
        val raw = runtime.invoke(widget, "loadDetail", JsonPrimitive(media.link), 30)
        val element = widgetJson.parseToJsonElement(raw)
        val detail =
            when (element) {
                is JsonObject -> parseWidgetItem(element)
                is JsonArray -> media.copy(children = parseWidgetMedia(raw))
                else -> error("详情函数必须返回详情或播放线路")
            }
        return detail.copy(
            id = media.id,
            title = detail.title.takeUnless { it == "未命名内容" } ?: media.title,
            poster = detail.poster.ifBlank { media.poster },
            backdrop = detail.backdrop.ifBlank { media.backdrop },
            description = detail.description.ifBlank { media.description },
            headers = media.headers + detail.headers,
            videoUrl = detail.videoUrl.ifBlank { media.videoUrl },
        )
    }

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
            val snapshot = widgetJson.encodeToString(updated)
            require(snapshot.encodeToByteArray().size <= MAX_WIDGET_DATABASE_BYTES) { "组件与页面配置已达到存储上限" }
            persistence.write(snapshot)
            mutableDatabase.value = updated
            cacheMutex.withLock { cache.clear() }
        }
    }

    private fun validate(db: WidgetDatabase) {
        require(db.schema == 1) { "不支持此备份版本" }
        require(db.widgets.size <= 24 && db.pages.size <= 32) { "最多安装 24 个组件、创建 32 个页面" }
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
                page.title.isNotBlank() && page.title.length <= 80 && page.sections.size <= 40,
            ) { "页面名称需为 1–80 字，最多 40 个栏目" }
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

expect fun openWidgetVideo(media: WidgetMedia)

expect fun searchWidgetMedia(title: String)
