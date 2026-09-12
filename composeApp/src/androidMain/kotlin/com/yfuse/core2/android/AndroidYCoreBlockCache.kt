package com.yfuse.core2.android

import com.yfuse.core2.network.YCacheIdentity
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
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
    private val writeQueue: AndroidCacheWriteQueue = WRITER,
) {
    private val root = File(cacheDirectory, CACHE_ROOT_DIRECTORY)
    private val supersededRoots = SUPERSEDED_CACHE_ROOT_DIRECTORIES.map { name -> File(cacheDirectory, name) }
    private val sourceDirectory = File(root, yCoreCacheDirectoryKey(identity))
    private val contentLengthFile = File(sourceDirectory, CONTENT_LENGTH_FILE)
    private val entityTagFile = File(sourceDirectory, "entity-tag")
    private val representationEpoch =
        synchronized(EPOCHS) {
            EPOCHS.entries.removeAll { it.value.get() == null }
            EPOCHS[sourceDirectory.absolutePath]?.get()
                ?: AtomicLong().also { EPOCHS[sourceDirectory.absolutePath] = java.lang.ref.WeakReference(it) }
        }

    @Volatile private var acceptedEpoch = representationEpoch.get()

    /** Reuse bytes across opens only after the origin proves the same strong entity and length. */
    fun validateRepresentation(
        length: Long?,
        entityTag: String?,
    ) {
        synchronized(index.writeLock) {
            val storedTag = runCatching { entityTagFile.readText() }.getOrNull()
            if (entityTag == null || storedTag != entityTag || contentLength != length) invalidateLocked()
            acceptedEpoch = representationEpoch.get()
            sourceDirectory.mkdirs()
            if (entityTag != null) writeAtomically(entityTagFile, entityTag.encodeToByteArray())
            if (length != null) writeAtomically(contentLengthFile, length.toString().encodeToByteArray())
        }
    }

    /** Old source instances and already queued writes may never repopulate an invalidated entry. */
    fun invalidate() = synchronized(index.writeLock) { invalidateLocked() }

    private fun invalidateLocked() {
        representationEpoch.incrementAndGet()
        index.initialize()
        sourceDirectory.listFiles()?.forEach { file ->
            if (file.delete() || !file.exists()) index.remove(file)
        }
    }

    private val index = synchronized(ROOTS) { ROOTS.getOrPut(root.absolutePath) { CacheIndex(root) } }
    private val maximumWriteNs = AtomicLong()
    private val writeFailures = AtomicLong()
    private val droppedWrites = AtomicLong()

    val maximumWriteMs: Long get() = maximumWriteNs.get() / 1_000_000L
    val failedWriteCount: Long get() = writeFailures.get()
    val droppedWriteCount: Long get() = droppedWrites.get()
    val canAcceptWrite: Boolean get() = writeQueue.hasCapacity(blockSizeBytes)

    /** Used by cache verification and orderly maintenance, never by the foreground reader. */
    fun awaitPendingWrites(timeoutMs: Long): Boolean = writeQueue.awaitIdle(timeoutMs)

    /** The caller owns immutable block bytes. At most four writes / 16 MiB are retained process-wide. */
    fun enqueueWriteBlock(
        blockIndex: Long,
        bytes: ByteArray,
        contentLength: Long?,
    ) {
        val scheduledEpoch = acceptedEpoch
        if (!writeQueue.enqueue(blockFile(blockIndex).absolutePath, bytes.size) {
                val startedNs = System.nanoTime()
                try {
                    synchronized(index.writeLock) {
                        if (scheduledEpoch == representationEpoch.get()) writeBlock(blockIndex, bytes, contentLength)
                    }
                } catch (_: Exception) {
                    // Cache persistence is optional; it must never turn delivered media into an EOF.
                    writeFailures.incrementAndGet()
                } finally {
                    maximumWriteNs.accumulateAndGet(System.nanoTime() - startedNs, ::maxOf)
                }
            }
        ) {
            droppedWrites.incrementAndGet()
        }
    }

    init {
        require(blockSizeBytes > 0)
        require(maximumBytes > 0L)
        if (supersededRoots.any(File::isDirectory)) {
            synchronized(index.writeLock) {
                // v1 blocks carried no integrity header and v2 blocks no stride, so neither can be
                // validated by this format and both have to go rather than be read on faith.
                supersededRoots.forEach { directory -> if (directory.isDirectory) directory.deleteRecursively() }
            }
        }
    }

    val contentLength: Long?
        get() =
            run {
                contentLengthFile
                    .takeIf(File::isFile)
                    ?.let { file -> runCatching { file.readText() }.getOrNull() }
                    ?.trim()
                    ?.toLongOrNull()
                    ?.takeIf { it >= 0L }
            }

    fun readBlock(index: Long): ByteArray? =
        run {
            if (acceptedEpoch != representationEpoch.get()) return@run null
            require(index >= 0L)
            val file = blockFile(index)
            val length = file.length()
            if (
                !file.isFile ||
                length !in (BLOCK_HEADER_BYTES + 1L)..(BLOCK_HEADER_BYTES + blockSizeBytes)
            ) {
                if (file.exists()) discardIfStillInvalid(file)
                return@run null
            }
            val block = runCatching { decodeBlock(file.readBytes(), blockSizeBytes) }.getOrNull()
            if (block == null) {
                discardIfStillInvalid(file)
                return@run null
            }
            file.setLastModified(System.currentTimeMillis())
            this.index.touch(file)
            block.takeIf { acceptedEpoch == representationEpoch.get() }
        }

    /** Metadata-only lookup for forward-cache scheduling; readBlock still validates the CRC before serving bytes. */
    fun cachedBlockLength(index: Long): Int? =
        run {
            if (acceptedEpoch != representationEpoch.get()) return@run null
            val file = blockFile(index)
            this.index.length(file, blockSizeBytes)?.let { length ->
                if (file.isFile && file.length() == BLOCK_HEADER_BYTES + length.toLong()) return@run length
                discardIfStillInvalid(file)
                return@run null
            }
            runCatching {
                DataInputStream(file.inputStream()).use { input ->
                    if (input.readInt() != BLOCK_MAGIC || input.readInt() != blockSizeBytes) return@use null
                    val size = input.readInt()
                    size.takeIf { it in 1..blockSizeBytes && file.length() == BLOCK_HEADER_BYTES + it.toLong() }
                }
            }.getOrNull()
        }

    fun writeBlock(
        index: Long,
        bytes: ByteArray,
        contentLength: Long?,
    ) {
        require(index >= 0L && bytes.isNotEmpty())
        synchronized(this.index.writeLock) {
            if (acceptedEpoch != representationEpoch.get()) return
            this.index.initialize()
            sourceDirectory.mkdirs()
            if (!sourceDirectory.isDirectory) return
            require(bytes.size <= blockSizeBytes)
            val file = blockFile(index)
            // Background/full-block reads can race another source instance warming the same item.
            // Compare validated bytes so a corrupt file or changed payload is never mistaken for a hit.
            if (readBlock(index)?.contentEquals(bytes) != true) {
                writeAtomically(file, encodeBlock(bytes, blockSizeBytes))
            }
            this.index.record(file, bytes.size, blockSizeBytes)
            contentLength?.takeIf { it >= 0L }?.let { length ->
                if (this.contentLength != length) {
                    writeAtomically(contentLengthFile, length.toString().encodeToByteArray())
                }
            }
            this.index.trimToBudget(maximumBytes)
        }
    }

    private fun blockFile(index: Long): File = File(sourceDirectory, "$BLOCK_PREFIX$index$BLOCK_SUFFIX")

    private fun discardIfStillInvalid(file: File) {
        writeQueue.enqueue("repair:${file.absolutePath}", 1) {
            synchronized(index.writeLock) {
                // A reader may have observed the old inode immediately before an atomic commit.
                // Revalidate the current file under the writer lock before deleting anything.
                val valid = runCatching { decodeBlock(file.readBytes(), blockSizeBytes) }.getOrNull()
                if (valid == null) {
                    if (file.delete() || !file.exists()) index.remove(file)
                } else {
                    index.record(file, valid.size, blockSizeBytes)
                }
            }
        }
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
            // Android's app cache is on one filesystem. Readers can retain the previous inode while
            // this atomically replaces it; they never wait behind a writer's fsync or eviction scan.
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            target.setLastModified(System.currentTimeMillis())
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        val ROOTS = mutableMapOf<String, CacheIndex>()
        val EPOCHS = mutableMapOf<String, java.lang.ref.WeakReference<AtomicLong>>()
        val WRITER = AndroidCacheWriteQueue()
    }
}

/** LRU metadata is built once on the writer, then updated in constant time per read/write. */
private class CacheIndex(
    private val root: File,
) {
    val writeLock = Any()
    private val entries = LinkedHashMap<File, Pair<Int, Int>>()
    private var totalBytes = 0L
    private var initialized = false

    fun initialize() {
        if (initialized) return
        root
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith(BLOCK_PREFIX) && it.name.endsWith(BLOCK_SUFFIX) }
            .sortedBy(File::lastModified)
            .forEach { file ->
                runCatching {
                    DataInputStream(file.inputStream()).use { input ->
                        if (input.readInt() != BLOCK_MAGIC) return@use
                        val stride = input.readInt()
                        val length = input.readInt()
                        if (length in 1..stride &&
                            file.length() == BLOCK_HEADER_BYTES + length.toLong()
                        ) {
                            record(file, length, stride)
                        }
                    }
                }
            }
        initialized = true
    }

    @Synchronized
    fun length(
        file: File,
        stride: Int,
    ): Int? = entries[file]?.takeIf { it.second == stride }?.first

    @Synchronized
    fun touch(file: File) {
        entries.remove(file)?.let { entries[file] = it }
    }

    @Synchronized
    fun record(
        file: File,
        length: Int,
        stride: Int,
    ) {
        totalBytes -= entries.remove(file)?.first ?: 0
        entries[file] = length to stride
        totalBytes += length
    }

    @Synchronized
    fun remove(file: File) {
        totalBytes -= entries.remove(file)?.first ?: 0
    }

    fun trimToBudget(maximumBytes: Long) {
        while (true) {
            val oldest =
                synchronized(this) {
                    if (totalBytes <= maximumBytes) return
                    entries.keys.firstOrNull() ?: return
                }
            if (oldest.delete() || !oldest.exists()) remove(oldest) else return
        }
    }
}

/** Non-blocking admission; queued duplicate blocks share a single writer and failures release capacity. */
internal class AndroidCacheWriteQueue(
    private val maximumBytes: Long = 16L * 1024L * 1024L,
    private val maximumEntries: Int = 4,
    private val execute: (() -> Unit) -> Unit = { task -> executor.execute(task) },
) {
    private val pending = mutableSetOf<String>()
    private var bytes = 0L
    private var memoryLease: PlaybackMemoryLease? = null

    private fun budgetBytes(): Long {
        AndroidPlaybackMemoryBudget.refreshPressure()
        val lease = memoryLease ?: AndroidPlaybackMemoryBudget.acquire(PlaybackBufferKind.CacheWrite, maximumBytes)
        memoryLease = lease
        return minOf(maximumBytes, lease.limitBytes)
    }

    private fun releaseIdleLease() {
        if (pending.isEmpty()) {
            memoryLease?.close()
            memoryLease = null
        }
    }

    @Synchronized
    fun hasCapacity(size: Int): Boolean =
        try {
            pending.size < maximumEntries && size <= budgetBytes() - bytes
        } finally {
            releaseIdleLease()
        }

    fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (synchronized(this) { pending.isEmpty() }) return true
            Thread.sleep(1L)
        }
        return synchronized(this) { pending.isEmpty() }
    }

    @Synchronized
    fun enqueue(
        key: String,
        size: Int,
        write: () -> Unit,
    ): Boolean {
        require(size > 0)
        if (key in pending) return true
        if (pending.size >= maximumEntries || size > budgetBytes() - bytes) {
            releaseIdleLease()
            return false
        }
        pending.add(key)
        bytes += size
        try {
            execute {
                try {
                    write()
                } finally {
                    synchronized(this) {
                        pending.remove(key)
                        bytes -= size
                        if (pending.isEmpty()) {
                            memoryLease?.close()
                            memoryLease = null
                        }
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pending.remove(key)
            bytes -= size
            if (pending.isEmpty()) {
                memoryLease?.close()
                memoryLease = null
            }
            return false
        }
        return true
    }

    private companion object {
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "YCore-CacheWriter").apply {
                    isDaemon =
                        true
                }
            }
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
