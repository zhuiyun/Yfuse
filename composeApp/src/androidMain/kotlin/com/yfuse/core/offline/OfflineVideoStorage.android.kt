package com.yfuse.core.offline

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class OfflineStoredVideo(
    val path: String,
    val size: Long,
)

internal interface OfflineVideoTarget {
    fun partialSize(): Long

    fun resetPartial()

    fun openPartial(append: Boolean): FileOutputStream

    fun published(): OfflineStoredVideo?

    fun publishPartial(): OfflineStoredVideo

    fun deletePartial()

    fun usableSpace(): Long?
}

internal fun offlineVideoTarget(
    context: Context,
    privateDirectory: File,
    item: OfflineMedia,
): OfflineVideoTarget {
    val prefix = offlineArtifactPrefix(item.id)
    val finalName = "$prefix.${item.downloadRevision}.media"
    return item.storageTreeUri?.let { tree ->
        SafOfflineVideoTarget(
            context = context,
            tree = Uri.parse(tree),
            partialName = "$prefix.part",
            finalName = finalName,
        )
    } ?: FileOfflineVideoTarget(
        context = context,
        partial = File(privateDirectory, "$prefix.part"),
        completed = File(privateDirectory, finalName),
    )
}

internal fun offlineStoredVideo(
    context: Context,
    path: String,
): OfflineStoredVideo? =
    if (path.startsWith("content://")) {
        val uri = Uri.parse(path)
        offlineDocumentSize(context, uri)?.takeIf { it > 0L }?.let { OfflineStoredVideo(path, it) }
    } else {
        File(path).takeIf { it.isFile && it.length() > 0L }?.let { OfflineStoredVideo(it.absolutePath, it.length()) }
    }

private class FileOfflineVideoTarget(
    private val context: Context,
    private val partial: File,
    private val completed: File,
) : OfflineVideoTarget {
    override fun partialSize(): Long = partial.takeIf(File::isFile)?.length() ?: 0L

    override fun resetPartial() {
        partial.delete()
    }

    override fun openPartial(append: Boolean): FileOutputStream {
        partial.parentFile?.mkdirs()
        return FileOutputStream(partial, append)
    }

    override fun published(): OfflineStoredVideo? =
        completed.takeIf { it.isFile && it.length() > 0L }?.let {
            OfflineStoredVideo(it.absolutePath, it.length())
        }

    override fun publishPartial(): OfflineStoredVideo {
        if (!partial.isFile || partial.length() <= 0L) throw OfflineStorageException("离线视频文件不存在")
        completed.delete()
        try {
            Files.move(
                partial.toPath(),
                completed.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(partial.toPath(), completed.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return OfflineStoredVideo(completed.absolutePath, completed.length())
    }

    override fun deletePartial() {
        partial.delete()
    }

    override fun usableSpace(): Long = allocatableOfflineBytes(context, partial.parentFile ?: partial)
}

private class SafOfflineVideoTarget(
    private val context: Context,
    private val tree: Uri,
    private val partialName: String,
    private val finalName: String,
) : OfflineVideoTarget {
    private val resolver get() = context.contentResolver

    override fun partialSize(): Long =
        findOfflineTreeDocument(context, tree, partialName)?.let {
            offlineDocumentSize(context, it)
        } ?: 0L

    override fun resetPartial() {
        findOfflineTreeDocument(context, tree, partialName)?.let {
            runCatching { DocumentsContract.deleteDocument(resolver, it) }
                .getOrElse { error -> throw OfflineStorageException("无法重置所选目录中的临时文件", error) }
        }
    }

    override fun openPartial(append: Boolean): FileOutputStream {
        val document =
            findOfflineTreeDocument(context, tree, partialName)
                ?: DocumentsContract.createDocument(resolver, offlineTreeRoot(tree), "video/*", partialName)
                ?: throw OfflineStorageException("无法在所选目录创建下载临时文件")
        val descriptor =
            resolver.openFileDescriptor(document, "rw")
                ?: throw OfflineStorageException("无法写入所选下载目录")
        val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        try {
            if (append) {
                output.channel.position(partialSize())
            } else {
                output.channel.truncate(0L)
                output.channel.position(0L)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            output.close()
            throw OfflineStorageException("所选目录不支持断点写入", error)
        }
        return output
    }

    override fun published(): OfflineStoredVideo? =
        findOfflineTreeDocument(context, tree, finalName)?.let { uri ->
            offlineDocumentSize(context, uri)?.takeIf { it > 0L }?.let { OfflineStoredVideo(uri.toString(), it) }
        }

    override fun publishPartial(): OfflineStoredVideo {
        val partial =
            findOfflineTreeDocument(context, tree, partialName)
                ?: throw OfflineStorageException("下载临时文件不存在")
        findOfflineTreeDocument(context, tree, finalName)?.let { existing ->
            runCatching { DocumentsContract.deleteDocument(resolver, existing) }
                .getOrElse { error -> throw OfflineStorageException("无法替换所选目录中的旧文件", error) }
        }
        val completed =
            runCatching { DocumentsContract.renameDocument(resolver, partial, finalName) }
                .getOrElse { error -> throw OfflineStorageException("无法完成所选目录中的离线文件", error) }
                ?: throw OfflineStorageException("所选目录不支持原子完成下载")
        val size = offlineDocumentSize(context, completed) ?: 0L
        if (size <= 0L) throw OfflineStorageException("完成后的离线文件为空")
        return OfflineStoredVideo(completed.toString(), size)
    }

    override fun deletePartial() {
        resetPartial()
    }

    override fun usableSpace(): Long? = offlineTreeUsableBytes(context, tree)
}

internal fun offlineTreeRoot(tree: Uri): Uri =
    runCatching {
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }.getOrElse { throw OfflineStorageException("所选下载目录已失效", it) }

internal fun findOfflineTreeDocument(
    context: Context,
    tree: Uri,
    displayName: String,
): Uri? {
    val resolver = context.contentResolver
    val children =
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val columns =
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
    return runCatching {
        resolver.query(children, columns, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == displayName) {
                    return@use DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idColumn))
                }
            }
            null
        }
    }.getOrNull()
}

private fun offlineDocumentSize(
    context: Context,
    uri: Uri,
): Long? {
    val queried =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }
        }.getOrNull()
    if (queried != null && queried >= 0L) return queried
    return runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor -> descriptor.statSize }
    }.getOrNull()?.takeIf { it >= 0L }
}

private fun offlineTreeUsableBytes(
    context: Context,
    tree: Uri,
): Long? {
    val volumeId =
        runCatching { DocumentsContract.getTreeDocumentId(tree).substringBefore(':') }
            .getOrNull()
            ?: return null
    val root =
        if (volumeId.equals("primary", ignoreCase = true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.getSystemService(StorageManager::class.java).primaryStorageVolume.directory
            } else {
                null
            }
        } else {
            File("/storage/$volumeId").takeIf(File::exists)
        }
    return root?.let { runCatching { StatFs(it.absolutePath).availableBytes }.getOrNull() }
}

private fun allocatableOfflineBytes(
    context: Context,
    directory: File,
): Long =
    runCatching {
        val storage = context.getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(directory))
    }.getOrElse {
        runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(0L)
    }
