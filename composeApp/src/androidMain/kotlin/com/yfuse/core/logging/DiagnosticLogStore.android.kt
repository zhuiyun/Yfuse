package com.yfuse.core.logging

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.yfuse.BuildConfig
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

internal data class DiagnosticLogStats(
    val entryCount: Int,
    val totalBytes: Long,
    val fileCount: Int,
)

internal object DiagnosticLogStore {
    private const val LogTag = "YfuseDiagnostics"
    private const val MaxFileBytes = 1024L * 1024L
    private const val MaxTotalBytes = 5L * 1024L * 1024L
    private const val MaxFiles = 8
    private const val DuplicateWindowMs = 5_000L
    private const val MaxMessageChars = 4_000
    private const val MaxAttributeChars = 1_000
    private const val MaxStackTraceChars = 16_000

    private val json = Json { encodeDefaults = false }
    private val lock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "yfuse-diagnostics").apply { isDaemon = true }
    }
    private val dayFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val lastWriteByFingerprint = mutableMapOf<String, Long>()
    private val writeFailureCount = AtomicInteger(0)
    private val lastWriteFailure = AtomicReference<String?>(null)

    @Volatile
    private var initialized = false
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
            attributes = mapOf(
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
        executor.execute {
            runCatching {
                writeBlocking(level, category, event, message, throwable, attributes)
            }.onFailure { error ->
                val failures = writeFailureCount.incrementAndGet()
                lastWriteFailure.set(error.javaClass.name)
                if (failures == 1 || failures % 25 == 0) {
                    Log.e(
                        LogTag,
                        "Failed to persist diagnostic log entry (count=$failures)",
                        error,
                    )
                }
            }
        }
    }

    fun stats(): DiagnosticLogStats {
        if (!initialized) return DiagnosticLogStats(0, 0L, 0)
        flushQueued()
        return synchronized(lock) {
            val files = logFilesLocked()
            DiagnosticLogStats(
                entryCount = files.sumOf { file ->
                    runCatching { file.useLines { lines -> lines.count() } }.getOrDefault(0)
                },
                totalBytes = files.sumOf(File::length),
                fileCount = files.size,
            )
        }
    }

    fun clear() {
        if (!initialized) return
        flushQueued()
        synchronized(lock) {
            logFilesLocked().forEach { it.delete() }
            lastWriteByFingerprint.clear()
        }
    }

    fun export(output: OutputStream) {
        check(initialized) { "诊断日志尚未初始化" }
        flushQueued()
        writeBlocking(
            level = DiagnosticLevel.Info,
            category = "diagnostics",
            event = "export_requested",
            message = "Diagnostic package export requested",
            throwable = null,
            attributes = emptyMap(),
        )
        synchronized(lock) {
            ZipOutputStream(output.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("device-info.txt"))
                zip.write(exportMetadataLocked().encodeToByteArray())
                zip.closeEntry()
                logFilesLocked().forEach { file ->
                    zip.putNextEntry(ZipEntry("logs/${file.name}"))
                    file.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeBlocking(
                    level = DiagnosticLevel.Critical,
                    category = "crash",
                    event = "uncaught_exception",
                    message = "Uncaught exception terminated the application",
                    throwable = throwable,
                    attributes = mapOf("thread" to thread.name),
                )
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun writeBlocking(
        level: DiagnosticLevel,
        category: String,
        event: String,
        message: String,
        throwable: Throwable?,
        attributes: Map<String, String>,
    ) {
        if (!initialized) return
        synchronized(lock) {
            val safeCategory = normalizedName(category, "general")
            val safeEvent = normalizedName(event, "unknown")
            val safeMessage = redactDiagnosticText(message).take(MaxMessageChars)
            val fingerprint = listOf(
                level.name,
                safeCategory,
                safeEvent,
                safeMessage,
                throwable?.javaClass?.name.orEmpty(),
            ).joinToString("|")
            val nowElapsed = SystemClock.elapsedRealtime()
            if (level != DiagnosticLevel.Error && level != DiagnosticLevel.Critical) {
                val previous = lastWriteByFingerprint[fingerprint]
                if (previous != null && nowElapsed - previous < DuplicateWindowMs) return
            }
            lastWriteByFingerprint[fingerprint] = nowElapsed
            if (lastWriteByFingerprint.size > 256) {
                val cutoff = nowElapsed - TimeUnit.MINUTES.toMillis(10)
                lastWriteByFingerprint.entries.removeAll { it.value < cutoff }
            }

            val safeAttributes = redactDiagnosticAttributes(attributes)
                .entries
                .take(32)
                .associate { (key, value) ->
                    normalizedName(key, "attribute") to value.take(MaxAttributeChars)
                }
                .toMutableMap()
                .apply { putIfAbsent("thread", Thread.currentThread().name.take(80)) }
            val safeThrowable = throwable?.let {
                DiagnosticException(
                    type = it.javaClass.name,
                    message = it.message?.let(::redactDiagnosticText)?.take(MaxMessageChars),
                    stackTrace = redactDiagnosticText(it.stackTraceToString())
                        .take(MaxStackTraceChars),
                )
            }
            val entry = DiagnosticEntry(
                timestamp = Instant.now().toString(),
                session = sessionId,
                level = level.name.uppercase(),
                category = safeCategory,
                event = safeEvent,
                message = safeMessage.ifBlank { null },
                attributes = safeAttributes,
                exception = safeThrowable,
            )
            val target = writableFileLocked()
            target.appendText(json.encodeToString(entry) + "\n", Charsets.UTF_8)
        }
    }

    private fun writableFileLocked(): File {
        val day = LocalDate.now().format(dayFormatter)
        val prefix = "diagnostic-$day-"
        val latest = logFilesLocked()
            .filter { it.name.startsWith(prefix) }
            .maxByOrNull(File::getName)
        if (latest != null && latest.length() < MaxFileBytes) return latest
        val nextIndex = latest
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
        directory.listFiles { file ->
            file.isFile && file.name.startsWith("diagnostic-") && file.extension == "jsonl"
        }
            ?.sortedBy(File::getName)
            .orEmpty()

    private fun exportMetadataLocked(): String {
        val stats = DiagnosticLogStats(
            entryCount = logFilesLocked().sumOf { file ->
                runCatching { file.useLines { lines -> lines.count() } }.getOrDefault(0)
            },
            totalBytes = logFilesLocked().sumOf(File::length),
            fileCount = logFilesLocked().size,
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
            appendLine("logWriteFailures=${writeFailureCount.get()}")
            lastWriteFailure.get()?.let { appendLine("lastLogWriteFailure=$it") }
            appendLine("format=JSON Lines; one JSON object per line")
            appendLine("retention=7 days, at most $MaxFiles files / ${MaxTotalBytes / 1024 / 1024} MiB")
            appendLine("privacy=Known tokens, API keys, passwords and URL credentials are redacted.")
        }
    }

    private fun flushQueued() {
        if (!initialized) return
        runCatching {
            executor.submit { }.get(3, TimeUnit.SECONDS)
        }
    }

    private fun normalizedName(value: String, fallback: String): String =
        value
            .trim()
            .lowercase()
            .replace(Regex("[^a-z0-9_.-]+"), "_")
            .trim('_')
            .take(64)
            .ifBlank { fallback }
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
    val priority = when (level) {
        DiagnosticLevel.Debug -> Log.DEBUG
        DiagnosticLevel.Info -> Log.INFO
        DiagnosticLevel.Warning -> Log.WARN
        DiagnosticLevel.Error -> Log.ERROR
        DiagnosticLevel.Critical -> Log.ERROR
    }
    val attrString = if (attributes.isEmpty()) "" else " " + attributes.entries.joinToString(", ") { "${it.key}=${it.value}" }
    Log.println(priority, category, "$event | $message$attrString")
    DiagnosticLogStore.record(level, category, event, message, throwable, attributes)
}
