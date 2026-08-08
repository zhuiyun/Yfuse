package com.yfuse.update

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.FileProvider
import com.russhwolf.settings.Settings
import com.yfuse.BuildConfig
import com.yfuse.core.logging.AppLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The production update origin is TLS-only; [validateForUpdateSource] also rejects downgrades. */
private const val UPDATE_MANIFEST = "https://47.112.219.60/yfuse/update-v2.json"
internal const val UPDATE_STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L
internal const val UPDATE_MANIFEST_MAX_BYTES = 64 * 1024

/**
 * Shortest gap between two automatic checks.
 *
 * The check is cheap — a 64 KiB manifest — and is what makes an update visible at all, so it
 * runs whenever 首页 is entered rather than once a day. What is limited to one a day is the
 * dialog: see [AutomaticUpdatePromptGate].
 */
internal const val AUTOMATIC_UPDATE_CHECK_INTERVAL_MS = 30L * 60L * 1_000L
internal const val FAILED_CHECK_RETRY_INTERVAL_MS = 5L * 60L * 1_000L
internal const val UPDATE_DOWNLOAD_RETRY_LIMIT = 3
internal const val UPDATE_DOWNLOAD_RETRY_BASE_DELAY_MS = 2_000L
private const val KEY_LAST_AUTOMATIC_UPDATE_CHECK_EPOCH_MS =
    "update.lastAutomaticCheckEpochMs"
private const val KEY_LAST_PROMPT_EPOCH_DAY = "update.lastPromptEpochDay"
private const val KEY_LAST_PROMPT_VERSION_CODE = "update.lastPromptVersionCode"
private const val KEY_DOWNLOAD_RECORD = "update.download.v1"
private val updateCacheFileNamePattern =
    Regex("""Yfuse-[A-Za-z0-9][A-Za-z0-9._+-]{0,79}\.apk(?:\.part)?""")

val LocalAppUpdateManager = staticCompositionLocalOf<AppUpdateManager?> { null }

internal fun isAutomaticUpdateCheckDue(
    lastCheckEpochMs: Long,
    nowEpochMs: Long,
    intervalMs: Long = AUTOMATIC_UPDATE_CHECK_INTERVAL_MS,
): Boolean {
    require(intervalMs > 0L) { "升级检查间隔无效" }
    val last = lastCheckEpochMs.coerceAtLeast(0L)
    val now = nowEpochMs.coerceAtLeast(0L)
    return last == 0L || now < last || now - last >= intervalMs
}

/** Persists automatic-check attempts so activity and process recreation cannot bypass the limit. */
internal class AutomaticUpdateCheckGate(
    private val settings: Settings,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = nowEpochMs().coerceAtLeast(0L)
        val lastCheck = settings.getLong(KEY_LAST_AUTOMATIC_UPDATE_CHECK_EPOCH_MS, 0L)
        if (!isAutomaticUpdateCheckDue(lastCheck, now)) return false
        // Record the attempt before starting I/O. A failing endpoint must not be hammered again
        // every time the user returns to 首页; the profile screen still offers manual retry.
        settings.putLong(KEY_LAST_AUTOMATIC_UPDATE_CHECK_EPOCH_MS, now)
        return true
    }

    /**
     * Shortens the wait after a failed attempt.
     *
     * The check budget is spent before the request is made, so without this a single offline
     * moment hides a published update for the whole interval.
     */
    @Synchronized
    fun releaseForRetry() {
        val now = nowEpochMs().coerceAtLeast(0L)
        val backdated = now - (AUTOMATIC_UPDATE_CHECK_INTERVAL_MS - FAILED_CHECK_RETRY_INTERVAL_MS)
        settings.putLong(KEY_LAST_AUTOMATIC_UPDATE_CHECK_EPOCH_MS, backdated.coerceAtLeast(0L))
    }
}

/**
 * 更新弹窗每天只自动弹一次.
 *
 * Checks may run many times a day, but the dialog interrupts at most once per local day for a
 * given version. A newly published version is allowed its own prompt on the same day —
 * otherwise a release cut hours after the user dismissed the previous one would stay silent
 * until tomorrow. Explicit checks from 我的 bypass this gate entirely.
 */
internal class AutomaticUpdatePromptGate(
    private val settings: Settings,
    private val nowEpochDay: () -> Long = ::localEpochDay,
) {
    @Synchronized
    fun tryAcquire(versionCode: Int): Boolean {
        require(versionCode > 0) { "升级信息不完整" }
        val today = nowEpochDay()
        val lastDay = settings.getLong(KEY_LAST_PROMPT_EPOCH_DAY, Long.MIN_VALUE)
        val lastVersion = settings.getInt(KEY_LAST_PROMPT_VERSION_CODE, 0)
        // A wall-clock change in either direction ends the day, which at worst costs one extra
        // prompt and never silences one.
        if (lastDay == today && lastVersion == versionCode) return false
        settings.putLong(KEY_LAST_PROMPT_EPOCH_DAY, today)
        settings.putInt(KEY_LAST_PROMPT_VERSION_CODE, versionCode)
        return true
    }
}

/** The day boundary users actually experience is the local one, not UTC's. */
private fun localEpochDay(): Long {
    val now = System.currentTimeMillis()
    return TimeUnit.MILLISECONDS.toDays(now + TimeZone.getDefault().getOffset(now))
}

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
 * What a partially downloaded package belongs to.
 *
 * Persisted next to the `.part` file so a resume survives process death: without the manifest
 * there is nothing to check the remaining bytes against, and without [validator] there is no
 * proof the remote package is still the same bytes the partial file came from.
 */
@Serializable
internal data class UpdateDownloadRecord(
    val manifest: UpdateManifest,
    val validator: String? = null,
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

private val updateContentRangePattern =
    Regex("""(?i)^bytes\s+(\d+)-(\d+)/(\d+|\*)$""")

/**
 * True when a `Content-Range` header describes exactly the tail this download still needs.
 *
 * A server that answers a range request with a different window would otherwise be appended
 * to the partial file at the wrong offset, producing a package that only fails at the digest
 * check after the whole download was paid for.
 */
internal fun updateContentRangeContinues(
    value: String?,
    expectedOffset: Long,
    expectedTotal: Long,
): Boolean {
    if (expectedOffset <= 0L || expectedTotal <= expectedOffset) return false
    val match = value?.trim()?.let(updateContentRangePattern::matchEntire) ?: return false
    val start = match.groupValues[1].toLongOrNull() ?: return false
    val end = match.groupValues[2].toLongOrNull() ?: return false
    val total = match.groupValues[3].let { if (it == "*") expectedTotal else it.toLongOrNull() }
        ?: return false
    return start == expectedOffset && end == expectedTotal - 1L && total == expectedTotal
}

/**
 * Whether the response may be appended to the partial file.
 *
 * The window has to be exactly the tail still missing, and a validator the partial file was
 * recorded with may not have changed under us. A server that offers no validator at all can
 * still be resumed from: the manifest pins the total size and the SHA-256, so the worst case
 * is one wasted attempt that fails verification and restarts from zero.
 */
internal fun canAppendUpdateRange(
    existingBytes: Long,
    expectedTotalBytes: Long,
    statusCode: Int,
    contentRange: String?,
    expectedValidator: String?,
    responseValidator: String?,
): Boolean = existingBytes > 0L &&
    statusCode == HttpURLConnection.HTTP_PARTIAL &&
    updateContentRangeContinues(contentRange, existingBytes, expectedTotalBytes) &&
    (expectedValidator.isNullOrBlank() || expectedValidator == responseValidator)

/**
 * Streams the package body, resuming at [startBytes].
 *
 * Returns the number of bytes present once the stream ends or [shouldContinue] turns false —
 * an interrupted transfer is not an error here, it is the state a later resume starts from.
 */
internal fun copyUpdatePackage(
    input: InputStream,
    output: OutputStream,
    expectedBytes: Long,
    startBytes: Long = 0L,
    shouldContinue: () -> Boolean = { true },
    onProgress: (Long) -> Unit = {},
): Long {
    require(expectedBytes > 0L) { "安装包大小无效" }
    require(startBytes in 0L until expectedBytes) { "续传位置无效" }
    val buffer = ByteArray(64 * 1024)
    var copied = startBytes
    while (shouldContinue()) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        check(count.toLong() <= expectedBytes - copied) { "安装包大小超出预期" }
        output.write(buffer, 0, count)
        copied += count
        onProgress(copied)
    }
    return copied
}

/**
 * Appends the response body to [partialFile] and returns the bytes it now holds.
 *
 * The partial file deliberately survives failures: it is the 断点续传 checkpoint. Only a
 * mismatch that proves the bytes are unusable removes it, which is [verifyUpdatePackage]'s job.
 */
internal fun appendUpdatePackage(
    input: InputStream,
    partialFile: File,
    startBytes: Long,
    expectedBytes: Long,
    shouldContinue: () -> Boolean = { true },
    onProgress: (Long) -> Unit = {},
): Long {
    require(startBytes in 0L until expectedBytes) { "续传位置无效" }
    check(startBytes == 0L || partialFile.length() == startBytes) { "续传文件已变化" }
    return FileOutputStream(partialFile, startBytes > 0L).use { output ->
        val copied = copyUpdatePackage(
            input = input,
            output = output,
            expectedBytes = expectedBytes,
            startBytes = startBytes,
            shouldContinue = shouldContinue,
            onProgress = onProgress,
        )
        // Written bytes have to be on disk, not in the page cache: the point of a checkpoint is
        // that it is still there after the process is killed.
        output.flush()
        output.fd.sync()
        copied
    }
}

/** Verifies a completed package; a file that fails is deleted so the next attempt restarts. */
internal fun verifyUpdatePackage(
    file: File,
    expectedBytes: Long,
    expectedSha256: String,
): File {
    try {
        check(file.length() == expectedBytes) { "安装包下载不完整" }
        check(file.sha256().equals(expectedSha256, ignoreCase = true)) { "安装包校验失败" }
        return file
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(
        val manifest: UpdateManifest,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState {
        val progress: Float get() = updateProgress(downloadedBytes, totalBytes)
    }

    /** Held bytes waiting to be resumed — a user pause, a lost connection, or a killed process. */
    data class Paused(
        val manifest: UpdateManifest,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val message: String? = null,
    ) : UpdateState {
        val progress: Float get() = updateProgress(downloadedBytes, totalBytes)
    }

    data class Ready(val manifest: UpdateManifest, val apk: File) : UpdateState
    data class Error(val message: String, val manifest: UpdateManifest? = null) : UpdateState
}

internal fun updateProgress(downloadedBytes: Long, totalBytes: Long): Float =
    if (totalBytes <= 0L) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)

/**
 * Owns update checking, downloading and installing for the whole process.
 *
 * Application-scoped rather than activity-scoped: a download has to keep running while the
 * user carries on browsing, after the dialog is dismissed, across a rotation, and while Yfuse
 * is in the background — the transfer itself runs inside [UpdateDownloadService].
 */
class AppUpdateManager(
    context: Context,
    private val settings: Settings,
) {
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val downloadMutex = Mutex()
    private val automaticCheckGate = AutomaticUpdateCheckGate(settings)
    private val promptGate = AutomaticUpdatePromptGate(settings)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    /** Whether the update dialog is on screen. Dismissing it never stops a running download. */
    private val _promptVisible = MutableStateFlow(false)
    val promptVisible = _promptVisible.asStateFlow()

    private var pendingInstall: File? = null
    private var checkJob: Job? = null

    @Volatile
    private var pauseRequested = false

    /**
     * Bumped by every [download] call so a transfer that was paused and immediately resumed can
     * tell it has been superseded, and leaves the state to the run that replaced it.
     */
    @Volatile
    private var requestGeneration = 0

    @Volatile
    private var foregroundActivities = 0

    init {
        restoreInterruptedDownload()
        (appContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val wasBackground = foregroundActivities == 0
                    foregroundActivities += 1
                    // Returning to Yfuse is the other moment 首页 is entered; the gate keeps
                    // this from turning into a request every time the user switches apps.
                    if (wasBackground) checkIfDue()
                }

                override fun onActivityPaused(activity: Activity) {
                    foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
                }

                override fun onActivityCreated(activity: Activity, saved: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, out: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /**
     * 进入首页时的自动检测. Throttled to [AUTOMATIC_UPDATE_CHECK_INTERVAL_MS]; the dialog that
     * follows is limited separately to once a day.
     */
    fun checkIfDue() {
        if (!automaticCheckGate.tryAcquire()) {
            AppLog.info(
                category = "update",
                event = "automatic_check_skipped",
                message = "Automatic update check skipped within the check interval",
            )
            return
        }
        runCheck(automatic = true)
    }

    /** Explicit user checks always bypass both the check interval and the daily prompt limit. */
    fun check() = runCheck(automatic = false)

    private fun runCheck(automatic: Boolean) {
        if (checkJob?.isActive == true) return
        // A running or held download already knows which version it is fetching; re-checking
        // would only replace that progress with a fresh Available state.
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Paused) {
            if (!automatic) _promptVisible.value = true
            return
        }
        val previous = _state.value
        checkJob = scope.launch {
            // A dialog left open from an earlier result must not stand in for this one's, or
            // the daily limit could be bypassed by a prompt that was never closed.
            _promptVisible.value = false
            _state.value = UpdateState.Checking
            AppLog.info(
                category = "update",
                event = "check_started",
                message = "Application update check started",
                attributes = mapOf(
                    "automatic" to automatic.toString(),
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
            }.onSuccess { manifest ->
                if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
                    AppLog.info(
                        category = "update",
                        event = "already_current",
                        message = "Application is already current",
                        attributes = mapOf(
                            "publishedVersionName" to manifest.versionName,
                            "publishedVersionCode" to manifest.versionCode.toString(),
                        ),
                    )
                    clearDownloadRecord()
                    _state.value = UpdateState.Current
                    return@onSuccess
                }
                AppLog.info(
                    category = "update",
                    event = "update_available",
                    message = "Application update is available",
                    attributes = mapOf(
                        "targetVersionName" to manifest.versionName,
                        "targetVersionCode" to manifest.versionCode.toString(),
                    ),
                )
                _state.value = withContext(Dispatchers.IO) { availableState(manifest) }
                if (!automatic) {
                    _promptVisible.value = true
                } else if (promptGate.tryAcquire(manifest.versionCode)) {
                    _promptVisible.value = true
                } else {
                    AppLog.info(
                        category = "update",
                        event = "prompt_suppressed",
                        message = "Update dialog already shown for this version today",
                        attributes = mapOf("targetVersionCode" to manifest.versionCode.toString()),
                    )
                }
            }.onFailure { error ->
                // Startup remains usable when the private update host is offline.
                AppLog.warning(
                    category = "update",
                    event = "check_failed",
                    message = "Update check failed",
                    throwable = error,
                )
                if (automatic) automaticCheckGate.releaseForRetry()
                // An unreachable server says nothing about a package already downloaded and
                // verified, so a staged install is not thrown away by a failed check.
                _state.value = previous as? UpdateState.Ready
                    ?: UpdateState.Error("暂时无法连接升级服务器")
            }
        }
    }

    /** Starts or resumes the background download. Safe to call while one is already running. */
    fun download(manifest: UpdateManifest) {
        if (_state.value is UpdateState.Downloading) return
        pauseRequested = false
        requestGeneration += 1
        val existing = partialFile(manifest).takeIf(File::isFile)?.length() ?: 0L
        putDownloadRecord(
            UpdateDownloadRecord(manifest, validator = storedValidator(manifest)),
        )
        _state.value = UpdateState.Downloading(
            manifest = manifest,
            downloadedBytes = existing.coerceAtMost(manifest.size),
            totalBytes = manifest.size,
        )
        runCatching {
            appContext.startForegroundService(
                Intent(appContext, UpdateDownloadService::class.java),
            )
        }.onFailure { error ->
            AppLog.error(
                category = "update",
                event = "download_service_start_failed",
                message = "Update download service could not be started",
                throwable = error,
            )
            _state.value = UpdateState.Paused(
                manifest = manifest,
                downloadedBytes = existing,
                totalBytes = manifest.size,
                message = "无法启动后台下载",
            )
        }
    }

    /** Keeps the bytes already fetched; [download] picks up from exactly there. */
    fun pauseDownload() {
        val current = _state.value as? UpdateState.Downloading ?: return
        pauseRequested = true
        _state.value = UpdateState.Paused(
            manifest = current.manifest,
            downloadedBytes = current.downloadedBytes,
            totalBytes = current.totalBytes,
        )
        AppLog.info(
            category = "update",
            event = "download_paused",
            message = "Update download paused by the user",
        )
    }

    fun showPrompt() {
        _promptVisible.value = true
    }

    /** The dialog closes; a download in flight keeps running in the background. */
    fun dismissPrompt() {
        _promptVisible.value = false
    }

    /**
     * Runs the transfer. Called by [UpdateDownloadService] so it is covered by a foreground
     * service for as long as it lasts.
     *
     * Transfers are serialized rather than skipped: a resume that arrives while the previous
     * one is still winding down waits its turn instead of being dropped on the floor.
     */
    internal suspend fun runActiveDownload() {
        downloadMutex.withLock {
            runActiveDownloadLocked()
        }
    }

    private suspend fun runActiveDownloadLocked() {
        val record = downloadRecord() ?: return
        // Only a request that is still wanted runs: a pause between the service start and this
        // point has already moved the state on.
        if (pauseRequested || _state.value !is UpdateState.Downloading) return
        val generation = requestGeneration
        val manifest = record.manifest
        var attempt = 0
        while (true) {
            if (!isCurrentRequest(generation)) break
            // A completed or paused transfer is done either way; only a throw is retried.
            val error = runCatching { downloadOnce(manifest, generation) }
                .exceptionOrNull() ?: break
            attempt += 1
            val downloaded = partialFile(manifest).takeIf(File::isFile)?.length() ?: 0L
            AppLog.warning(
                category = "update",
                event = "download_attempt_failed",
                message = "Update package download attempt failed",
                throwable = error,
                attributes = mapOf(
                    "attempt" to attempt.toString(),
                    "downloadedBytes" to downloaded.toString(),
                ),
            )
            if (!isCurrentRequest(generation)) break
            if (attempt >= UPDATE_DOWNLOAD_RETRY_LIMIT) {
                _state.value = UpdateState.Paused(
                    manifest = manifest,
                    downloadedBytes = downloaded,
                    totalBytes = manifest.size,
                    message = error.message ?: "下载失败",
                )
                break
            }
            _state.value = UpdateState.Paused(
                manifest = manifest,
                downloadedBytes = downloaded,
                totalBytes = manifest.size,
                message = "网络中断，正在重试…",
            )
            delay(UPDATE_DOWNLOAD_RETRY_BASE_DELAY_MS shl (attempt - 1))
            if (!isCurrentRequest(generation)) break
            _state.value = UpdateState.Downloading(
                manifest = manifest,
                downloadedBytes = downloaded,
                totalBytes = manifest.size,
            )
        }
    }

    /** A transfer that was paused, or replaced by a newer request, may no longer write state. */
    private fun isCurrentRequest(generation: Int): Boolean =
        !pauseRequested && generation == requestGeneration

    /** Returns true once the package is downloaded, verified and staged for install. */
    private suspend fun downloadOnce(
        manifest: UpdateManifest,
        generation: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        manifest.validateForUpdateSource(UPDATE_MANIFEST)
        val directory = updateDirectory()
        val target = File(directory, updatePackageFileName(manifest.versionCode))
        val partial = File(directory, "${target.name}.part")
        // The partial file is a checkpoint, not litter: it has to survive the sweep.
        cleanupStaleUpdateFiles(directory, keepFileNames = setOf(target.name, partial.name))

        if (target.isFile && target.length() == manifest.size &&
            target.sha256().equals(manifest.sha256, ignoreCase = true)
        ) {
            partial.delete()
            finish(manifest, target)
            return@withContext true
        }

        val record = downloadRecord()?.takeIf { it.manifest.versionCode == manifest.versionCode }
        var existing = partial.takeIf(File::isFile)?.length() ?: 0L
        var validator = record?.validator?.takeIf { existing > 0L }
        if (existing > 0L && record == null) {
            // Those bytes were fetched for some other package.
            partial.delete()
            existing = 0L
        }
        if (existing >= manifest.size) {
            // Complete but never verified — most likely killed between the last write and the
            // digest check.
            val verified = runCatching {
                verifyUpdatePackage(partial, manifest.size, manifest.sha256)
            }
            if (verified.isSuccess) {
                promote(partial, target)
                finish(manifest, target)
                return@withContext true
            }
            existing = 0L
            validator = null
        }

        val remaining = manifest.size - existing
        val usableSpace = directory.usableSpace
        if (!hasSufficientUpdateStorage(usableSpace, remaining)) {
            val missingBytes = missingUpdateStorageBytes(usableSpace, remaining).coerceAtLeast(1L)
            val bytesPerMb = 1024L * 1024L
            val missingMb = ((missingBytes - 1L) / bytesPerMb) + 1L
            error("存储空间不足，至少还需 $missingMb MB 可用空间")
        }

        AppLog.info(
            category = "update",
            event = "download_started",
            message = "Application update download started",
            attributes = mapOf(
                "targetVersionName" to manifest.versionName,
                "targetVersionCode" to manifest.versionCode.toString(),
                "expectedBytes" to manifest.size.toString(),
                "resumeBytes" to existing.toString(),
            ),
        )

        var connection: HttpURLConnection? = null
        try {
            var append: Boolean
            var responseValidator: String?
            while (true) {
                connection = (URL(manifest.apkUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    useCaches = false
                    if (existing > 0L) {
                        setRequestProperty("Range", "bytes=$existing-")
                        validator?.let { setRequestProperty("If-Range", it.validatorHeaderValue()) }
                    }
                }
                val code = connection.responseCode
                responseValidator = connection.updateResumeValidator()
                append = canAppendUpdateRange(
                    existingBytes = existing,
                    expectedTotalBytes = manifest.size,
                    statusCode = code,
                    contentRange = connection.getHeaderField("Content-Range"),
                    expectedValidator = validator,
                    responseValidator = responseValidator,
                )
                val rejectedResume = existing > 0L && !append && (
                    code == HTTP_RANGE_NOT_SATISFIABLE || code == HttpURLConnection.HTTP_PARTIAL
                    )
                if (rejectedResume) {
                    AppLog.warning(
                        category = "update",
                        event = "resume_rejected",
                        message = "Update range response did not continue the partial file",
                        attributes = mapOf("status" to code.toString()),
                    )
                    connection.disconnect()
                    connection = null
                    partial.delete()
                    existing = 0L
                    validator = null
                    continue
                }
                if (code !in 200..299) error("HTTP $code")
                if (code == HttpURLConnection.HTTP_PARTIAL && existing == 0L) {
                    error("服务器返回了无请求的分段响应")
                }
                if (!append && existing > 0L) {
                    // A plain 200 to an If-Range request means the package changed.
                    partial.delete()
                    existing = 0L
                }
                break
            }
            val activeConnection = checkNotNull(connection) { "升级连接已关闭" }
            validateUpdateContentLength(activeConnection.contentLengthLong, manifest.size - existing)
            putDownloadRecord(UpdateDownloadRecord(manifest, validator = responseValidator))
            val copied = activeConnection.inputStream.use { input ->
                appendUpdatePackage(
                    input = input,
                    partialFile = partial,
                    startBytes = existing,
                    expectedBytes = manifest.size,
                    shouldContinue = { isCurrentRequest(generation) },
                ) { downloaded ->
                    // A pause has already moved the state on; progress must not undo it.
                    if (isCurrentRequest(generation) && _state.value is UpdateState.Downloading) {
                        _state.value = UpdateState.Downloading(
                            manifest = manifest,
                            downloadedBytes = downloaded,
                            totalBytes = manifest.size,
                        )
                    }
                }
            }
            if (copied < manifest.size) {
                check(!isCurrentRequest(generation)) { "安装包下载不完整" }
                if (generation == requestGeneration) {
                    // The pause was recorded before the last chunks landed; show what the
                    // resume will actually start from. A newer request owns the state instead.
                    _state.value = UpdateState.Paused(manifest, copied, manifest.size)
                }
                AppLog.info(
                    category = "update",
                    event = "download_interrupted",
                    message = "Update download stopped with a resumable partial file",
                    attributes = mapOf("downloadedBytes" to copied.toString()),
                )
                return@withContext false
            }
            verifyUpdatePackage(partial, manifest.size, manifest.sha256)
            promote(partial, target)
            finish(manifest, target)
            true
        } finally {
            connection?.disconnect()
        }
    }

    private fun promote(partial: File, target: File) {
        check(!target.exists() || target.delete()) { "无法替换旧的安装包" }
        check(partial.renameTo(target)) { "无法保存安装包" }
    }

    private fun finish(manifest: UpdateManifest, apk: File) {
        clearDownloadRecord()
        AppLog.info(
            category = "update",
            event = "download_verified",
            message = "Application update downloaded and verified",
            attributes = mapOf("targetVersionName" to manifest.versionName),
        )
        _state.value = UpdateState.Ready(manifest, apk)
        // The installer is an activity. Launching one from the background is both blocked by
        // Android and rude, so a download finished behind the user's back waits for them.
        if (foregroundActivities > 0) {
            scope.launch { install(apk) }
        } else {
            pendingInstall = apk
            AppLog.info(
                category = "update",
                event = "install_deferred",
                message = "Install deferred until Yfuse returns to the foreground",
            )
        }
    }

    fun install(apk: File) {
        pendingInstall = apk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            AppLog.info(
                category = "update",
                event = "install_permission_required",
                message = "Unknown-app install permission is required",
            )
            runCatching {
                appContext.startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${appContext.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
                appContext,
                "${appContext.packageName}.updates",
                apk,
            )
            appContext.startActivity(
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
        val apk = pendingInstall?.takeIf(File::isFile) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            appContext.packageManager.canRequestPackageInstalls()
        ) install(apk)
    }

    /**
     * Puts a download interrupted by process death back on screen as resumable progress,
     * so 断点续传 survives more than a lost connection.
     */
    private fun restoreInterruptedDownload() {
        val record = downloadRecord() ?: return
        if (record.manifest.versionCode <= BuildConfig.VERSION_CODE) {
            clearDownloadRecord()
            return
        }
        val directory = File(appContext.cacheDir, "updates")
        val target = File(directory, updatePackageFileName(record.manifest.versionCode))
        val existing = File(directory, "${target.name}.part").takeIf(File::isFile)?.length() ?: 0L
        // Deliberately not staged as a pending install: the installer may only be launched by
        // an action the user just took, never because the app happened to start.
        if (target.isFile && target.length() == record.manifest.size) {
            _state.value = UpdateState.Ready(record.manifest, target)
            return
        }
        if (existing <= 0L) return
        _state.value = UpdateState.Paused(
            manifest = record.manifest,
            downloadedBytes = existing.coerceAtMost(record.manifest.size),
            totalBytes = record.manifest.size,
        )
        AppLog.info(
            category = "update",
            event = "download_restored",
            message = "Interrupted update download restored as resumable",
            attributes = mapOf("downloadedBytes" to existing.toString()),
        )
    }

    private fun availableState(manifest: UpdateManifest): UpdateState {
        val directory = File(appContext.cacheDir, "updates")
        val target = File(directory, updatePackageFileName(manifest.versionCode))
        if (target.isFile && target.length() == manifest.size) {
            return UpdateState.Ready(manifest, target)
        }
        val record = downloadRecord()?.takeIf { it.manifest.versionCode == manifest.versionCode }
        val existing = File(directory, "${target.name}.part")
            .takeIf { record != null && it.isFile }
            ?.length()
            ?: 0L
        return if (existing in 1 until manifest.size) {
            UpdateState.Paused(manifest, existing, manifest.size)
        } else {
            UpdateState.Available(manifest)
        }
    }

    private fun updateDirectory(): File {
        val directory = File(appContext.cacheDir, "updates")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建升级缓存目录" }
        return directory
    }

    private fun partialFile(manifest: UpdateManifest): File = File(
        File(appContext.cacheDir, "updates"),
        "${updatePackageFileName(manifest.versionCode)}.part",
    )

    private fun storedValidator(manifest: UpdateManifest): String? = downloadRecord()
        ?.takeIf { it.manifest.versionCode == manifest.versionCode }
        ?.validator

    private fun downloadRecord(): UpdateDownloadRecord? {
        val raw = settings.getStringOrNull(KEY_DOWNLOAD_RECORD) ?: return null
        return runCatching {
            json.decodeFromString(UpdateDownloadRecord.serializer(), raw)
        }.onFailure {
            AppLog.warning(
                category = "update",
                event = "download_record_invalid",
                message = "Stored update download record could not be decoded",
                throwable = it,
            )
            settings.remove(KEY_DOWNLOAD_RECORD)
        }.getOrNull()
    }

    private fun putDownloadRecord(record: UpdateDownloadRecord) {
        settings.putString(
            KEY_DOWNLOAD_RECORD,
            json.encodeToString(UpdateDownloadRecord.serializer(), record),
        )
    }

    private fun clearDownloadRecord() {
        settings.remove(KEY_DOWNLOAD_RECORD)
    }

    private companion object {
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}

/**
 * A value that changes whenever the remote package does, sent back as `If-Range` so the server
 * itself decides whether the partial file may be continued.
 */
private fun HttpURLConnection.updateResumeValidator(): String? {
    val strongEtag = getHeaderField("ETag")
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.startsWith("W/", ignoreCase = true) }
    if (strongEtag != null) return "etag:$strongEtag"
    return getHeaderField("Last-Modified")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "last-modified:$it" }
}

private fun String.validatorHeaderValue(): String = substringAfter(':')

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
