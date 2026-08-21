package com.yfuse.core.logging

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.yfuse.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
private data class DiagnosticException(
    val type: String,
    val message: String? = null,
    val stackTrace: String,
)

@Serializable
private data class DiagnosticEntry(
    val timestamp: String,
    val session: String,
    val level: String,
    val category: String,
    val event: String,
    val message: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val exception: DiagnosticException? = null,
)

internal const val DiagnosticMaxMessageChars = 4_000
internal const val DiagnosticMaxAttributes = 32
internal const val DiagnosticMaxAttributeChars = 1_000
internal const val DiagnosticMaxStackTraceChars = 16_000
internal const val DiagnosticMaxThrowableTypeChars = 256
internal const val DiagnosticMaxThreadNameChars = 80
internal const val DiagnosticMaxFingerprints = 256

internal data class PreparedDiagnosticException(
    val type: String,
    val message: String?,
    val stackTrace: String,
)

/**
 * The complete payload retained by the asynchronous writer.
 *
 * Every string is redacted and capped, [attributes] is a defensive unmodifiable copy, and no
 * Throwable survives preparation. A full queue therefore has a deterministic memory ceiling and
 * cannot retain an exception's cause/suppressed-object graph.
 */
internal data class PreparedDiagnosticLog(
    val timestamp: String,
    val level: DiagnosticLevel,
    val category: String,
    val event: String,
    val message: String,
    val attributes: Map<String, String>,
    val exception: PreparedDiagnosticException?,
)

internal fun prepareDiagnosticLog(
    level: DiagnosticLevel,
    category: String,
    event: String,
    message: String,
    throwable: Throwable?,
    attributes: Map<String, String>,
    threadName: String,
): PreparedDiagnosticLog {
    val safeAttributes = LinkedHashMap<String, String>(DiagnosticMaxAttributes)
    // Reserve one slot for the actual producer thread. Limiting before transformation also avoids
    // copying an attacker-sized map merely to discard all but its first entries afterwards.
    attributes.entries
        .take(DiagnosticMaxAttributes - 1)
        .forEach { (key, value) ->
            val safeKey = normalizeDiagnosticName(key, "attribute")
            if (safeKey == "thread") return@forEach
            val safeValue =
                redactDiagnosticAttributes(mapOf(key to value))
                    .values
                    .first()
                    .take(DiagnosticMaxAttributeChars)
            safeAttributes[safeKey] = safeValue
        }
    safeAttributes["thread"] =
        redactDiagnosticText(threadName)
            .take(DiagnosticMaxThreadNameChars)

    val safeException =
        throwable?.let {
            PreparedDiagnosticException(
                type =
                    redactDiagnosticText(it.javaClass.name)
                        .take(DiagnosticMaxThrowableTypeChars),
                message =
                    it.message
                        ?.let(::redactDiagnosticText)
                        ?.take(DiagnosticMaxMessageChars),
                stackTrace =
                    redactDiagnosticText(it.stackTraceToString())
                        .take(DiagnosticMaxStackTraceChars),
            )
        }
    return PreparedDiagnosticLog(
        timestamp = Instant.now().toString(),
        level = level,
        category = normalizeDiagnosticName(category, "general"),
        event = normalizeDiagnosticName(event, "unknown"),
        message = redactDiagnosticText(message).take(DiagnosticMaxMessageChars),
        attributes = Collections.unmodifiableMap(safeAttributes),
        exception = safeException,
    )
}

/** Fixed-capacity insertion-ordered history used by duplicate suppression. */
internal class BoundedDiagnosticFingerprintHistory(
    private val maxEntries: Int = DiagnosticMaxFingerprints,
) {
    private val entries = LinkedHashMap<String, Long>(maxEntries)

    init {
        require(maxEntries > 0)
    }

    fun record(
        fingerprint: String,
        nowElapsedMs: Long,
        duplicateWindowMs: Long,
        suppressDuplicates: Boolean,
    ): Boolean {
        val previous = entries[fingerprint]
        if (
            suppressDuplicates &&
            previous != null &&
            nowElapsedMs >= previous &&
            nowElapsedMs - previous < duplicateWindowMs
        ) {
            return true
        }

        // Updating an existing fingerprint makes it newest. A new fingerprint evicts before
        // insertion, so the collection never transiently exceeds its hard capacity.
        entries.remove(fingerprint)
        if (entries.size >= maxEntries) {
            val oldest = entries.entries.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        entries[fingerprint] = nowElapsedMs
        return false
    }

    fun clear() = entries.clear()

    internal val size: Int get() = entries.size

    internal fun contains(fingerprint: String): Boolean = fingerprint in entries
}

internal fun normalizeDiagnosticName(
    value: String,
    fallback: String,
): String =
    value
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_.-]+"), "_")
        .trim('_')
        .take(64)
        .ifBlank { fallback }

internal data class DiagnosticLogStats(
    val entryCount: Int,
    val totalBytes: Long,
    val fileCount: Int,
    val droppedEntryCount: Long,
)

internal object DiagnosticLogStore {
    private const val LogTag = "YfuseDiagnostics"
    private const val MaxFileBytes = 1024L * 1024L
    private const val MaxTotalBytes = 5L * 1024L * 1024L
    private const val MaxFiles = 8
    private const val DuplicateWindowMs = 5_000L

    /** Hard cap on already-bounded payloads awaiting disk persistence. */
    private const val MaxQueuedWrites = 64

    private val json = Json { encodeDefaults = false }
    private val lock = Any()
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MaxQueuedWrites),
            { task -> Thread(task, "yfuse-diagnostics").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val dayFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val fingerprints = BoundedDiagnosticFingerprintHistory()
    private val writeFailureCount = AtomicInteger(0)
    private val lastWriteFailure = AtomicReference<String?>(null)
    private val droppedEntryCount = AtomicLong(0L)

    @Volatile
    private var initialized = false

    @Volatile
    private var crashHandlerInstalled = false
    private lateinit var appContext: Context
    private lateinit var directory: File
    private var sessionId = ""

    fun initialize(context: Context) {
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            directory = File(appContext.noBackupFilesDir, "diagnostics").apply { mkdirs() }
            sessionId = UUID.randomUUID().toString().take(8)
            pruneLocked()
            initialized = true
        }
        installCrashHandler()
        record(
            level = DiagnosticLevel.Info,
            category = "app",
            event = "session_started",
            message = "Application process started",
            attributes =
                mapOf(
                    "versionName" to BuildConfig.VERSION_NAME,
                    "versionCode" to BuildConfig.VERSION_CODE.toString(),
                    "androidApi" to Build.VERSION.SDK_INT.toString(),
                ),
        )
    }

    fun record(
        level: DiagnosticLevel,
        category: String,
        event: String,
        message: String,
        throwable: Throwable? = null,
        attributes: Map<String, String> = emptyMap(),
    ) {
        if (!initialized) return
        val prepared =
            runCatching {
                prepareDiagnosticLog(
                    level = level,
                    category = category,
                    event = event,
                    message = message,
                    throwable = throwable,
                    attributes = attributes,
                    threadName = Thread.currentThread().name,
                )
            }.getOrElse { error ->
                recordWriteFailure(error)
                return
            }
        try {
            executor.execute {
                runCatching { writeBlocking(prepared) }
                    .onFailure(::recordWriteFailure)
            }
        } catch (_: RejectedExecutionException) {
            recordDroppedEntry()
        }
    }

    fun stats(): DiagnosticLogStats {
        if (!initialized) return DiagnosticLogStats(0, 0L, 0, droppedEntryCount.get())
        flushQueued()
        return synchronized(lock) {
            val files = logFilesLocked()
            DiagnosticLogStats(
                entryCount =
                    files.sumOf { file ->
                        runCatching { file.useLines { lines -> lines.count() } }.getOrDefault(0)
                    },
                totalBytes = files.sumOf(File::length),
                fileCount = files.size,
                droppedEntryCount = droppedEntryCount.get(),
            )
        }
    }

    fun clear() {
        if (!initialized) return
        flushQueued()
        synchronized(lock) {
            logFilesLocked().forEach { it.delete() }
            fingerprints.clear()
        }
    }

    fun export(output: OutputStream) {
        check(initialized) { "诊断日志尚未初始化" }
        flushQueued()
        writeBlocking(
            prepareDiagnosticLog(
                level = DiagnosticLevel.Info,
                category = "diagnostics",
                event = "export_requested",
                message = "Diagnostic package export requested",
                throwable = null,
                attributes = emptyMap(),
                threadName = Thread.currentThread().name,
            ),
        )
        synchronized(lock) {
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("device-info.txt"))
                zip.write(exportMetadataLocked().encodeToByteArray())
                zip.closeEntry()
                logFilesLocked().forEach { file ->
                    zip.putNextEntry(ZipEntry("logs/${file.name}"))
                    // Re-sanitize at the export boundary as well as at write time. This protects
                    // packages that still contain lines written by an older app version whose
                    // redactor did not hide server hosts or user identifiers.
                    file.useLines { lines ->
                        lines.forEach { line ->
                            zip.write(redactDiagnosticText(line).encodeToByteArray())
                            zip.write('\n'.code)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Chains onto whatever handler is already installed, at most once.
     *
     * Without the guard a second [initialize] installs a second handler whose `previous` is
     * the first one, so a single crash is written twice — which is how the 07-30 logs came to
     * hold two identical CRITICAL entries 0.9 ms apart, reading as two crashes.
     */
    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeBlocking(
                    prepareDiagnosticLog(
                        level = DiagnosticLevel.Critical,
                        category = "crash",
                        event = "uncaught_exception",
                        message = "Uncaught exception terminated the application",
                        throwable = throwable,
                        attributes = emptyMap(),
                        threadName = thread.name,
                    ),
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun writeBlocking(prepared: PreparedDiagnosticLog) {
        if (!initialized) return
        synchronized(lock) {
            val fingerprint =
                listOf(
                    prepared.level.name,
                    prepared.category,
                    prepared.event,
                    prepared.message,
                    prepared.exception?.type.orEmpty(),
                ).joinToString("|")
            val suppressDuplicates =
                prepared.level != DiagnosticLevel.Error &&
                    prepared.level != DiagnosticLevel.Critical
            if (
                fingerprints.record(
                    fingerprint = fingerprint,
                    nowElapsedMs = SystemClock.elapsedRealtime(),
                    duplicateWindowMs = DuplicateWindowMs,
                    suppressDuplicates = suppressDuplicates,
                )
            ) {
                return
            }
            val safeThrowable =
                prepared.exception?.let {
                    DiagnosticException(
                        type = it.type,
                        message = it.message,
                        stackTrace = it.stackTrace,
                    )
                }
            val entry =
                DiagnosticEntry(
                    timestamp = prepared.timestamp,
                    session = sessionId,
                    level = prepared.level.name.uppercase(),
                    category = prepared.category,
                    event = prepared.event,
                    message = prepared.message.ifBlank { null },
                    attributes = prepared.attributes,
                    exception = safeThrowable,
                )
            val target = writableFileLocked()
            target.appendText(json.encodeToString(entry) + "\n", Charsets.UTF_8)
        }
    }

    private fun writableFileLocked(): File {
        val day = LocalDate.now().format(dayFormatter)
        val prefix = "diagnostic-$day-"
        val latest =
            logFilesLocked()
                .filter { it.name.startsWith(prefix) }
                .maxByOrNull(File::getName)
        if (latest != null && latest.length() < MaxFileBytes) return latest
        val nextIndex =
            latest
                ?.nameWithoutExtension
                ?.substringAfterLast('-')
                ?.toIntOrNull()
                ?.plus(1)
                ?: 1
        val file = File(directory, "$prefix${nextIndex.toString().padStart(3, '0')}.jsonl")
        pruneLocked()
        return file
    }

    private fun pruneLocked() {
        val files = logFilesLocked().sortedBy(File::lastModified).toMutableList()
        val oldestAllowed = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        files.filter { it.lastModified() < oldestAllowed }.forEach {
            it.delete()
            files.remove(it)
        }
        var total = files.sumOf(File::length)
        while (files.size >= MaxFiles || total >= MaxTotalBytes) {
            val oldest = files.removeFirstOrNull() ?: break
            total -= oldest.length()
            oldest.delete()
        }
    }

    private fun logFilesLocked(): List<File> =
        directory
            .listFiles { file ->
                file.isFile && file.name.startsWith("diagnostic-") && file.extension == "jsonl"
            }?.sortedBy(File::getName)
            .orEmpty()

    private fun exportMetadataLocked(): String {
        val stats =
            DiagnosticLogStats(
                entryCount =
                    logFilesLocked().sumOf { file ->
                        runCatching { file.useLines { lines -> lines.count() } }.getOrDefault(0)
                    },
                totalBytes = logFilesLocked().sumOf(File::length),
                fileCount = logFilesLocked().size,
                droppedEntryCount = droppedEntryCount.get(),
            )
        return buildString {
            appendLine("Yfuse diagnostic package")
            appendLine("exportedAt=${Instant.now()}")
            appendLine("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("locale=${Locale.getDefault().toLanguageTag()}")
            appendLine("timeZone=${ZoneId.systemDefault().id}")
            appendLine("logFiles=${stats.fileCount}")
            appendLine("logEntries=${stats.entryCount}")
            appendLine("logBytes=${stats.totalBytes}")
            appendLine("logDroppedEntries=${stats.droppedEntryCount}")
            appendLine("logWriteFailures=${writeFailureCount.get()}")
            lastWriteFailure.get()?.let { appendLine("lastLogWriteFailure=$it") }
            appendLine("format=JSON Lines; one JSON object per line")
            appendLine("retention=7 days, at most $MaxFiles files / ${MaxTotalBytes / 1024 / 1024} MiB")
            appendLine("privacy=Tokens, credentials, server hosts and user identifiers are redacted.")
        }
    }

    private fun flushQueued() {
        if (!initialized) return
        runCatching {
            executor.submit { }.get(3, TimeUnit.SECONDS)
        }
    }

    private fun recordWriteFailure(error: Throwable) {
        val failures = writeFailureCount.incrementAndGet()
        lastWriteFailure.set(error.javaClass.name)
        if (failures == 1 || failures % 25 == 0) {
            safeLogcat(
                priority = Log.ERROR,
                tag = LogTag,
                message = "Failed to persist diagnostic log entry (count=$failures)",
                throwable = error,
            )
        }
    }

    private fun recordDroppedEntry() {
        val dropped = droppedEntryCount.incrementAndGet()
        if (dropped == 1L || dropped % 100L == 0L) {
            safeLogcat(
                priority = Log.WARN,
                tag = LogTag,
                message = "Diagnostic log queue full; dropped entry (count=$dropped)",
            )
        }
    }
}

internal actual fun writeDiagnosticLog(
    level: DiagnosticLevel,
    category: String,
    event: String,
    message: String,
    throwable: Throwable?,
    attributes: Map<String, String>,
) {
    // Mirror to logcat so developers can tail diagnostics via `adb logcat` without having
    // to export the in-app diagnostic bundle. The on-disk store remains the source of truth
    // for exportable logs; this is just a convenience for live debugging.
    val priority =
        when (level) {
            DiagnosticLevel.Debug -> Log.DEBUG
            DiagnosticLevel.Info -> Log.INFO
            DiagnosticLevel.Warning -> Log.WARN
            DiagnosticLevel.Error -> Log.ERROR
            DiagnosticLevel.Critical -> Log.ERROR
        }
    safeLogcat(
        priority = priority,
        tag = category,
        event = event,
        message = message,
        throwable = throwable,
        attributes = attributes,
    )
    DiagnosticLogStore.record(level, category, event, message, throwable, attributes)
}
