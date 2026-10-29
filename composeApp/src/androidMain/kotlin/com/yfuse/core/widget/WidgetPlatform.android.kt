package com.yfuse.core.widget

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.designsystem.ConfirmDialog
import com.yfuse.core.util.androidAppContext
import com.yfuse.feature.player.PlayerActivity
import com.yfuse.feature.player.widgetPlaybackItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.koin.core.context.GlobalContext
import org.koin.core.qualifier.named
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal fun widgetHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

private val widgetTmdbHttp by lazy { WidgetTmdbHttp(GlobalContext.get().get(named("tmdb-http"))) }

private val processEpisodeData by lazy {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val koin = GlobalContext.get()
    WidgetEpisodeData(NativeWidgetEpisodeLibrary(koin.get(), koin.get(), koin.get(), scope), scope)
}

internal actual fun widgetEpisodeData(): WidgetEpisodeData = processEpisodeData

private val processRepository by lazy {
    val context = checkNotNull(androidAppContext)
    val file = AtomicFile(File(context.filesDir, "widgets-v1.json"))
    val feedFile = AtomicFile(File(context.filesDir, "widget-feeds-v1.json"))
    val storage = context.getSharedPreferences("widget-storage-v1", 0)
    WidgetRepository(
        persistence =
            object : WidgetPersistence {
                override suspend fun readFeedCache(): String? =
                    withContext(Dispatchers.IO) {
                        if (!feedFile.baseFile.exists()) {
                            null
                        } else {
                            feedFile.openRead().use {
                                it.readBytesBounded(MAX_WIDGET_FEED_CACHE_BYTES).toString(Charsets.UTF_8)
                            }
                        }
                    }

                override suspend fun writeFeedCache(value: String) =
                    withContext(Dispatchers.IO) {
                        val output = feedFile.startWrite()
                        try {
                            output.write(value.toByteArray())
                            feedFile.finishWrite(output)
                        } catch (e: Exception) {
                            feedFile.failWrite(output)
                            throw e
                        }
                    }

                override suspend fun read(): String? =
                    withContext(Dispatchers.IO) {
                        if (!file.baseFile.exists()) {
                            null
                        } else {
                            file.openRead().use {
                                it
                                    .readBytesBounded(
                                        24 * MAX_WIDGET_SOURCE_BYTES,
                                    ).toString(Charsets.UTF_8)
                            }
                        }
                    }

                override suspend fun write(value: String) =
                    withContext(Dispatchers.IO) {
                        val output = file.startWrite()
                        try {
                            output.write(value.toByteArray())
                            file.finishWrite(output)
                        } catch (
                            e: Exception,
                        ) {
                            file.failWrite(output)
                            throw e
                        }
                    }
            },
        runtime =
            SandboxWidgetRuntime(
                request = { options, tmdb ->
                    if (tmdb && options.text("apiKey").isBlank()) {
                        widgetTmdbHttp.request(options)
                    } else {
                        WidgetHttp.request(options, tmdb)
                    }
                },
                readStorage = { id, key -> storage.getString("${widgetHash(id)}:$key", null) },
                writeStorage = { id, key, value ->
                    synchronized(storage) {
                        val prefix = "${widgetHash(id)}:"
                        val own = storage.all.filterKeys { it.startsWith(prefix) }.toMutableMap()
                        if (value == null) own.remove(prefix + key) else own[prefix + key] = value
                        require(
                            own.size <= 128 && own.values.sumOf { it.toString().length } <= MAX_WIDGET_SOURCE_BYTES,
                        ) { "组件存储配额已满" }
                        check(
                            storage
                                .edit()
                                .let {
                                    if (value ==
                                        null
                                    ) {
                                        it.remove(prefix + key)
                                    } else {
                                        it.putString(prefix + key, value)
                                    }
                                }.commit(),
                        ) { "组件存储写入失败" }
                    }
                },
            ),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        newId = { UUID.randomUUID().toString() },
        hash = ::widgetHash,
        now = System::currentTimeMillis,
        bundledSources = {
            bundledWidgetSources { filename ->
                context.assets.open("widgets/$filename").use {
                    it.readBytesBounded(MAX_WIDGET_SOURCE_BYTES).toString(Charsets.UTF_8)
                }
            }
        },
    )
}

actual fun widgetRepository(): WidgetRepository = processRepository

actual suspend fun downloadWidgetSource(url: String): String = WidgetHttp.source(url)

internal suspend fun readWidgetFile(
    context: android.content.Context,
    uri: Uri,
    limit: Int = MAX_WIDGET_SOURCE_BYTES,
): Pair<String, String> =
    withContext(Dispatchers.IO) {
        require(uri.scheme == "content" || uri.scheme == "file") { "不支持此文件地址" }
        if (uri.scheme == "content") {
            val authority = uri.authority?.substringAfterLast('@').orEmpty()
            require(
                authority.isNotBlank() &&
                    authority != context.packageName &&
                    !authority.startsWith("${context.packageName}."),
            ) { "不能导入应用内部文件" }
        } else {
            val file = File(requireNotNull(uri.path)).canonicalFile
            val publicRoot =
                android.os.Environment
                    .getExternalStorageDirectory()
                    .canonicalFile
            require(file.path.startsWith(publicRoot.path + File.separator)) { "只支持共享存储中的文件" }
        }
        val name =
            if (uri.scheme ==
                "content"
            ) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            } else {
                File(uri.path.orEmpty()).name
            }
        val text =
            context.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(limit).toString(Charsets.UTF_8) }
                ?: error("无法读取文件")
        text to (name ?: "component.js")
    }

@Composable
actual fun rememberWidgetFileImport(
    onFile: (String, String) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentFile by rememberUpdatedState(onFile)
    val currentError by rememberUpdatedState(onError)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        val (source, name) = readWidgetFile(context, uri)
                        require(name.endsWith(".js", true)) { "请选择 .js 组件文件" }
                        currentFile(source, "file:$name")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        currentError(e.message ?: "文件读取失败")
                    }
                }
            }
        }
    return { picker.launch(arrayOf("*/*")) }
}

@Composable
actual fun rememberWidgetBackupFiles(
    repository: WidgetRepository,
    onError: (String) -> Unit,
): WidgetBackupActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var restoreText by remember { mutableStateOf<String?>(null) }
    val currentError by rememberUpdatedState(onError)
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        val raw = repository.backup()
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { it.write(raw.toByteArray()) }
                                ?: error("无法写入文件")
                        }
                        currentError("备份已导出")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        currentError(e.message ?: "导出失败")
                    }
                }
            }
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        restoreText = readWidgetFile(context, uri, 24 * MAX_WIDGET_SOURCE_BYTES).first
                    } catch (
                        e: CancellationException,
                    ) {
                        throw e
                    } catch (e: Exception) {
                        currentError(e.message ?: "读取备份失败")
                    }
                }
            }
        }
    restoreText?.let { raw ->
        ConfirmDialog(title = "恢复组件与首页？", message = "将替换当前组件和自定义页面。备份中的脚本会先进行安装检查。", confirmLabel = "恢复", onDismiss = {
            restoreText =
                null
        }, onConfirm = {
            restoreText = null
            scope.launch {
                try {
                    repository.restore(raw)
                    currentError("备份已恢复")
                } catch (
                    e: CancellationException,
                ) {
                    throw e
                } catch (e: Exception) {
                    currentError(e.message ?: "恢复失败")
                }
            }
        })
    }
    return WidgetBackupActions(export = {
        save.launch("Yfuse-home-backup.json")
    }, restore = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
}

actual fun openWidgetVideo(
    media: WidgetMedia,
    request: WidgetPlaybackRequest?,
) {
    val context = checkNotNull(androidAppContext)
    val url = media.videoUrl.toHttpUrl()
    val preferences = GlobalContext.get().get<ThemePreferences>()
    val item = widgetPlaybackItem(media.copy(videoUrl = url.toString()), request)
    val launch =
        PlayerActivity
            .intent(
                context,
                listOf(item),
                0,
                request?.positionMs?.coerceAtLeast(0L) ?: 0L,
                preferences.engine.value,
                preferences.decoder.value,
                false,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(launch)
    } catch (e: Exception) {
        PlayerActivity.discardLaunch(launch)
        throw e
    }
}

actual fun searchWidgetMedia(title: String) {
    val context = checkNotNull(androidAppContext)
    context.startActivity(
        Intent()
            .setComponent(ComponentName(context, "com.yfuse.AppEntry"))
            .putExtra("widget_search_query", title.take(300))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
    )
}
