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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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

/** The production update origin is TLS-only; [validateForUpdateSource] also rejects downgrades. */
private const val UPDATE_MANIFEST = "https://47.112.219.60/yfuse/update-v2.json"
internal const val UPDATE_STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L
internal const val UPDATE_MANIFEST_MAX_BYTES = 64 * 1024
private val updateCacheFileNamePattern =
    Regex("""Yfuse-[A-Za-z0-9][A-Za-z0-9._+-]{0,79}\.apk(?:\.part)?""")

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

/**
 * Constrains the unsigned update manifest before any APK bytes are downloaded.
 *
 * Android still verifies that an update is signed by the installed application's key, and
 * [sha256] verifies transport integrity against the accepted manifest. This policy adds a
 * separate boundary for the legacy HTTP source: it cannot point at an unrelated host or path,
 * and once [sourceUrl] moves to HTTPS it cannot downgrade the APK download back to HTTP.
 */
internal fun UpdateManifest.validateForUpdateSource(sourceUrl: String): UpdateManifest {
    require(versionCode > 0 && versionName.isNotBlank()) { "升级信息不完整" }
    require(size > 0L) { "安装包大小无效" }
    require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "安装包校验值无效" }

    val source = URL(sourceUrl)
    val target = URL(apkUrl)
    require(target.userInfo == null && target.ref == null) { "安装包地址无效" }
    require(target.protocol.equals("https", ignoreCase = true) ||
        target.protocol.equals(source.protocol, ignoreCase = true)
    ) {
        "安装包地址不允许降低连接安全性"
    }
    require(target.host.equals(source.host, ignoreCase = true)) {
        "安装包地址不属于升级服务器"
    }
    val sameEffectivePort = source.portOrDefault() == target.portOrDefault()
    val standardHttpsUpgrade = source.protocol.equals("http", true) &&
        target.protocol.equals("https", true) && target.portOrDefault() == 443
    require(sameEffectivePort || standardHttpsUpgrade) {
        "安装包地址端口不属于升级服务器"
    }
    val normalizedSourcePath = source.toURI().normalize().path
    val normalizedTargetPath = target.toURI().normalize().path
    val sourceDirectory = normalizedSourcePath.substringBeforeLast('/', missingDelimiterValue = "/")
        .trimEnd('/') + "/"
    require(normalizedTargetPath.startsWith(sourceDirectory)) {
        "安装包地址不属于升级目录"
    }
    return this
}

internal fun InputStream.readUpdateManifestText(
    maxBytes: Int = UPDATE_MANIFEST_MAX_BYTES,
): String {
    require(maxBytes in 1 until Int.MAX_VALUE) { "升级信息大小限制无效" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(8 * 1024)
    var readBytes = 0
    while (true) {
        val count = read(buffer, 0, minOf(buffer.size, maxBytes - readBytes + 1))
        if (count < 0) break
        if (count == 0) continue
        readBytes += count
        check(readBytes <= maxBytes) { "升级信息过大" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

internal fun updatePackageFileName(versionCode: Int): String {
    require(versionCode > 0) { "升级信息不完整" }
    return "Yfuse-$versionCode.apk"
}

internal fun cleanupStaleUpdateFiles(
    directory: File,
    keepFileNames: Set<String>,
): Int {
    require(directory.isDirectory) { "升级缓存目录无效" }
    require(keepFileNames.all(updateCacheFileNamePattern::matches)) { "保留文件名无效" }
    val canonicalDirectory = directory.canonicalFile
    val candidates = directory.listFiles() ?: error("无法读取升级缓存目录")
    var deleted = 0
    candidates.forEach { candidate ->
        if (!candidate.isFile ||
            !updateCacheFileNamePattern.matches(candidate.name) ||
            candidate.name in keepFileNames ||
            candidate.canonicalFile.parentFile != canonicalDirectory
        ) {
            return@forEach
        }
        check(candidate.delete()) { "无法清理旧的升级缓存" }
        deleted += 1
    }
    return deleted
}

internal class UpdateDownloadGate {
    internal class Lease internal constructor()

    private var activeLease: Lease? = null

    @Synchronized
    fun tryAcquire(): Lease? {
        if (activeLease != null) return null
        return Lease().also { activeLease = it }
    }

    @Synchronized
    fun runIfActive(lease: Lease, action: () -> Unit): Boolean {
        if (activeLease !== lease) return false
        action()
        return true
    }

    @Synchronized
    fun release(lease: Lease): Boolean {
        if (activeLease !== lease) return false
        activeLease = null
        return true
    }
}

internal fun hasSufficientUpdateStorage(
    usableSpace: Long,
    requiredBytes: Long,
    reserveBytes: Long = UPDATE_STORAGE_RESERVE_BYTES,
): Boolean {
    val usable = usableSpace.coerceAtLeast(0L)
    val required = requiredBytes.coerceAtLeast(0L)
    val reserve = reserveBytes.coerceAtLeast(0L)
    return usable >= reserve && required <= usable - reserve
}

internal fun missingUpdateStorageBytes(
    usableSpace: Long,
    requiredBytes: Long,
    reserveBytes: Long = UPDATE_STORAGE_RESERVE_BYTES,
): Long {
    val usable = usableSpace.coerceAtLeast(0L)
    val required = requiredBytes.coerceAtLeast(0L)
    val reserve = reserveBytes.coerceAtLeast(0L)
    if (required > Long.MAX_VALUE - reserve) return Long.MAX_VALUE
    return (required + reserve - usable).coerceAtLeast(0L)
}

internal fun validateUpdateContentLength(contentLength: Long, expectedBytes: Long) {
    require(expectedBytes > 0L) { "安装包大小无效" }
    if (contentLength >= 0L) {
        require(contentLength == expectedBytes) { "安装包大小与升级信息不一致" }
    }
}

internal fun copyUpdatePackage(
    input: InputStream,
    output: OutputStream,
    expectedBytes: Long,
    onProgress: (Long) -> Unit = {},
): Long {
    require(expectedBytes > 0L) { "安装包大小无效" }
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        check(count.toLong() <= expectedBytes - copied) { "安装包大小超出预期" }
        output.write(buffer, 0, count)
        copied += count
        onProgress(copied)
    }
    check(copied == expectedBytes) { "安装包下载不完整" }
    return copied
}

internal fun writeVerifiedUpdatePackage(
    input: InputStream,
    partialFile: File,
    expectedBytes: Long,
    expectedSha256: String,
    onProgress: (Long) -> Unit = {},
): File {
    try {
        partialFile.outputStream().use { output ->
            copyUpdatePackage(input, output, expectedBytes, onProgress)
        }
        check(partialFile.length() == expectedBytes) { "安装包下载不完整" }
        check(partialFile.sha256().equals(expectedSha256, ignoreCase = true)) {
            "安装包校验失败"
        }
        return partialFile
    } catch (error: Throwable) {
        partialFile.delete()
        throw error
    }
}

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
    private val downloadGate = UpdateDownloadGate()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val state = _state.asStateFlow()
    private var pendingInstall: File? = null

    fun check() {
        scope.launch {
            _state.value = UpdateState.Checking
            AppLog.info(
                category = "update",
                event = "check_started",
                message = "Application update check started",
                attributes = mapOf(
                    "currentVersionName" to BuildConfig.VERSION_NAME,
                    "currentVersionCode" to BuildConfig.VERSION_CODE.toString(),
                ),
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    val connection = (URL(UPDATE_MANIFEST).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 8_000
                        useCaches = false
                    }
                    try {
                        check(connection.contentLengthLong < 0L ||
                            connection.contentLengthLong <= UPDATE_MANIFEST_MAX_BYTES
                        ) {
                            "升级信息过大"
                        }
                        connection.inputStream.use { input ->
                            json.decodeFromString<UpdateManifest>(input.readUpdateManifestText())
                                .validateForUpdateSource(UPDATE_MANIFEST)
                        }
                    } finally {
                        connection.disconnect()
                    }
                }
            }.onSuccess {
                _state.value = if (it.versionCode > BuildConfig.VERSION_CODE) {
                    AppLog.info(
                        category = "update",
                        event = "update_available",
                        message = "Application update is available",
                        attributes = mapOf(
                            "targetVersionName" to it.versionName,
                            "targetVersionCode" to it.versionCode.toString(),
                        ),
                    )
                    UpdateState.Available(it)
                } else {
                    AppLog.info(
                        category = "update",
                        event = "already_current",
                        message = "Application is already current",
                        attributes = mapOf(
                            "publishedVersionName" to it.versionName,
                            "publishedVersionCode" to it.versionCode.toString(),
                        ),
                    )
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
        val lease = downloadGate.tryAcquire() ?: return
        _state.value = UpdateState.Downloading(manifest, progress = 0f)
        scope.launch {
            try {
                AppLog.info(
                    category = "update",
                    event = "download_started",
                    message = "Application update download started",
                    attributes = mapOf(
                        "targetVersionName" to manifest.versionName,
                        "targetVersionCode" to manifest.versionCode.toString(),
                        "expectedBytes" to manifest.size.toString(),
                    ),
                )
                runCatching {
                    manifest.validateForUpdateSource(UPDATE_MANIFEST)
                    withContext(Dispatchers.IO) {
                        val dir = File(activity.cacheDir, "updates")
                        check(dir.isDirectory || dir.mkdirs()) { "无法创建升级缓存目录" }
                        val target = File(dir, updatePackageFileName(manifest.versionCode))
                        val partial = File(dir, "${target.name}.part")
                        cleanupStaleUpdateFiles(dir, keepFileNames = setOf(target.name))
                        check(!partial.exists() || partial.delete()) { "无法清理旧的升级缓存" }
                        val usableSpace = dir.usableSpace
                        if (!hasSufficientUpdateStorage(usableSpace, manifest.size)) {
                            val missingBytes = missingUpdateStorageBytes(usableSpace, manifest.size)
                                .coerceAtLeast(1L)
                            val bytesPerMb = 1024L * 1024L
                            val missingMb = ((missingBytes - 1L) / bytesPerMb) + 1L
                            error("存储空间不足，至少还需 $missingMb MB 可用空间")
                        }
                        val connection = (URL(manifest.apkUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15_000
                            readTimeout = 30_000
                            useCaches = false
                        }
                        try {
                            validateUpdateContentLength(connection.contentLengthLong, manifest.size)
                            connection.inputStream.use { input ->
                                writeVerifiedUpdatePackage(
                                    input = input,
                                    partialFile = partial,
                                    expectedBytes = manifest.size,
                                    expectedSha256 = manifest.sha256,
                                ) { copied ->
                                    downloadGate.runIfActive(lease) {
                                        _state.value = UpdateState.Downloading(
                                            manifest,
                                            (copied.toFloat() / manifest.size).coerceIn(0f, 1f),
                                        )
                                    }
                                }
                            }
                            check(!target.exists() || target.delete()) { "无法替换旧的安装包" }
                            check(partial.renameTo(target)) { "无法保存安装包" }
                            target
                        } catch (error: Throwable) {
                            partial.delete()
                            throw error
                        } finally {
                            connection.disconnect()
                        }
                    }
                }.onSuccess { apk ->
                    if (downloadGate.runIfActive(lease) {
                            _state.value = UpdateState.Ready(manifest, apk)
                        }
                    ) {
                        AppLog.info(
                            category = "update",
                            event = "download_verified",
                            message = "Application update downloaded and verified",
                            attributes = mapOf("targetVersionName" to manifest.versionName),
                        )
                        install(apk)
                    }
                }.onFailure { error ->
                    if (downloadGate.runIfActive(lease) {
                            _state.value = UpdateState.Error(error.message ?: "下载失败", manifest)
                        }
                    ) {
                        AppLog.error(
                            category = "update",
                            event = "download_failed",
                            message = "Update package download failed",
                            throwable = error,
                            attributes = mapOf("targetVersion" to manifest.versionName),
                        )
                    }
                }
            } finally {
                downloadGate.release(lease)
            }
        }
    }

    fun install(apk: File) {
        pendingInstall = apk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            AppLog.info(
                category = "update",
                event = "install_permission_required",
                message = "Unknown-app install permission is required",
            )
            runCatching {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}"),
                    ),
                )
            }.onFailure { error ->
                AppLog.error(
                    category = "update",
                    event = "install_permission_screen_failed",
                    message = "Failed to open unknown-app install permission screen",
                    throwable = error,
                )
                val manifest = (_state.value as? UpdateState.Ready)?.manifest
                _state.value = UpdateState.Error("无法打开安装权限设置", manifest)
            }
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
            AppLog.info(
                category = "update",
                event = "installer_launched",
                message = "Android package installer launched",
            )
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

private fun URL.portOrDefault(): Int = port.takeIf { it >= 0 } ?: defaultPort
