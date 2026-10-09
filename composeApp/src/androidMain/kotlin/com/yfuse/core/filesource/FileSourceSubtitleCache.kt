package com.yfuse.core.filesource

import com.yfuse.core2.android.decodeSubtitleFile
import com.yfuse.core2.subtitle.YSubtitleCharset
import com.yfuse.core2.subtitle.detectSubtitleCharset
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

    private fun File.localUri(): String = toPath().toUri().toASCIIString()

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
 * Chinese subtitles are still very often GBK, traditional ones Big5, Japanese ones Shift_JIS; read
 * as UTF-8 any of them is a screen of replacement characters. Valid UTF-8 and text behind a UTF-16
 * BOM are left alone, since every loader reads them; anything else is decoded in the encoding
 * [detectSubtitleCharset] reads off the bytes, with a 繁 or `cht` in the name settling Big5
 * against GBK.
 */
internal fun utf8SubtitleBytes(
    data: ByteArray,
    fileName: String,
): ByteArray {
    val name = fileBaseName(fileName)
    val readableAsIs =
        when (detectSubtitleCharset(data, subtitleLabelSuggestsTraditional(name))) {
            YSubtitleCharset.Utf8 -> data.decodesStrictly(Charsets.UTF_8)
            YSubtitleCharset.Utf16Le, YSubtitleCharset.Utf16Be ->
                data.startsWithBytes(UTF16LE_BOM) || data.startsWithBytes(UTF16BE_BOM)
            else -> false
        }
    return if (readableAsIs) data else decodeSubtitleFile(data, name).encodeToByteArray()
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

private fun ByteArray.startsWithBytes(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
