package com.yfuse.core2.android

import com.yfuse.core2.network.YCacheIdentity
import java.io.File
import java.security.MessageDigest

/**
 * Credential-free persistent sparse block store owned by YCore.
 *
 * Directory and file names contain only hashes and numeric block indexes. Signed URLs, account
 * tokens, request headers, titles, and server addresses are never written to disk.
 */
internal class AndroidYCoreBlockCache(
    cacheDirectory: File,
    identity: YCacheIdentity,
    private val maximumBytes: Long,
) {
    private val root = File(cacheDirectory, CACHE_ROOT_DIRECTORY)
    private val sourceDirectory = File(root, yCoreCacheDirectoryKey(identity))
    private val contentLengthFile = File(sourceDirectory, CONTENT_LENGTH_FILE)

    init {
        require(maximumBytes > 0L)
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

    fun readBlock(
        index: Long,
        maximumBlockBytes: Int,
    ): ByteArray? =
        synchronized(CACHE_LOCK) {
            require(index >= 0L && maximumBlockBytes > 0)
            val file = blockFile(index)
            val length = file.length()
            if (!file.isFile || length !in 1L..maximumBlockBytes.toLong()) {
                if (file.exists()) file.delete()
                return@synchronized null
            }
            file.setLastModified(System.currentTimeMillis())
            runCatching { file.readBytes() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= maximumBlockBytes }
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
            writeAtomically(blockFile(index), bytes)
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
        var total = blocks.sumOf(File::length)
        val iterator = blocks.iterator()
        while (total > maximumBytes && iterator.hasNext()) {
            val oldest = iterator.next()
            val length = oldest.length()
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
            temporary.outputStream().buffered().use { it.write(bytes) }
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

internal fun yCoreCacheDirectoryKey(identity: YCacheIdentity): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(identity.key().encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val CACHE_ROOT_DIRECTORY = "ycore-media-v1"
private const val CONTENT_LENGTH_FILE = "length"
private const val BLOCK_PREFIX = "block-"
private const val BLOCK_SUFFIX = ".bin"
private const val TEMP_SUFFIX = ".tmp"
