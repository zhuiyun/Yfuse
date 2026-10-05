package com.yfuse.core.playback

/** Native ownership used by crash accounting. Keep these separate even when libraries share FFmpeg. */
enum class NativePlaybackComponent {
    Unknown,
    Mpv,
    Mdk,
    YCoreDemux,
    YCoreGpu,
}

/** Classifies a bounded tombstone in descending order of specificity. */
fun classifyNativePlaybackCrash(tombstone: String): NativePlaybackComponent {
    val text = tombstone.lowercase()
    return when {
        "libycore_gpu" in text || "ycore vulkan" in text ->
            NativePlaybackComponent.YCoreGpu
        "libycore_demux" in text || "libycore-demux" in text || "ycore demux" in text ->
            NativePlaybackComponent.YCoreDemux
        "libyfuse-mdk-jni" in text || "libmdk" in text || "mdkplayer" in text ->
            NativePlaybackComponent.Mdk
        "libmpv" in text || "mpv_render" in text || "mpv_render_context" in text ->
            NativePlaybackComponent.Mpv
        else -> NativePlaybackComponent.Unknown
    }
}

/**
 * Defense-in-depth redaction for any small tombstone excerpt shown to a developer. Production
 * persistence stores only the classification, never this text.
 */
fun redactNativeCrashText(text: String): String =
    text
        .replace(URL_WITH_AUTH_OR_QUERY, "[redacted-url]")
        .replace(CREDENTIAL_ASSIGNMENT, "$1=[redacted]")
        .replace(ANDROID_MEDIA_PATH, "[redacted-media-path]")

private val URL_WITH_AUTH_OR_QUERY =
    Regex("(?i)\\b(?:https?|rtsp|smb|ftp)://[^\\s]+")
private val CREDENTIAL_ASSIGNMENT =
    Regex("(?i)\\b(api[_-]?key|access[_-]?token|token|authorization|password|passwd)=([^\\s&]+)")
private val ANDROID_MEDIA_PATH =
    Regex("(?i)(?:/storage/|/sdcard/|content://)[^\\s]+")

/** One backtrace frame of the crashing thread. [library] is a file name, never a full path. */
data class NativeCrashFrame(
    val library: String,
    val relativePc: Long,
    val function: String? = null,
    val functionOffset: Long = 0L,
    val buildId: String? = null,
)

/** The crashing thread of a native tombstone, reduced to what attributes and explains the crash. */
data class NativeCrashReport(
    val signal: String,
    val faultAddress: Long? = null,
    val abortMessage: String? = null,
    val threadName: String,
    val frames: List<NativeCrashFrame>,
)

/** How a crash was tied to a native owner. */
enum class NativeCrashAttribution {
    /** A frame of the crashing thread lies in the owner's own library. */
    Backtrace,

    /** The crashing thread is one the owner names. */
    ThreadName,

    /** Nothing in the crashing thread names an owner; the crash happened during its session. */
    ActiveSession,

    Unknown,
}

/**
 * Reads the tombstone Android keeps for a native crash: protobuf from Android 12 (what
 * ApplicationExitInfo returns there), text before it. Only the crashing thread is kept. Matching
 * library names anywhere in the whole tombstone blamed whichever owner had any idle thread in
 * it, which is how a crash in another component could be booked against the YCore demuxer.
 */
fun parseNativeCrashTombstone(bytes: ByteArray): NativeCrashReport? =
    runCatching { parseTextTombstone(bytes) ?: parseProtoTombstone(bytes) }.getOrNull()

/** The owner named by the crashing thread: its first frame in an owner's library, then its name. */
fun NativeCrashReport.attributedComponent(): Pair<NativePlaybackComponent, NativeCrashAttribution> {
    // Walking up from #00 passes through shared FFmpeg and system frames to the owner that called them.
    frames.firstNotNullOfOrNull { componentForLibrary(it.library) }?.let {
        return it to NativeCrashAttribution.Backtrace
    }
    componentForThread(threadName)?.let { return it to NativeCrashAttribution.ThreadName }
    return NativePlaybackComponent.Unknown to NativeCrashAttribution.Unknown
}

/**
 * A bounded, redacted text for diagnostics: signal, fault address, thread and the top frames as
 * library, offset, symbol and build id, which is enough to symbolize against the build's
 * unstripped libraries. Paths are reduced to file names; abort text is redacted.
 */
fun NativeCrashReport.redactedSummary(maximumFrames: Int = MAX_SUMMARY_FRAMES): String =
    buildString {
        append("signal=").append(signal.take(48))
        faultAddress?.let { append(" fault=0x").append(it.toULong().toString(16)) }
        append(" thread=").append(threadName.take(32))
        abortMessage?.takeIf(String::isNotBlank)?.let {
            append(" abort=").append(redactNativeCrashText(it).replace('\n', ' ').take(160))
        }
        frames.take(maximumFrames).forEachIndexed { index, frame ->
            append("\n#").append(index.toString().padStart(2, '0'))
            append(' ').append(frame.library.take(64))
            append("+0x").append(frame.relativePc.toULong().toString(16))
            frame.function?.takeIf(String::isNotBlank)?.let {
                append(" (")
                    .append(it.take(96))
                    .append("+")
                    .append(frame.functionOffset)
                    .append(')')
            }
            frame.buildId?.takeIf(String::isNotBlank)?.let { append(" BuildId=").append(it.take(40)) }
        }
    }

private const val MAX_SUMMARY_FRAMES = 16
private const val MAX_PARSED_FRAMES = 64

private fun componentForLibrary(library: String): NativePlaybackComponent? =
    when (library.lowercase()) {
        "libycore_gpu.so" -> NativePlaybackComponent.YCoreGpu
        "libycore_demux.so" -> NativePlaybackComponent.YCoreDemux
        "libmdk.so", "libyfuse-mdk-jni.so" -> NativePlaybackComponent.Mdk
        "libmpv.so", "libplayer.so" -> NativePlaybackComponent.Mpv
        else -> null
    }

// Linux keeps 15 characters of a thread name, so only prefixes are reliable.
private fun componentForThread(name: String): NativePlaybackComponent? =
    when {
        name.startsWith("YCore-Vulkan") -> NativePlaybackComponent.YCoreGpu
        name.startsWith("YCore") -> NativePlaybackComponent.YCoreDemux
        name.startsWith("mpv/") || name == "mpv" -> NativePlaybackComponent.Mpv
        else -> null
    }

private fun String.libraryName(): String = substringAfterLast('/').substringBefore(' ').trim()

private val TEXT_THREAD_HEADER = Regex("""pid: \d+, tid: (\d+), name: (.*?)\s+>>>""")
private val TEXT_SIGNAL = Regex("""signal \d+ \((\w+)\), code -?\d+ \((\w+)\)(?:, fault addr (0x[0-9a-fA-F]+))?""")
private val TEXT_ABORT = Regex("""Abort message: '(.*)'""")
private val TEXT_FRAME = Regex("""^\s*#\d+ pc ([0-9a-fA-F]+)\s+(\S+)(.*)$""")
private val TEXT_FUNCTION = Regex("""\(([^()]+)\+(\d+)\)""")
private val TEXT_BUILD_ID = Regex("""\(BuildId: ([0-9a-fA-F]+)\)""")

private fun parseTextTombstone(bytes: ByteArray): NativeCrashReport? {
    val text = bytes.decodeToString()
    val start = text.trimStart()
    if (!start.startsWith("*** ***") && !start.startsWith("Build fingerprint")) return null
    val header = TEXT_THREAD_HEADER.find(text) ?: return null
    val signal = TEXT_SIGNAL.find(text, header.range.last)
    val lines = text.substring(header.range.last).lineSequence()
    val frames = mutableListOf<NativeCrashFrame>()
    var inBacktrace = false
    for (line in lines) {
        if (!inBacktrace) {
            if (line.trim() == "backtrace:") inBacktrace = true
            if (line.startsWith("--- --- ---")) break
            continue
        }
        val match = TEXT_FRAME.find(line) ?: break
        val rest = match.groupValues[3]
        val function = TEXT_FUNCTION.find(rest)
        frames +=
            NativeCrashFrame(
                library = match.groupValues[2].libraryName(),
                relativePc = match.groupValues[1].toULongOrNull(16)?.toLong() ?: 0L,
                function = function?.groupValues?.get(1),
                functionOffset = function?.groupValues?.get(2)?.toLongOrNull() ?: 0L,
                buildId = TEXT_BUILD_ID.find(rest)?.groupValues?.get(1),
            )
        if (frames.size >= MAX_PARSED_FRAMES) break
    }
    return NativeCrashReport(
        signal = signal?.let { "${it.groupValues[1]}/${it.groupValues[2]}" } ?: "unknown",
        faultAddress =
            signal
                ?.groupValues
                ?.get(3)
                ?.removePrefix("0x")
                ?.toULongOrNull(16)
                ?.toLong(),
        abortMessage = TEXT_ABORT.find(text)?.groupValues?.get(1),
        threadName = header.groupValues[2].trim(),
        frames = frames,
    )
}

// tombstone.proto: Tombstone{tid=6, signal_info=10, abort_message=14, threads=16 (map<uint32, Thread>)},
// Signal{name=2, code_name=4, has_fault_address=8, fault_address=9}, Thread{id=1, name=2,
// current_backtrace=4}, BacktraceFrame{rel_pc=1, function_name=4, function_offset=5, file_name=6,
// build_id=8}.
private fun parseProtoTombstone(bytes: ByteArray): NativeCrashReport? {
    var crashingTid = -1L
    var signal = "unknown"
    var faultAddress: Long? = null
    var abortMessage: String? = null
    val threads = mutableMapOf<Long, ProtoField>()
    val root = ProtoReader(bytes, 0, bytes.size)
    while (root.hasMore()) {
        val field = root.next() ?: return null
        when (field.number) {
            6 -> crashingTid = field.varint
            10 -> {
                var name = ""
                var codeName = ""
                var hasFault = false
                var fault = 0L
                field.message(bytes).forEachField { inner ->
                    when (inner.number) {
                        2 -> name = inner.text(bytes)
                        4 -> codeName = inner.text(bytes)
                        8 -> hasFault = inner.varint != 0L
                        9 -> fault = inner.varint
                    }
                }
                signal = listOf(name, codeName).filter(String::isNotEmpty).joinToString("/").ifEmpty { "unknown" }
                faultAddress = fault.takeIf { hasFault }
            }
            14 -> abortMessage = field.text(bytes)
            16 -> {
                var key = -1L
                var value: ProtoField? = null
                field.message(bytes).forEachField { entry ->
                    when (entry.number) {
                        1 -> key = entry.varint
                        2 -> value = entry
                    }
                }
                value?.let { threads[key] = it }
            }
        }
    }
    val thread = threads[crashingTid] ?: return null
    var threadName = ""
    val frames = mutableListOf<NativeCrashFrame>()
    thread.message(bytes).forEachField { field ->
        when (field.number) {
            2 -> threadName = field.text(bytes)
            4 ->
                if (frames.size < MAX_PARSED_FRAMES) {
                    var relativePc = 0L
                    var function: String? = null
                    var functionOffset = 0L
                    var file = ""
                    var buildId: String? = null
                    field.message(bytes).forEachField { frame ->
                        when (frame.number) {
                            1 -> relativePc = frame.varint
                            4 -> function = frame.text(bytes)
                            5 -> functionOffset = frame.varint
                            6 -> file = frame.text(bytes)
                            8 -> buildId = frame.text(bytes)
                        }
                    }
                    frames += NativeCrashFrame(file.libraryName(), relativePc, function, functionOffset, buildId)
                }
        }
    }
    return NativeCrashReport(signal, faultAddress, abortMessage, threadName, frames)
}

/** One decoded protobuf field: a varint value, or the bounds of a length-delimited payload. */
private class ProtoField(
    val number: Int,
    val varint: Long,
    val start: Int,
    val end: Int,
) {
    fun message(bytes: ByteArray) = ProtoReader(bytes, start, end)

    fun text(bytes: ByteArray): String = bytes.decodeToString(start, end)
}

private class ProtoReader(
    private val bytes: ByteArray,
    private var position: Int,
    private val end: Int,
) {
    fun hasMore(): Boolean = position < end

    inline fun forEachField(action: (ProtoField) -> Unit) {
        while (hasMore()) action(next() ?: return)
    }

    /** The next field, or null when the payload is not well-formed protobuf. */
    fun next(): ProtoField? {
        val key = readVarint() ?: return null
        val number = (key ushr 3).toInt()
        if (number <= 0) return null
        return when ((key and 0x7L).toInt()) {
            0 -> ProtoField(number, readVarint() ?: return null, position, position)
            1 -> skip(8)?.let { ProtoField(number, 0L, it, position) }
            2 -> {
                val length = readVarint() ?: return null
                if (length < 0 || length > end - position) return null
                val start = position
                position += length.toInt()
                ProtoField(number, 0L, start, position)
            }
            5 -> skip(4)?.let { ProtoField(number, 0L, it, position) }
            else -> null
        }
    }

    private fun skip(count: Int): Int? {
        if (end - position < count) return null
        val start = position
        position += count
        return start
    }

    private fun readVarint(): Long? {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (position >= end) return null
            val byte = bytes[position++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        return null
    }
}
