package com.yfuse.core.filesource

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Sidecar subtitles copied off a share, in the app's cache where the player can read them with
 * no credentials.
 *
 * Kept small and self-cleaning: files unused for [MAX_AGE_MS] go, and past [MAX_TOTAL_BYTES] the
 * least recently used go first. A read touches the file, so a series being watched keeps its
 * subtitles while one finished last month does not.
 */
internal class FileSourceSubtitleCache(
    private val directory: () -> File?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun cached(
        key: String,
        extension: String,
    ): String? {
        val file = fileFor(key, extension) ?: return null
        if (!file.isFile || file.length() == 0L) return null
        file.setLastModified(nowMs())
        return file.localUri()
    }

    fun store(
        key: String,
        extension: String,
        bytes: ByteArray,
    ): String {
        val target = fileFor(key, extension) ?: throw IOException("Subtitle cache is unavailable")
        target.parentFile?.mkdirs()
        // Written aside and renamed, so a player never opens a half-written file.
        val partial = File(target.parentFile, target.name + PARTIAL_SUFFIX)
        partial.writeBytes(bytes)
        if (!partial.renameTo(target)) {
            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                throw IOException("Subtitle could not be stored")
            }
        }
        target.setLastModified(nowMs())
        prune()
        return target.localUri()
    }

    private fun fileFor(
        key: String,
        extension: String,
    ): File? {
        require(key.matches(SAFE_NAME) && extension.matches(SAFE_EXTENSION)) { "Unsafe subtitle cache name" }
        val root = directory() ?: return null
        return File(File(root, DIRECTORY_NAME), "$key.$extension")
    }

    private fun prune() {
        val folder = directory()?.let { File(it, DIRECTORY_NAME) } ?: return
        val files = folder.listFiles()?.filter(File::isFile).orEmpty()
        val now = nowMs()
        // A leftover partial file is from a write that died half way; it is never valid.
        val (stale, fresh) = files.partition { now - it.lastModified() > MAX_AGE_MS || it.isPartial() }
        stale.forEach { it.delete() }
        var total = 0L
        fresh.sortedByDescending(File::lastModified).forEach { file ->
            total += file.length()
            if (total > MAX_TOTAL_BYTES) file.delete()
        }
    }

    private fun File.localUri(): String = "file://$absolutePath"

    private fun File.isPartial(): Boolean = name.endsWith(PARTIAL_SUFFIX)

    private companion object {
        const val DIRECTORY_NAME = "filesource-subtitles"
        const val PARTIAL_SUFFIX = ".partial"
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        val SAFE_NAME = Regex("[0-9a-f]{16}")
        val SAFE_EXTENSION = Regex("[a-z0-9]{1,5}")
    }
}

/**
 * Re-encodes a subtitle as UTF-8, the one text encoding the player's loaders assume.
 *
 * Chinese subtitles are still very often GBK, and traditional ones Big5; read as UTF-8 either is
 * a screen of replacement characters. A BOM — UTF-8 or UTF-16 — is trusted as is, valid UTF-8 is
 * left alone, and anything else is decoded as Big5 when the name or the bytes say traditional,
 * as GB18030 (a superset of GBK and GB2312) otherwise.
 */
internal fun utf8SubtitleBytes(
    data: ByteArray,
    fileName: String,
): ByteArray {
    if (data.startsWithBytes(UTF8_BOM) || data.startsWithBytes(UTF16LE_BOM) || data.startsWithBytes(UTF16BE_BOM)) {
        return data
    }
    if (data.decodesStrictly(Charsets.UTF_8)) return data
    val gb18030 = Charset.forName("GB18030")
    val big5 = Charset.forName("Big5")
    val traditional = subtitleTags(fileBaseName(fileName)).language in TRADITIONAL_LABELS || data.looksLikeBig5()
    val order = if (traditional) listOf(big5, gb18030) else listOf(gb18030, big5)
    val charset = order.firstOrNull { data.decodesStrictly(it) } ?: order.first()
    return String(data, charset).encodeToByteArray()
}

private fun ByteArray.decodesStrictly(charset: Charset): Boolean =
    try {
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
        true
    } catch (_: CharacterCodingException) {
        false
    }

/**
 * Big5 and GBK share their lead bytes, so either decodes the other without an error. What tells
 * them apart is the trail byte: Big5 uses 0x40–0x7E for a large share of its characters, while
 * GB2312 — the simplified text GBK subtitles are almost always written in — never does.
 */
private fun ByteArray.looksLikeBig5(): Boolean {
    var pairs = 0
    var lowTrails = 0
    var index = 0
    while (index < size - 1) {
        val lead = this[index].toInt() and 0xFF
        if (lead >= 0x81) {
            val trail = this[index + 1].toInt() and 0xFF
            pairs++
            if (trail in 0x40..0x7E) lowTrails++
            index += 2
        } else {
            index++
        }
    }
    return pairs >= MIN_PAIRS_FOR_GUESS && lowTrails * 100 >= pairs * BIG5_LOW_TRAIL_PERCENT
}

private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
private val TRADITIONAL_LABELS = setOf("繁体中文", "繁英双语")
private const val MIN_PAIRS_FOR_GUESS = 8
private const val BIG5_LOW_TRAIL_PERCENT = 15
