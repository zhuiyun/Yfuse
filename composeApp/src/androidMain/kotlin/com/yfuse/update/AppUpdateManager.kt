package com.yfuse.update

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.FileProvider
import com.yfuse.BuildConfig
import com.yfuse.core.logging.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val UPDATE_MANIFEST = "http://47.112.219.60/yfuse/update.json"

val LocalAppUpdateManager = staticCompositionLocalOf<AppUpdateManager?> { null }

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val notes: String = "",
)

sealed interface UpdateState {
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateState
    data class Ready(val manifest: UpdateManifest, val apk: File) : UpdateState
    data class Error(val message: String, val manifest: UpdateManifest? = null) : UpdateState
}

class AppUpdateManager(private val activity: Activity) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val state = _state.asStateFlow()
    private var pendingInstall: File? = null

    fun check() {
        scope.launch {
            _state.value = UpdateState.Checking
            runCatching {
                withContext(Dispatchers.IO) {
                    val connection = (URL(UPDATE_MANIFEST).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        useCaches = false
                    }
                    connection.inputStream.bufferedReader().use {
                        json.decodeFromString<UpdateManifest>(it.readText())
                    }
                }
            }.onSuccess {
                _state.value = if (it.versionCode > BuildConfig.VERSION_CODE) {
                    UpdateState.Available(it)
                } else {
                    UpdateState.Current
                }
            }.onFailure { error ->
                // Startup remains usable when the private update host is offline.
                AppLog.warning(
                    category = "update",
                    event = "check_failed",
                    message = "Update check failed",
                    throwable = error,
                )
                _state.value = UpdateState.Error("暂时无法连接升级服务器")
            }
        }
    }

    fun download(manifest: UpdateManifest) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                    val target = File(dir, "Yfuse-${manifest.versionName}.apk")
                    val connection = (URL(manifest.apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                    }
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: manifest.size
                    connection.inputStream.use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var copied = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                _state.value = UpdateState.Downloading(
                                    manifest,
                                    if (total > 0) copied.toFloat() / total else 0f,
                                )
                            }
                        }
                    }
                    require(target.sha256().equals(manifest.sha256, ignoreCase = true)) {
                        "安装包校验失败"
                    }
                    target
                }
            }.onSuccess {
                _state.value = UpdateState.Ready(manifest, it)
                install(it)
            }.onFailure { error ->
                AppLog.error(
                    category = "update",
                    event = "download_failed",
                    message = "Update package download failed",
                    throwable = error,
                    attributes = mapOf("targetVersion" to manifest.versionName),
                )
                _state.value = UpdateState.Error(error.message ?: "下载失败", manifest)
            }
        }
    }

    fun install(apk: File) {
        pendingInstall = apk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.updates",
                apk,
            )
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    clipData = ClipData.newRawUri("Yfuse update", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onSuccess {
            pendingInstall = null
        }.onFailure { error ->
            AppLog.error(
                category = "update",
                event = "installer_launch_failed",
                message = "Failed to open Android package installer",
                throwable = error,
            )
            val manifest = (_state.value as? UpdateState.Ready)?.manifest
            _state.value = UpdateState.Error("无法打开系统安装程序", manifest)
        }
    }

    fun resumeInstall() {
        val apk = pendingInstall ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            activity.packageManager.canRequestPackageInstalls()
        ) install(apk)
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
