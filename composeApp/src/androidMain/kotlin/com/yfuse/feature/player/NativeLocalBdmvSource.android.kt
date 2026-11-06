package com.yfuse.feature.player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only filesystem bridge for an extracted BDMV tree.
 *
 * libbluray's `bd_open_files()` asks for relative files and directories instead of a single ISO
 * block device. This class keeps that surface process-local and deliberately validates every path:
 * absolute paths, `..`, NULs and filesystem/Symlink escapes are rejected before native code can
 * reach them. Both ordinary filesystem directories and persisted SAF tree URIs are supported.
 */
internal class NativeLocalBdmvSource private constructor(
    private val root: BdmvRoot,
) {
    private val nextHandle = AtomicLong(1L)
    private val files = mutableMapOf<Long, BdmvOpenFile>()
    private val directories = mutableMapOf<Long, BdmvOpenDirectory>()

    @Volatile
    private var closed = false

    @Synchronized
    @Suppress("unused")
    fun openFileNative(relativePath: String): Long {
        if (closed) return 0L
        val normalized = normalizeBdmvRelativePath(relativePath) ?: return 0L
        val file = root.openFile(normalized) ?: return 0L
        val handle =
            nextHandle.getAndIncrement().takeIf { it > 0L } ?: run {
                file.close()
                return 0L
            }
        files[handle] = file
        return handle
    }

    @Synchronized
    @Suppress("unused")
    fun readFileNative(
        handle: Long,
        target: ByteArray,
        targetOffset: Int,
        length: Int,
    ): Int {
        if (closed || handle <= 0L || targetOffset < 0 || length < 0) return -1
        if (targetOffset > target.size || length > target.size - targetOffset) return -1
        return files[handle]?.read(target, targetOffset, length) ?: -1
    }

    @Synchronized
    @Suppress("unused")
    fun seekFileNative(
        handle: Long,
        offset: Long,
        origin: Int,
    ): Long = if (closed) -1L else files[handle]?.seek(offset, origin) ?: -1L

    @Synchronized
    @Suppress("unused")
    fun tellFileNative(handle: Long): Long = if (closed) -1L else files[handle]?.tell() ?: -1L

    @Synchronized
    @Suppress("unused")
    fun closeFileNative(handle: Long) {
        files.remove(handle)?.close()
    }

    @Synchronized
    @Suppress("unused")
    fun openDirNative(relativePath: String): Long {
        if (closed) return 0L
        val normalized = normalizeBdmvRelativePath(relativePath) ?: return 0L
        val directory = root.openDirectory(normalized) ?: return 0L
        val handle = nextHandle.getAndIncrement().takeIf { it > 0L } ?: return 0L
        directories[handle] = directory
        return handle
    }

    @Synchronized
    @Suppress("unused")
    fun readDirNative(handle: Long): String? = if (closed) null else directories[handle]?.nextName()

    @Synchronized
    @Suppress("unused")
    fun closeDirNative(handle: Long) {
        directories.remove(handle)?.close()
    }

    @Synchronized
    @Suppress("unused")
    fun closeNativeSource() {
        if (closed) return
        closed = true
        files.values.forEach(BdmvOpenFile::close)
        files.clear()
        directories.values.forEach(BdmvOpenDirectory::close)
        directories.clear()
        root.close()
    }

    companion object {
        fun create(
            context: Context,
            uri: String,
        ): NativeLocalBdmvSource? {
            val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
            val root =
                when (parsed.scheme?.lowercase()) {
                    "file" -> parsed.path?.let(::File)?.let(::FileBdmvRoot)
                    "content" -> SafBdmvRoot.create(context.contentResolver, parsed)
                    else -> null
                } ?: return null
            if (!root.looksLikeBdmv()) {
                root.close()
                return null
            }
            return NativeLocalBdmvSource(root)
        }
    }
}

internal fun normalizeBdmvRelativePath(raw: String?): String? {
    val value = raw?.replace('\\', '/')?.trim().orEmpty()
    if ('\u0000' in value || value.startsWith('/')) return null
    val segments = value.split('/').filter(String::isNotEmpty)
    if (segments.any { it == "." || it == ".." }) return null
    return segments.joinToString("/")
}

private interface BdmvRoot : AutoCloseable {
    fun openFile(relativePath: String): BdmvOpenFile?

    fun openDirectory(relativePath: String): BdmvOpenDirectory?

    fun looksLikeBdmv(): Boolean

    override fun close() = Unit
}

private interface BdmvOpenFile : AutoCloseable {
    fun read(
        target: ByteArray,
        targetOffset: Int,
        length: Int,
    ): Int

    fun seek(
        offset: Long,
        origin: Int,
    ): Long

    fun tell(): Long
}

private interface BdmvOpenDirectory : AutoCloseable {
    fun nextName(): String?

    override fun close() = Unit
}

private class FileBdmvRoot(
    selected: File,
) : BdmvRoot {
    private val root: File? = discoverFileBdmvRoot(selected)
    private val rootPath: String? = root?.canonicalFile?.path

    override fun looksLikeBdmv(): Boolean = resolve("BDMV/index.bdmv")?.isFile == true

    override fun openFile(relativePath: String): BdmvOpenFile? {
        val file = resolve(relativePath)?.takeIf(File::isFile) ?: return null
        return runCatching { RandomAccessBdmvFile(RandomAccessFile(file, "r")) }.getOrNull()
    }

    override fun openDirectory(relativePath: String): BdmvOpenDirectory? {
        val directory = resolve(relativePath)?.takeIf(File::isDirectory) ?: return null
        val names = directory.listFiles()?.map(File::getName)?.sortedWith(String.CASE_INSENSITIVE_ORDER) ?: return null
        return ListBdmvDirectory(names)
    }

    private fun resolve(relativePath: String): File? {
        val base = rootPath ?: return null
        val normalized = normalizeBdmvRelativePath(relativePath) ?: return null
        val candidate = runCatching { File(root, normalized).canonicalFile }.getOrNull() ?: return null
        val path = candidate.path
        val inside = path == base || path.startsWith(base + File.separator)
        return candidate.takeIf { inside }
    }
}

private fun discoverFileBdmvRoot(selected: File): File? {
    val canonical = runCatching { selected.canonicalFile }.getOrNull() ?: return null
    if (canonical.isFile) {
        if (!canonical.name.equals("index.bdmv", true) && !canonical.name.equals("MovieObject.bdmv", true)) return null
        val bdmv = canonical.parentFile ?: return null
        return bdmv.parentFile?.takeIf { bdmv.name.equals("BDMV", true) }
    }
    if (!canonical.isDirectory) return null
    if (canonical.name.equals("BDMV", true)) return canonical.parentFile
    return canonical.takeIf { directory ->
        directory.listFiles()?.any { it.isDirectory && it.name.equals("BDMV", true) } == true
    }
}

private class RandomAccessBdmvFile(
    private val file: RandomAccessFile,
) : BdmvOpenFile {
    override fun read(
        target: ByteArray,
        targetOffset: Int,
        length: Int,
    ): Int =
        if (length ==
            0
        ) {
            0
        } else {
            runCatching { file.read(target, targetOffset, length).coerceAtLeast(0) }.getOrDefault(-1)
        }

    override fun seek(
        offset: Long,
        origin: Int,
    ): Long =
        runCatching {
            val base =
                when (origin) {
                    SEEK_SET -> 0L
                    SEEK_CUR -> file.filePointer
                    SEEK_END -> file.length()
                    else -> return -1L
                }
            val target = checkedAdd(base, offset)?.takeIf { it >= 0L } ?: return -1L
            file.seek(target)
            file.filePointer
        }.getOrDefault(-1L)

    override fun tell(): Long = runCatching { file.filePointer }.getOrDefault(-1L)

    override fun close() {
        runCatching { file.close() }
    }
}

private class SafBdmvRoot private constructor(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val rootNode: SafNode,
    private val selectedBdmvDirectory: Boolean,
) : BdmvRoot {
    override fun looksLikeBdmv(): Boolean = resolve("BDMV/index.bdmv")?.directory == false

    override fun openFile(relativePath: String): BdmvOpenFile? {
        val node = resolve(relativePath)?.takeIf { !it.directory } ?: return null
        val descriptor = runCatching { resolver.openFileDescriptor(node.uri, "r") }.getOrNull() ?: return null
        return SafBdmvFile.create(descriptor)
    }

    override fun openDirectory(relativePath: String): BdmvOpenDirectory? {
        val node = resolve(relativePath)?.takeIf(SafNode::directory) ?: return null
        val names = children(node).map(SafNode::name).sortedWith(String.CASE_INSENSITIVE_ORDER)
        return ListBdmvDirectory(names)
    }

    private fun resolve(relativePath: String): SafNode? {
        val normalized = normalizeBdmvRelativePath(relativePath) ?: return null
        var segments = normalized.split('/').filter(String::isNotEmpty)
        if (selectedBdmvDirectory && segments.firstOrNull()?.equals("BDMV", true) == true) {
            segments = segments.drop(1)
        }
        var node = rootNode
        for (segment in segments) {
            if (!node.directory) return null
            node = children(node).firstOrNull { it.name.equals(segment, true) } ?: return null
        }
        return node
    }

    private fun children(parent: SafNode): List<SafNode> {
        val childrenUri =
            runCatching { DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId) }
                .getOrNull() ?: return emptyList()
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        return runCatching {
            resolver
                .query(childrenUri, projection, null, null, null)
                ?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    buildList {
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(idColumn) ?: continue
                            val name = cursor.getString(nameColumn)?.takeIf(String::isNotBlank) ?: continue
                            val mime = cursor.getString(mimeColumn).orEmpty()
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            add(
                                SafNode(
                                    documentId = id,
                                    uri = uri,
                                    name = name,
                                    directory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                                ),
                            )
                        }
                    }
                }.orEmpty()
        }.getOrDefault(emptyList())
    }

    companion object {
        fun create(
            resolver: ContentResolver,
            treeUri: Uri,
        ): SafBdmvRoot? {
            if (!DocumentsContract.isTreeUri(treeUri)) return null
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
            val rootDocument =
                runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId) }.getOrNull() ?: return null
            val projection =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
            val root =
                runCatching {
                    resolver.query(rootDocument, projection, null, null, null)?.use { cursor ->
                        if (!cursor.moveToFirst()) return@use null
                        val name = cursor.getString(0)?.takeIf(String::isNotBlank) ?: return@use null
                        val mime = cursor.getString(1).orEmpty()
                        SafNode(
                            documentId = rootId,
                            uri = rootDocument,
                            name = name,
                            directory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        )
                    }
                }.getOrNull() ?: return null
            if (!root.directory) return null
            return SafBdmvRoot(
                resolver = resolver,
                treeUri = treeUri,
                rootNode = root,
                selectedBdmvDirectory = root.name.equals("BDMV", true),
            )
        }
    }
}

private data class SafNode(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val directory: Boolean,
)

private class SafBdmvFile private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val length: Long,
) : BdmvOpenFile {
    private var position = 0L

    override fun read(
        target: ByteArray,
        targetOffset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        return try {
            val count = Os.pread(descriptor.fileDescriptor, target, targetOffset, length, position)
            if (count > 0) position += count
            count.coerceAtLeast(0)
        } catch (_: ErrnoException) {
            -1
        }
    }

    override fun seek(
        offset: Long,
        origin: Int,
    ): Long {
        val base =
            when (origin) {
                SEEK_SET -> 0L
                SEEK_CUR -> position
                SEEK_END -> length
                else -> return -1L
            }
        val target = checkedAdd(base, offset)?.takeIf { it >= 0L } ?: return -1L
        position = target
        return target
    }

    override fun tell(): Long = position

    override fun close() {
        runCatching { descriptor.close() }
    }

    companion object {
        fun create(descriptor: ParcelFileDescriptor): SafBdmvFile? {
            val length =
                try {
                    Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
                    Os.fstat(descriptor.fileDescriptor).st_size
                } catch (_: ErrnoException) {
                    descriptor.close()
                    return null
                }
            return SafBdmvFile(descriptor, length.coerceAtLeast(0L))
        }
    }
}

private class ListBdmvDirectory(
    private val names: List<String>,
) : BdmvOpenDirectory {
    private var index = 0

    override fun nextName(): String? = names.getOrNull(index)?.also { index++ }
}

private fun checkedAdd(
    a: Long,
    b: Long,
): Long? {
    if (b > 0L && a > Long.MAX_VALUE - b) return null
    if (b < 0L && a < Long.MIN_VALUE - b) return null
    return a + b
}

private const val SEEK_SET = 0
private const val SEEK_CUR = 1
private const val SEEK_END = 2
