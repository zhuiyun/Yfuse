package com.yfuse.core.account

import com.yfuse.core.security.VaultCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The most ciphertext the server keeps per account: its AccountLimits.MAX_CIPHERTEXT_BYTES. */
internal const val MAX_SYNC_CIPHERTEXT_BYTES = 256 * 1024

/** AES-GCM appends a 16-byte tag to what it encrypts. */
internal const val MAX_SYNC_PLAINTEXT_BYTES = MAX_SYNC_CIPHERTEXT_BYTES - VaultCrypto.GCM_TAG_SIZE_BYTES

/**
 * The most JSON a document may hold once restored. The synced lists' own caps keep a real one
 * far below it; the bound is what stops a damaged document from inflating without limit.
 */
internal const val MAX_SYNC_DOCUMENT_BYTES = 8 * 1024 * 1024

/**
 * The bytes to encrypt for [document]: the JSON itself when it fits, otherwise its gzip
 * compression. Null when even that is more than the server keeps.
 *
 * With 个人清单 in the document, six hundred or so watched episodes outgrow the server's limit
 * as JSON, because every history entry repeats every field name. A document that fits is stored
 * exactly as earlier builds wrote it, so a device that has not been updated still restores it;
 * only one that could not have been uploaded at all is compressed, which shrinks it five- to
 * eightfold. JSON starts with `{` and gzip with its magic number, so [decodeSyncDocument] tells
 * them apart without a format field: the envelope's schema version is pinned by the server.
 */
internal fun encodeSyncDocument(document: String): ByteArray? {
    val plain = document.encodeToByteArray()
    if (plain.size <= MAX_SYNC_PLAINTEXT_BYTES) return plain
    try {
        if (plain.size > MAX_SYNC_DOCUMENT_BYTES) return null
        val compressed =
            ByteArrayOutputStream().use { output ->
                BestGzipOutputStream(output).use { it.write(plain) }
                output.toByteArray()
            }
        return compressed.takeIf { it.size <= MAX_SYNC_PLAINTEXT_BYTES }
    } finally {
        plain.fill(0)
    }
}

/** Reverses [encodeSyncDocument]; documents from builds that never compressed are plain JSON. */
internal fun decodeSyncDocument(plaintext: ByteArray): String {
    require(plaintext.size <= MAX_SYNC_PLAINTEXT_BYTES) { "云端同步数据过大" }
    if (!plaintext.isGzip()) return plaintext.decodeToString()
    val document =
        try {
            GZIPInputStream(ByteArrayInputStream(plaintext)).use { it.readAtMost(MAX_SYNC_DOCUMENT_BYTES) }
        } catch (error: IOException) {
            throw IllegalArgumentException("云端同步数据已损坏", error)
        }
    return try {
        document.decodeToString()
    } finally {
        document.fill(0)
    }
}

private fun ByteArray.isGzip(): Boolean = size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = read(buffer)
        if (read < 0) return output.toByteArray()
        require(output.size() <= limit - read) { "云端同步数据过大" }
        output.write(buffer, 0, read)
    }
}

/** The document is compressed only when it is too big, so it is worth the slowest level. */
private class BestGzipOutputStream(
    output: OutputStream,
) : GZIPOutputStream(output) {
    init {
        def.setLevel(Deflater.BEST_COMPRESSION)
    }
}
