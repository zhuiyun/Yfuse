package com.yfuse.core2.android

import com.yfuse.core2.network.YCacheIdentity
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Credential-free persistent sparse block store owned by YCore.
 *
 * Directory and file names contain only hashes and numeric block indexes. Signed URLs, account
 * tokens, request headers, titles, and server addresses are never written to disk.
 */
internal class AndroidYCoreBlockCache(
    cacheDirectory: File,
    identity: YCacheIdentity,
    /**
     * The stride a block index is measured in.
     *
     * A block file is addressed by index alone, so its byte offset is only meaningful together with
     * the stride it was written under. Recording it here and rejecting a mismatch is what stops a
     * later build - one that sizes read-ahead differently - from reading block N at the wrong offset
     * and passing the checksum while doing it.
     */
    private val blockSizeBytes: Int,
    private val maximumBytes: Long,
) {
    private val root = File(cacheDirectory, CACHE_ROOT_DIRECTORY)
    private val supersededRoots = SUPERSEDED_CACHE_ROOT_DIRECTORIES.map { name -> File(cacheDirectory, name) }
    private val sourceDirectory = File(root, yCoreCacheDirectoryKey(identity))
    private val contentLengthFile = File(sourceDirectory, CONTENT_LENGTH_FILE)

    init {
        require(blockSizeBytes > 0)
        require(maximumBytes > 0L)
        synchronized(CACHE_LOCK) {
            // v1 blocks carried no integrity header and v2 blocks no stride, so neither can be
            // validated by this format and both have to go rather than be read on faith.
            supersededRoots.forEach { directory -> if (directory.isDirectory) directory.deleteRecursively() }
        }
    }

    val contentLength: Long?
        get() =
            synchronized(CACHE_LOCK) {
                contentLengthFile
                    .takeIf(File::isFile)
                    ?.let { file -> runCatching { file.readText() }.getOrNull() }
                    ?.trim()
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L }
            }

    fun readBlock(index: Long): ByteArray? =
        synchronized(CACHE_LOCK) {
            require(index >= 0L)
            val file = blockFile(index)
            val length = file.length()
            if (
                !file.isFile ||
                length !in (BLOCK_HEADER_BYTES + 1L)..(BLOCK_HEADER_BYTES + blockSizeBytes)
            ) {
                if (file.exists()) file.delete()
                return@synchronized null
            }
            val block = runCatching { decodeBlock(file.readBytes(), blockSizeBytes) }.getOrNull()
            if (block == null) {
                file.delete()
                return@synchronized null
            }
            file.setLastModified(System.currentTimeMillis())
            block
        }

    fun writeBlock(
        index: Long,
        bytes: ByteArray,
        contentLength: Long?,
    ) {
        require(index >= 0L && bytes.isNotEmpty())
        synchronized(CACHE_LOCK) {
            sourceDirectory.mkdirs()
            if (!sourceDirectory.isDirectory) return
            require(bytes.size <= blockSizeBytes)
            writeAtomically(blockFile(index), encodeBlock(bytes, blockSizeBytes))
            contentLength?.takeIf { it >= 0L }?.let { length ->
                writeAtomically(contentLengthFile, length.toString().encodeToByteArray())
            }
            trimToBudget()
        }
    }

    private fun blockFile(index: Long): File = File(sourceDirectory, "$BLOCK_PREFIX$index$BLOCK_SUFFIX")

    private fun trimToBudget() {
        if (!root.isDirectory) return
        val blocks =
            root
                .walkTopDown()
                .filter { file ->
                    file.isFile && file.name.startsWith(BLOCK_PREFIX) && file.name.endsWith(BLOCK_SUFFIX)
                }.toList()
                .sortedBy(File::lastModified)
        var total = blocks.sumOf { block -> block.payloadLengthOnDisk() }
        val iterator = blocks.iterator()
        while (total > maximumBytes && iterator.hasNext()) {
            val oldest = iterator.next()
            val length = oldest.payloadLengthOnDisk()
            if (oldest.delete()) total -= length
        }
        root
            .walkBottomUp()
            .filter { it.isDirectory && it != root }
            .forEach { directory -> if (directory.list().isNullOrEmpty()) directory.delete() }
    }

    private fun writeAtomically(
        target: File,
        bytes: ByteArray,
    ) {
        val temporary = File(target.parentFile, "${target.name}.${System.nanoTime()}$TEMP_SUFFIX")
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = BufferedOutputStream(fileOutput)
                output.write(bytes)
                output.flush()
                fileOutput.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                target.delete()
                require(temporary.renameTo(target)) { "YCore cache block could not be committed" }
            }
            target.setLastModified(System.currentTimeMillis())
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        val CACHE_LOCK = Any()
    }
}

private fun encodeBlock(
    bytes: ByteArray,
    blockSizeBytes: Int,
): ByteArray {
    val checksum = CRC32().apply { update(bytes) }.value
    return ByteArrayOutputStream(BLOCK_HEADER_BYTES + bytes.size).use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(BLOCK_MAGIC)
            data.writeInt(blockSizeBytes)
            data.writeInt(bytes.size)
            data.writeLong(checksum)
            data.write(bytes)
        }
        output.toByteArray()
    }
}

private fun decodeBlock(
    encoded: ByteArray,
    blockSizeBytes: Int,
): ByteArray? =
    DataInputStream(ByteArrayInputStream(encoded)).use { input ->
        if (input.readInt() != BLOCK_MAGIC) return null
        // The stride turns an index into a byte offset. A block stored under a different one
        // describes different bytes of the same media and would pass every later check.
        if (input.readInt() != blockSizeBytes) return null
        val length = input.readInt()
        if (length !in 1..blockSizeBytes || encoded.size != BLOCK_HEADER_BYTES + length) return null
        val expectedChecksum = input.readLong()
        val bytes = ByteArray(length)
        input.readFully(bytes)
        bytes.takeIf { CRC32().apply { update(bytes) }.value == expectedChecksum }
    }

private fun File.payloadLengthOnDisk(): Long = (length() - BLOCK_HEADER_BYTES).coerceAtLeast(0L)

internal fun yCoreCacheDirectoryKey(identity: YCacheIdentity): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(identity.key().encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val CACHE_ROOT_DIRECTORY = "ycore-media-v3"
private val SUPERSEDED_CACHE_ROOT_DIRECTORIES = listOf("ycore-media-v1", "ycore-media-v2")
private const val CONTENT_LENGTH_FILE = "length"
private const val BLOCK_PREFIX = "block-"
private const val BLOCK_SUFFIX = ".bin"
private const val TEMP_SUFFIX = ".tmp"
private const val BLOCK_MAGIC = 0x59434232
private const val BLOCK_HEADER_BYTES = 20
