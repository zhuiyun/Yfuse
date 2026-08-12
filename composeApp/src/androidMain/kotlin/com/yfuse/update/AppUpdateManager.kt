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

/** Release ordering is defined by Android's monotonic versionCode, not versionName parsing. */
internal fun isPublishedUpdateAvailable(
    publishedVersionCode: Int,
    installedVersionCode: Int,
): Boolean = publishedVersionCode > installedVersionCode

/**
 * The first foreground transition belongs to [AppUpdateManager.checkOnLaunch].
 *
 * Activity resume happens before the splash-gated update overlay is composed. Starting a due
 * check there can discover the update and consume its daily prompt allowance; when the overlay
 * later runs the mandatory launch check, that check clears the first prompt while entering
 * Checking and the allowance prevents it from reopening. Once the launch check has started,
 * later foreground transitions may use the normal interval gate.
 */
internal fun shouldCheckForUpdateOnForeground(
    wasBackground: Boolean,
    launchCheckStarted: Boolean,
): Boolean = wasBackground && launchCheckStarted

/** Persists automatic-check attempts so activity and process recreation cannot bypass the limit. */
internal class AutomaticUpdateCheckGate(
    private val settings: Settings,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun tryAcquire(force: Boolean = false): Boolean {
        val now = nowEpochMs().coerceAtLeast(0L)
        val lastCheck = settings.getLong(KEY_LAST_AUTOMATIC_UPDATE_CHECK_EPOCH_MS, 0L)
        if (!force && !isAutomaticUpdateCheckDue(lastCheck, now)) return false
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
 * The partial file deliberately survives failures: it is the 断点续传 checkpoint. Completed
 * bytes are checked read-only, then an owner-aware manager operation decides whether to delete.
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

/** Read-only package verification used while an asynchronous restore may become stale. */
internal fun cachedUpdatePackageMatches(file: File, manifest: UpdateManifest): Boolean =
    file.isFile &&
        file.length() == manifest.size &&
        runCatching { file.sha256().equals(manifest.sha256, ignoreCase = true) }
            .getOrDefault(false)

internal enum class OwnedUpdateCacheDeleteResult {
    Deleted,
    Missing,
    StaleOwner,
    Failed,
}

/**
 * Deletes one cache path only while it still belongs to the restore that inspected it.
 *
 * The file name contains only versionCode, so comparing that field alone would let an old restore
 * delete a same-version package with a new digest. The caller serializes this ownership check and
 * deletion with generation/record mutations.
 */
internal fun deleteUpdateCacheFileIfOwned(
    file: File,
    expectedGeneration: Int,
    currentGeneration: Int,
    expectedManifest: UpdateManifest,
    currentRecord: UpdateDownloadRecord?,
): OwnedUpdateCacheDeleteResult {
    if (expectedGeneration != currentGeneration ||
        currentRecord?.manifest?.hasSamePackageAs(expectedManifest) != true
    ) {
        return OwnedUpdateCacheDeleteResult.StaleOwner
    }
    if (!file.exists()) return OwnedUpdateCacheDeleteResult.Missing
    return if (file.delete()) {
        OwnedUpdateCacheDeleteResult.Deleted
    } else {
        OwnedUpdateCacheDeleteResult.Failed
    }
}

internal fun updateDownloadOwnerStillCurrent(
    expectedGeneration: Int,
    currentGeneration: Int,
    pauseRequested: Boolean,
    expectedManifest: UpdateManifest,
    currentRecord: UpdateDownloadRecord?,
): Boolean = !pauseRequested &&
    expectedGeneration == currentGeneration &&
    currentRecord?.manifest?.hasSamePackageAs(expectedManifest) == true

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

internal enum class ActiveDownloadManifestAction {
    Keep,
    Replace,
    Invalidate,
}

/**
 * Reconciles a package already being downloaded with the latest accepted manifest.
 *
 * Notes and display names may change without invalidating bytes already on disk. The fields
 * below are the package identity: if any of them changes, continuing the old transfer risks
 * staging an artifact the update server no longer publishes.
 */
internal fun activeDownloadManifestAction(
    active: UpdateManifest,
    published: UpdateManifest,
    installedVersionCode: Int,
): ActiveDownloadManifestAction = when {
    published.versionCode <= installedVersionCode -> ActiveDownloadManifestAction.Invalidate
    active.hasSamePackageAs(published) -> ActiveDownloadManifestAction.Keep
    else -> ActiveDownloadManifestAction.Replace
}

internal fun UpdateManifest.hasSamePackageAs(other: UpdateManifest): Boolean =
    versionCode == other.versionCode &&
        apkUrl == other.apkUrl &&
        size == other.size &&
        sha256.equals(other.sha256, ignoreCase = true)

/** Keeps metadata refreshed by a concurrent manifest check when a transfer finishes. */
internal fun latestManifestForFinishedDownload(
    downloaded: UpdateManifest,
    currentState: UpdateState,
): UpdateManifest {
    val current = when (currentState) {
        is UpdateState.Downloading -> currentState.manifest
        is UpdateState.Paused -> currentState.manifest
        is UpdateState.Ready -> currentState.manifest
        else -> null
    }
    return current?.takeIf { it.hasSamePackageAs(downloaded) } ?: downloaded
}

/** A transfer may refresh its validator without rolling back newer notes/name metadata. */
internal fun mergeDownloadRecordValidator(
    current: UpdateDownloadRecord,
    attempted: UpdateDownloadRecord,
): UpdateDownloadRecord? = current
    .takeIf { it.manifest.hasSamePackageAs(attempted.manifest) }
    ?.copy(validator = attempted.validator)

internal fun updateCheckSnapshotStillCurrent(
    startGeneration: Int,
    currentGeneration: Int,
    startedWithOwnedState: Boolean,
    currentState: UpdateState,
): Boolean = startGeneration == currentGeneration &&
    (startedWithOwnedState || currentState !is UpdateState.Downloading &&
        currentState !is UpdateState.Paused && currentState !is UpdateState.Ready)

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
    private var launchCheckStarted = false

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

    private data class ActiveDownloadCheck(
        val generation: Int,
        val manifest: UpdateManifest,
    )

    private data class ActiveDownloadRequest(
        val generation: Int,
        val manifest: UpdateManifest,
    )

    private data class UpdateCheckSnapshot(
        val generation: Int,
        val previous: UpdateState,
        val activeDownload: ActiveDownloadCheck?,
        val startedWithOwnedState: Boolean,
    )

    private data class RestoreDownloadOwner(
        val generation: Int,
        val record: UpdateDownloadRecord,
    )

    init {
        restoreInterruptedDownload()
        (appContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    val wasBackground = foregroundActivities == 0
                    foregroundActivities += 1
                    // Returning to Yfuse is the other moment 首页 is entered; the gate keeps
                    // this from turning into a request every time the user switches apps. The
                    // initial resume precedes the splash-gated overlay, so its check is owned by
                    // checkOnLaunch rather than racing it and consuming the prompt allowance.
                    if (shouldCheckForUpdateOnForeground(wasBackground, launchCheckStarted)) {
                        checkIfDue()
                    }
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

    /**
     * The first check of a new app process always reaches the manifest.
     *
     * Persisting the 30-minute gate across process death meant this sequence stayed silent:
     * check 0.2.27, publish 0.2.28, reopen Yfuse. The new process inherited the old timestamp
     * and skipped the only check the user expected opening the app to perform. Repeated tab and
     * foreground checks still use [checkIfDue]; only this once-per-process launch check bypasses
     * the interval, while [AutomaticUpdatePromptGate] continues to cap interruptions.
     */
    @Synchronized
    fun checkOnLaunch() {
        if (launchCheckStarted) return
        launchCheckStarted = true
        automaticCheckGate.tryAcquire(force = true)
        runCheck(automatic = true)
    }

    /** Explicit user checks always bypass both the check interval and the daily prompt limit. */
    fun check() = runCheck(automatic = false)

    private fun runCheck(automatic: Boolean) {
        if (checkJob?.isActive == true) return
        val checkSnapshot = snapshotUpdateCheck()
        val previous = checkSnapshot.previous
        val activeDownload = checkSnapshot.activeDownload
        checkJob = scope.launch {
            if (!beginUpdateCheckIfCurrent(checkSnapshot)) return@launch
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
                if (!isUpdateCheckSnapshotCurrent(checkSnapshot)) return@onSuccess
                if (!isPublishedUpdateAvailable(manifest.versionCode, BuildConfig.VERSION_CODE)) {
                    if (activeDownload == null) {
                        if (!publishCurrentIfCheckCurrent(checkSnapshot)) return@onSuccess
                    } else {
                        if (!invalidateActiveDownloadAndPublishCurrent(activeDownload)) {
                            return@onSuccess
                        }
                    }
                    AppLog.info(
                        category = "update",
                        event = "already_current",
                        message = "Application is already current",
                        attributes = mapOf(
                            "publishedVersionName" to manifest.versionName,
                            "publishedVersionCode" to manifest.versionCode.toString(),
                        ),
                    )
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
                if (activeDownload == null) {
                    val available = withContext(Dispatchers.IO) { availableState(manifest) }
                    if (!publishAvailableIfCheckCurrent(checkSnapshot, available)) {
                        return@onSuccess
                    }
                } else {
                    when (
                        activeDownloadManifestAction(
                            active = activeDownload.manifest,
                            published = manifest,
                            installedVersionCode = BuildConfig.VERSION_CODE,
                        )
                    ) {
                        ActiveDownloadManifestAction.Keep -> {
                            if (!refreshActiveDownloadManifest(activeDownload, manifest)) {
                                return@onSuccess
                            }
                        }
                        ActiveDownloadManifestAction.Replace -> {
                            if (!invalidateActiveDownload(
                                    activeDownload,
                                    nextState = UpdateState.Checking,
                                )
                            ) {
                                return@onSuccess
                            }
                            val replacementGeneration = requestGeneration
                            val available = withContext(Dispatchers.IO) {
                                availableState(manifest)
                            }
                            if (!publishAvailableIfGenerationCurrent(
                                    replacementGeneration,
                                    available,
                                )
                            ) {
                                return@onSuccess
                            }
                        }
                        ActiveDownloadManifestAction.Invalidate -> error(
                            "Current-version manifests are handled before available updates",
                        )
                    }
                }
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
                // An unreachable or invalid manifest says nothing about bytes already being
                // downloaded, so keep their live progress rather than replacing it with Error.
                if (activeDownload != null) {
                    if (isActiveDownloadCurrent(activeDownload) && !automatic) {
                        _promptVisible.value = true
                    }
                    // A resume/replacement may have advanced the generation while the manifest
                    // request was in flight. That newer operation owns the state even when this
                    // stale check fails, so never replace it with Error here.
                    return@onFailure
                }
                publishCheckFailureIfCurrent(checkSnapshot, previous)
            }
        }
    }

    @Synchronized
    private fun beginUpdateCheckIfCurrent(snapshot: UpdateCheckSnapshot): Boolean {
        if (!isUpdateCheckSnapshotCurrent(snapshot)) return false
        if (snapshot.activeDownload == null) {
            // A dialog left open from an earlier result must not stand in for this one's, or the
            // daily limit could be bypassed by a prompt that was never closed. An active transfer
            // is different: its progress remains visible while this cheap check runs.
            _promptVisible.value = false
            _state.value = UpdateState.Checking
        }
        return true
    }

    @Synchronized
    private fun snapshotUpdateCheck(): UpdateCheckSnapshot {
        val previous = _state.value
        val activeDownload = when (previous) {
            is UpdateState.Downloading -> ActiveDownloadCheck(requestGeneration, previous.manifest)
            is UpdateState.Paused -> ActiveDownloadCheck(requestGeneration, previous.manifest)
            else -> null
        }
        return UpdateCheckSnapshot(
            generation = requestGeneration,
            previous = previous,
            activeDownload = activeDownload,
            startedWithOwnedState = previous is UpdateState.Downloading ||
                previous is UpdateState.Paused || previous is UpdateState.Ready,
        )
    }

    @Synchronized
    private fun isUpdateCheckSnapshotCurrent(snapshot: UpdateCheckSnapshot): Boolean =
        updateCheckSnapshotStillCurrent(
            startGeneration = snapshot.generation,
            currentGeneration = requestGeneration,
            startedWithOwnedState = snapshot.startedWithOwnedState,
            currentState = _state.value,
        )

    @Synchronized
    private fun publishCurrentIfCheckCurrent(snapshot: UpdateCheckSnapshot): Boolean {
        if (!isUpdateCheckSnapshotCurrent(snapshot)) return false
        clearDownloadRecord()
        _state.value = UpdateState.Current
        return true
    }

    @Synchronized
    private fun publishAvailableIfCheckCurrent(
        snapshot: UpdateCheckSnapshot,
        available: UpdateState,
    ): Boolean {
        if (!isUpdateCheckSnapshotCurrent(snapshot)) return false
        _state.value = available
        return true
    }

    @Synchronized
    private fun publishAvailableIfGenerationCurrent(
        expectedGeneration: Int,
        available: UpdateState,
    ): Boolean {
        if (expectedGeneration != requestGeneration) return false
        _state.value = available
        return true
    }

    @Synchronized
    private fun publishCheckFailureIfCurrent(
        snapshot: UpdateCheckSnapshot,
        previous: UpdateState,
    ): Boolean {
        if (!isUpdateCheckSnapshotCurrent(snapshot)) return false
        _state.value = previous as? UpdateState.Ready
            ?: UpdateState.Error("暂时无法连接升级服务器")
        return true
    }

    @Synchronized
    private fun isActiveDownloadCurrent(active: ActiveDownloadCheck): Boolean {
        if (active.generation != requestGeneration) return false
        val currentManifest = when (val current = _state.value) {
            is UpdateState.Downloading -> current.manifest
            is UpdateState.Paused -> current.manifest
            is UpdateState.Ready -> current.manifest
            else -> return false
        }
        return currentManifest.hasSamePackageAs(active.manifest)
    }

    /** Refreshes notes/name without resetting live progress for the same package. */
    @Synchronized
    private fun refreshActiveDownloadManifest(
        active: ActiveDownloadCheck,
        published: UpdateManifest,
    ): Boolean {
        if (active.generation != requestGeneration) return false
        val current = _state.value
        val refreshed = when (current) {
            is UpdateState.Downloading -> current
                .takeIf { it.manifest.hasSamePackageAs(active.manifest) }
                ?.copy(manifest = published, totalBytes = published.size)
            is UpdateState.Paused -> current
                .takeIf { it.manifest.hasSamePackageAs(active.manifest) }
                ?.copy(manifest = published, totalBytes = published.size)
            is UpdateState.Ready -> current
                .takeIf { it.manifest.hasSamePackageAs(active.manifest) }
                ?.copy(manifest = published)
            else -> null
        } ?: return false
        if (current is UpdateState.Downloading || current is UpdateState.Paused) {
            val record = downloadRecord()?.takeIf {
                it.manifest.hasSamePackageAs(active.manifest)
            } ?: return false
            putDownloadRecord(record.copy(manifest = published))
        }
        _state.value = refreshed
        return true
    }

    /** Makes every in-flight callback from the replaced package fail its generation check. */
    @Synchronized
    private fun invalidateActiveDownload(
        active: ActiveDownloadCheck,
        nextState: UpdateState,
    ): Boolean {
        if (!isActiveDownloadCurrent(active)) return false
        pauseRequested = true
        requestGeneration += 1
        pendingInstall = null
        clearDownloadRecord()
        _state.value = nextState
        return true
    }

    @Synchronized
    private fun invalidateActiveDownloadAndPublishCurrent(active: ActiveDownloadCheck): Boolean {
        return invalidateActiveDownload(active, nextState = UpdateState.Current)
    }

    /** Starts or resumes the background download. Safe to call while one is already running. */
    @Synchronized
    fun download(manifest: UpdateManifest) {
        if (_state.value is UpdateState.Downloading) return
        pauseRequested = false
        requestGeneration += 1
        val matchingRecord = downloadRecord()?.takeIf {
            it.manifest.hasSamePackageAs(manifest)
        }
        val partial = partialFile(manifest)
        if (matchingRecord == null && partial.isFile && !partial.delete()) {
            // File names only contain versionCode. A republished package can therefore collide
            // with bytes from an older digest/URL and must never resume from that prefix.
            clearDownloadRecord()
            _state.value = UpdateState.Error("无法清理旧的下载缓存，请重试", manifest)
            return
        }
        val existing = partial.takeIf(File::isFile)?.length() ?: 0L
        putDownloadRecord(
            UpdateDownloadRecord(manifest, validator = matchingRecord?.validator),
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
    @Synchronized
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
        val request = snapshotActiveDownloadRequest() ?: return
        val generation = request.generation
        val manifest = request.manifest
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
            if (attempt >= UPDATE_DOWNLOAD_RETRY_LIMIT) {
                publishPausedIfCurrent(
                    manifest,
                    generation,
                    downloaded,
                    error.message ?: "下载失败",
                )
                break
            }
            if (!publishPausedIfCurrent(
                    manifest,
                    generation,
                    downloaded,
                    "网络中断，正在重试…",
                )
            ) {
                break
            }
            delay(UPDATE_DOWNLOAD_RETRY_BASE_DELAY_MS shl (attempt - 1))
            if (!publishDownloadingIfCurrent(manifest, generation, downloaded)) break
        }
    }

    @Synchronized
    private fun snapshotActiveDownloadRequest(): ActiveDownloadRequest? {
        val current = _state.value as? UpdateState.Downloading ?: return null
        val record = downloadRecord() ?: return null
        if (pauseRequested || !current.manifest.hasSamePackageAs(record.manifest)) return null
        return ActiveDownloadRequest(requestGeneration, record.manifest)
    }

    @Synchronized
    private fun ownedDownloadManifest(
        expected: UpdateManifest,
        generation: Int,
    ): UpdateManifest? {
        val record = downloadRecord()
        if (!updateDownloadOwnerStillCurrent(
                expectedGeneration = generation,
                currentGeneration = requestGeneration,
                pauseRequested = pauseRequested,
                expectedManifest = expected,
                currentRecord = record,
            )
        ) {
            return null
        }
        return record?.manifest
    }

    @Synchronized
    private fun publishDownloadingIfCurrent(
        expected: UpdateManifest,
        generation: Int,
        downloadedBytes: Long,
        requireDownloadingState: Boolean = false,
    ): Boolean {
        if (requireDownloadingState && _state.value !is UpdateState.Downloading) return false
        val manifest = ownedDownloadManifest(expected, generation) ?: return false
        _state.value = UpdateState.Downloading(manifest, downloadedBytes, manifest.size)
        return true
    }

    @Synchronized
    private fun publishPausedIfCurrent(
        expected: UpdateManifest,
        generation: Int,
        downloadedBytes: Long,
        message: String?,
    ): Boolean {
        val manifest = ownedDownloadManifest(expected, generation) ?: return false
        _state.value = UpdateState.Paused(manifest, downloadedBytes, manifest.size, message)
        return true
    }

    /** A user pause keeps ownership but sets pauseRequested, so update only its byte count. */
    @Synchronized
    private fun publishStoppedBytesIfOwned(
        expected: UpdateManifest,
        generation: Int,
        downloadedBytes: Long,
    ) {
        if (generation != requestGeneration) return
        val current = _state.value as? UpdateState.Paused ?: return
        val manifest = downloadRecord()?.manifest
            ?.takeIf { it.hasSamePackageAs(expected) && current.manifest.hasSamePackageAs(it) }
            ?: return
        _state.value = current.copy(
            manifest = manifest,
            downloadedBytes = downloadedBytes,
            totalBytes = manifest.size,
        )
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
        if (!cleanupStaleFilesIfCurrent(
                manifest,
                generation,
                directory,
                keepFileNames = setOf(target.name, partial.name),
            )
        ) {
            return@withContext false
        }

        if (cachedUpdatePackageMatches(target, manifest)) {
            return@withContext finishCachedTargetIfCurrent(
                manifest,
                target,
                partial,
                generation,
            )
        }

        val record = downloadRecord()?.takeIf { it.manifest.hasSamePackageAs(manifest) }
        var existing = partial.takeIf(File::isFile)?.length() ?: 0L
        var validator = record?.validator?.takeIf { existing > 0L }
        if (existing > 0L && record == null) {
            // Those bytes were fetched for some other package.
            when (deleteDownloadFileIfCurrent(manifest, generation, partial)) {
                OwnedUpdateCacheDeleteResult.StaleOwner -> return@withContext false
                OwnedUpdateCacheDeleteResult.Failed -> error("无法清理旧的下载缓存")
                OwnedUpdateCacheDeleteResult.Deleted,
                OwnedUpdateCacheDeleteResult.Missing,
                -> Unit
            }
            existing = 0L
        }
        if (existing >= manifest.size) {
            // Complete but never verified — most likely killed between the last write and the
            // digest check.
            if (!isCurrentRequest(generation)) return@withContext false
            if (cachedUpdatePackageMatches(partial, manifest)) {
                return@withContext promoteAndFinishIfCurrent(
                    manifest,
                    partial,
                    target,
                    generation,
                )
            }
            when (deleteDownloadFileIfCurrent(manifest, generation, partial)) {
                OwnedUpdateCacheDeleteResult.StaleOwner -> return@withContext false
                OwnedUpdateCacheDeleteResult.Failed -> error("无法清理损坏的下载缓存")
                OwnedUpdateCacheDeleteResult.Deleted,
                OwnedUpdateCacheDeleteResult.Missing,
                -> Unit
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
                    when (deleteDownloadFileIfCurrent(manifest, generation, partial)) {
                        OwnedUpdateCacheDeleteResult.StaleOwner -> return@withContext false
                        OwnedUpdateCacheDeleteResult.Failed -> error("无法重置下载缓存")
                        OwnedUpdateCacheDeleteResult.Deleted,
                        OwnedUpdateCacheDeleteResult.Missing,
                        -> Unit
                    }
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
                    when (deleteDownloadFileIfCurrent(manifest, generation, partial)) {
                        OwnedUpdateCacheDeleteResult.StaleOwner -> return@withContext false
                        OwnedUpdateCacheDeleteResult.Failed -> error("无法重置下载缓存")
                        OwnedUpdateCacheDeleteResult.Deleted,
                        OwnedUpdateCacheDeleteResult.Missing,
                        -> Unit
                    }
                    existing = 0L
                }
                break
            }
            val activeConnection = checkNotNull(connection) { "升级连接已关闭" }
            validateUpdateContentLength(activeConnection.contentLengthLong, manifest.size - existing)
            if (!putDownloadRecordIfCurrent(
                    generation,
                    UpdateDownloadRecord(manifest, validator = responseValidator),
                )
            ) {
                return@withContext false
            }
            val copied = activeConnection.inputStream.use { input ->
                appendUpdatePackage(
                    input = input,
                    partialFile = partial,
                    startBytes = existing,
                    expectedBytes = manifest.size,
                    shouldContinue = { isCurrentRequest(generation) },
                ) { downloaded ->
                    // A pause has already moved the state on; progress must not undo it.
                    publishDownloadingIfCurrent(
                        expected = manifest,
                        generation = generation,
                        downloadedBytes = downloaded,
                        requireDownloadingState = true,
                    )
                }
            }
            if (copied < manifest.size) {
                check(!isCurrentRequest(generation)) { "安装包下载不完整" }
                // The pause was recorded before the last chunks landed; show what the resume
                // will actually start from. A newer generation or invalidated record is ignored.
                publishStoppedBytesIfOwned(manifest, generation, copied)
                AppLog.info(
                    category = "update",
                    event = "download_interrupted",
                    message = "Update download stopped with a resumable partial file",
                    attributes = mapOf("downloadedBytes" to copied.toString()),
                )
                return@withContext false
            }
            if (!isCurrentRequest(generation)) return@withContext false
            if (!cachedUpdatePackageMatches(partial, manifest)) {
                when (deleteDownloadFileIfCurrent(manifest, generation, partial)) {
                    OwnedUpdateCacheDeleteResult.StaleOwner -> return@withContext false
                    OwnedUpdateCacheDeleteResult.Failed -> error("无法清理损坏的下载缓存")
                    OwnedUpdateCacheDeleteResult.Deleted,
                    OwnedUpdateCacheDeleteResult.Missing,
                    -> error("安装包校验失败")
                }
            }
            promoteAndFinishIfCurrent(manifest, partial, target, generation)
        } finally {
            connection?.disconnect()
        }
    }

    @Synchronized
    private fun isDownloadOwnerCurrent(manifest: UpdateManifest, generation: Int): Boolean =
        ownedDownloadManifest(manifest, generation) != null

    @Synchronized
    private fun cleanupStaleFilesIfCurrent(
        manifest: UpdateManifest,
        generation: Int,
        directory: File,
        keepFileNames: Set<String>,
    ): Boolean {
        if (!isDownloadOwnerCurrent(manifest, generation)) return false
        cleanupStaleUpdateFiles(directory, keepFileNames)
        return true
    }

    @Synchronized
    private fun deleteDownloadFileIfCurrent(
        manifest: UpdateManifest,
        generation: Int,
        file: File,
    ): OwnedUpdateCacheDeleteResult {
        if (!isCurrentRequest(generation)) return OwnedUpdateCacheDeleteResult.StaleOwner
        return deleteUpdateCacheFileIfOwned(
            file = file,
            expectedGeneration = generation,
            currentGeneration = requestGeneration,
            expectedManifest = manifest,
            currentRecord = downloadRecord(),
        )
    }

    @Synchronized
    private fun finishCachedTargetIfCurrent(
        manifest: UpdateManifest,
        target: File,
        partial: File,
        generation: Int,
    ): Boolean {
        if (!isDownloadOwnerCurrent(manifest, generation)) return false
        partial.delete()
        return finish(manifest, target, generation)
    }

    @Synchronized
    private fun promoteAndFinishIfCurrent(
        manifest: UpdateManifest,
        partial: File,
        target: File,
        generation: Int,
    ): Boolean {
        if (!isDownloadOwnerCurrent(manifest, generation)) return false
        promote(partial, target)
        return finish(manifest, target, generation)
    }

    private fun promote(partial: File, target: File) {
        check(!target.exists() || target.delete()) { "无法替换旧的安装包" }
        check(partial.renameTo(target)) { "无法保存安装包" }
    }

    @Synchronized
    private fun finish(manifest: UpdateManifest, apk: File, generation: Int): Boolean {
        if (!isDownloadOwnerCurrent(manifest, generation)) return false
        val finishedManifest = latestManifestForFinishedDownload(manifest, _state.value)
        clearDownloadRecord()
        AppLog.info(
            category = "update",
            event = "download_verified",
            message = "Application update downloaded and verified",
            attributes = mapOf("targetVersionName" to finishedManifest.versionName),
        )
        _state.value = UpdateState.Ready(finishedManifest, apk)
        // The installer is an activity. Launching one from the background is both blocked by
        // Android and rude, so a download finished behind the user's back waits for them.
        if (foregroundActivities > 0) {
            scope.launch { installIfCurrent(finishedManifest, apk, generation) }
        } else {
            pendingInstall = apk
            AppLog.info(
                category = "update",
                event = "install_deferred",
                message = "Install deferred until Yfuse returns to the foreground",
            )
        }
        return true
    }

    /** A newer manifest may invalidate the package after [finish] queues the main-thread launch. */
    private fun installIfCurrent(
        manifest: UpdateManifest,
        apk: File,
        generation: Int,
    ) {
        val ready = _state.value as? UpdateState.Ready ?: return
        if (!isCurrentRequest(generation) ||
            ready.apk != apk ||
            !ready.manifest.hasSamePackageAs(manifest)
        ) {
            return
        }
        install(apk)
    }

    fun install(apk: File) {
        pendingInstall = apk
        if (!appContext.packageManager.canRequestPackageInstalls()) {
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
        if (appContext.packageManager.canRequestPackageInstalls()) install(apk)
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
        if (target.isFile) {
            // Hashing an APK can take hundreds of milliseconds. Keep application startup on the
            // main thread responsive. Verification is deliberately read-only: any cleanup happens
            // only after ownership is checked again under the same lock used by [download].
            val owner = RestoreDownloadOwner(requestGeneration, record)
            scope.launch {
                if (!isRestoreOwnerCurrent(owner)) return@launch
                val verified = withContext(Dispatchers.IO) {
                    cachedUpdatePackageMatches(target, record.manifest)
                }
                if (verified) {
                    publishRestoredStateIfCurrent(
                        owner,
                        UpdateState.Ready(record.manifest, target),
                    )
                    return@launch
                }

                when (withContext(Dispatchers.IO) { deleteRestoreTargetIfCurrent(owner, target) }) {
                    OwnedUpdateCacheDeleteResult.StaleOwner -> return@launch
                    OwnedUpdateCacheDeleteResult.Failed -> AppLog.warning(
                        category = "update",
                        event = "cached_package_cleanup_failed",
                        message = "Rejected cached update package could not be removed",
                        attributes = mapOf(
                            "targetVersionCode" to record.manifest.versionCode.toString(),
                        ),
                    )
                    OwnedUpdateCacheDeleteResult.Deleted,
                    OwnedUpdateCacheDeleteResult.Missing,
                    -> Unit
                }

                val partialBytes = withContext(Dispatchers.IO) {
                    partialFile(record.manifest).takeIf(File::isFile)?.length() ?: 0L
                }
                val restored = if (partialBytes in 1 until record.manifest.size) {
                    UpdateState.Paused(record.manifest, partialBytes, record.manifest.size)
                } else {
                    UpdateState.Available(record.manifest)
                }
                publishRestoredStateIfCurrent(owner, restored)
            }
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

    @Synchronized
    private fun isRestoreOwnerCurrent(owner: RestoreDownloadOwner): Boolean =
        owner.generation == requestGeneration && isDownloadRecordCurrent(owner.record)

    @Synchronized
    private fun deleteRestoreTargetIfCurrent(
        owner: RestoreDownloadOwner,
        target: File,
    ): OwnedUpdateCacheDeleteResult = deleteUpdateCacheFileIfOwned(
        file = target,
        expectedGeneration = owner.generation,
        currentGeneration = requestGeneration,
        expectedManifest = owner.record.manifest,
        currentRecord = downloadRecord(),
    )

    @Synchronized
    private fun publishRestoredStateIfCurrent(
        owner: RestoreDownloadOwner,
        restored: UpdateState,
    ) {
        if (!isRestoreOwnerCurrent(owner)) return
        when (_state.value) {
            UpdateState.Idle,
            UpdateState.Checking,
            is UpdateState.Error,
            -> _state.value = restored
            else -> Unit
        }
    }

    private fun availableState(manifest: UpdateManifest): UpdateState {
        val directory = File(appContext.cacheDir, "updates")
        val target = File(directory, updatePackageFileName(manifest.versionCode))
        val hadCachedTarget = target.isFile
        if (cachedUpdatePackageMatches(target, manifest)) {
            return UpdateState.Ready(manifest, target)
        }
        if (hadCachedTarget) {
            AppLog.warning(
                category = "update",
                event = "cached_package_rejected",
                message = "Cached update package failed size or digest verification",
                attributes = mapOf("targetVersionCode" to manifest.versionCode.toString()),
            )
        }
        val record = downloadRecord()?.takeIf {
            it.manifest.hasSamePackageAs(manifest)
        }
        val partial = File(directory, "${target.name}.part")
        val existing = partial.takeIf { record != null && it.isFile }?.length() ?: 0L
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

    private fun isDownloadRecordCurrent(expected: UpdateDownloadRecord): Boolean =
        downloadRecord()?.manifest?.hasSamePackageAs(expected.manifest) == true

    private fun putDownloadRecord(record: UpdateDownloadRecord) {
        settings.putString(
            KEY_DOWNLOAD_RECORD,
            json.encodeToString(UpdateDownloadRecord.serializer(), record),
        )
    }

    @Synchronized
    private fun putDownloadRecordIfCurrent(
        generation: Int,
        record: UpdateDownloadRecord,
    ): Boolean {
        if (!isCurrentRequest(generation)) return false
        val merged = downloadRecord()?.let { mergeDownloadRecordValidator(it, record) }
            ?: return false
        putDownloadRecord(merged)
        return true
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
