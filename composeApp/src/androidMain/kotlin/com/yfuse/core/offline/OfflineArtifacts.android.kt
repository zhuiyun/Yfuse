package com.yfuse.core.offline

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal const val OFFLINE_STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L

internal const val MAX_OFFLINE_SUBTITLE_BYTES = 16L * 1024L * 1024L

internal fun hasSufficientOfflineStorage(
    usableSpace: Long,
    requiredBytes: Long,
    reserveBytes: Long = OFFLINE_STORAGE_RESERVE_BYTES,
): Boolean {
    val usable = usableSpace.coerceAtLeast(0L)
    val reserve = reserveBytes.coerceAtLeast(0L)
    val required = requiredBytes.coerceAtLeast(0L)
    return usable >= reserve && required <= usable - reserve
}

internal fun missingOfflineStorageBytes(
    usableSpace: Long,
    requiredBytes: Long,
    reserveBytes: Long = OFFLINE_STORAGE_RESERVE_BYTES,
): Long {
    val usable = usableSpace.coerceAtLeast(0L)
    val reserve = reserveBytes.coerceAtLeast(0L)
    val required = requiredBytes.coerceAtLeast(0L)
    if (required > Long.MAX_VALUE - reserve) return Long.MAX_VALUE
    val totalRequired = required + reserve
    return (totalRequired - usable).coerceAtLeast(0L)
}

internal fun isOfflineArtifactName(name: String): Boolean =
    name.endsWith(".media") ||
        name.endsWith(".part") ||
        name.endsWith(".srt")

internal fun validateOfflineSubtitleContentLength(
    contentLength: Long,
    maxBytes: Long = MAX_OFFLINE_SUBTITLE_BYTES,
) {
    require(maxBytes >= 0L) { "字幕大小上限无效" }
    if (contentLength >= 0L && contentLength > maxBytes) {
        throw OfflineSubtitleTooLargeException(maxBytes)
    }
}

/** Copies an untrusted subtitle response without allowing an unknown-length body to fill storage. */
internal suspend fun copyOfflineSubtitleBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = MAX_OFFLINE_SUBTITLE_BYTES,
    isCurrent: () -> Boolean = { true },
): Long? {
    require(maxBytes >= 0L) { "字幕大小上限无效" }
    var writtenBytes = 0L
    val buffer = ByteArray(32 * 1024)
    while (true) {
        if (!isCurrent()) return null
        val read = readOfflineChunk(input, buffer)
        if (read < 0) return writtenBytes
        if (!isCurrent()) return null
        if (read.toLong() > maxBytes - writtenBytes) {
            throw OfflineSubtitleTooLargeException(maxBytes)
        }
        offlineStorageWrite { output.write(buffer, 0, read) }
        writtenBytes += read
    }
}

internal fun offlineArtifactPrefix(value: String) =
    value.encodeToByteArray().joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

/** Exact artifact names that may still be referenced after process-death recovery. */
internal fun retainedOfflineArtifactNames(
    directory: File,
    items: List<OfflineMedia>,
): Set<String> {
    val root = directory.absoluteFile
    return buildSet {
        items.forEach { item ->
            val prefix = offlineArtifactPrefix(item.id)
            add("$prefix.part")
            add("$prefix.media")
            add("$prefix.srt")
            add("$prefix.${item.downloadRevision}.media")
            add("$prefix.${item.downloadRevision}.subtitle.part")

            // Re-enqueueing an already-completed variant advances the generation while retaining
            // the verified file. Preserve that exact indexed path as well as the current target.
            listOfNotNull(item.localPath, item.subtitlePath).forEach { path ->
                val indexed = File(path).absoluteFile
                if (indexed.parentFile == root) add(indexed.name)
            }
        }
    }
}

internal fun cleanupOrphanedOfflineArtifacts(
    directory: File,
    items: List<OfflineMedia>,
) {
    val retainedNames = retainedOfflineArtifactNames(directory, items)
    directory
        .listFiles()
        ?.filter(File::isFile)
        ?.filter { it.name !in retainedNames && isOfflineArtifactName(it.name) }
        ?.forEach(File::delete)
}

/**
 * Publishes the selected subtitle and creates the completed index value while the manager's
 * index lock is held by the caller. The revision-specific subtitle part means a superseded request
 * can never overwrite the sidecar prepared by its replacement.
 */
internal fun publishOfflineCompletionLocked(
    current: OfflineMedia?,
    snapshot: OfflineMedia,
    videoTarget: File,
    subtitlePart: File?,
    subtitleTarget: File,
    nowMs: Long,
): OfflineMedia? =
    publishOfflineCompletionLocked(
        current = current,
        snapshot = snapshot,
        videoPath = videoTarget.absolutePath,
        videoBytes = videoTarget.takeIf(File::isFile)?.length() ?: 0L,
        subtitlePart = subtitlePart,
        subtitleTarget = subtitleTarget,
        nowMs = nowMs,
    )

internal fun publishOfflineCompletionLocked(
    current: OfflineMedia?,
    snapshot: OfflineMedia,
    videoPath: String,
    videoBytes: Long,
    subtitlePart: File?,
    subtitleTarget: File,
    nowMs: Long,
): OfflineMedia? {
    if (
        current == null ||
        current.downloadRevision != snapshot.downloadRevision ||
        current.status != DownloadStatus.Downloading
    ) {
        subtitlePart?.delete()
        return null
    }
    if (videoPath.isBlank() || videoBytes <= 0L) throw OfflineStorageException("离线视频文件不存在")

    val publishedSubtitle =
        subtitlePart
            ?.takeIf(File::isFile)
            ?.let { prepared ->
                runCatching {
                    offlineStorageWrite {
                        Files.move(
                            prepared.toPath(),
                            subtitleTarget.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    subtitleTarget.takeIf(File::isFile)
                }.onFailure { error ->
                    prepared.delete()
                    subtitleTarget.delete()
                    AppLog.warning(
                        category = "offline",
                        event = "subtitle_publish_failed",
                        message = "Video completed but the selected subtitle could not be published",
                        throwable = error,
                        attributes = mapOf("itemId" to snapshot.itemId),
                    )
                }.getOrNull()
            }
    if (publishedSubtitle == null) subtitleTarget.delete()

    return current.copy(
        status = DownloadStatus.Completed,
        localPath = videoPath,
        subtitlePath = publishedSubtitle?.absolutePath,
        downloadedBytes = videoBytes,
        totalBytes = videoBytes,
        resumeValidator = null,
        error =
            if (snapshot.subtitleStreamIndex != null && publishedSubtitle == null) {
                "视频已完成，但所选字幕未能保存"
            } else {
                null
            },
        retryCount = 0,
        nextRetryAt = 0L,
        lastFailureKind = null,
        updatedAtEpochMs = nowMs,
    )
}

internal fun offlinePathExists(path: String): Boolean =
    if (path.startsWith("content://")) {
        runCatching {
            offlineApplicationContext.contentResolver
                .openAssetFileDescriptor(Uri.parse(path), "r")
                ?.use { it.length != 0L } == true
        }.getOrDefault(false)
    } else {
        File(path).isFile
    }

internal fun deleteOfflinePath(path: String): Boolean =
    if (path.startsWith("content://")) {
        runCatching {
            DocumentsContract.deleteDocument(
                offlineApplicationContext.contentResolver,
                Uri.parse(path),
            )
        }.getOrDefault(false)
    } else {
        !File(path).exists() || File(path).delete()
    }

internal fun publishOfflineFileToTree(
    context: Context,
    treeUri: String,
    source: File,
    displayName: String,
    mimeType: String,
): String {
    if (!source.isFile) throw OfflineStorageException("待保存的离线文件不存在")
    val resolver = context.contentResolver
    val tree = Uri.parse(treeUri)
    val root = offlineTreeRoot(tree)
    val temporaryName = "$displayName.${System.currentTimeMillis()}.downloading"
    findOfflineTreeDocument(context, tree, temporaryName)?.let {
        runCatching { DocumentsContract.deleteDocument(resolver, it) }
    }
    val temporary =
        runCatching {
            DocumentsContract.createDocument(resolver, root, mimeType, temporaryName)
        }.getOrNull() ?: throw OfflineStorageException("无法在所选目录创建离线文件")
    try {
        val output =
            resolver.openOutputStream(temporary, "wt")
                ?: throw OfflineStorageException("无法写入所选下载目录")
        source.inputStream().use { input ->
            output.use { destination -> input.copyTo(destination, 128 * 1024) }
        }
        findOfflineTreeDocument(context, tree, displayName)?.let {
            runCatching { DocumentsContract.deleteDocument(resolver, it) }
                .getOrElse { error -> throw OfflineStorageException("无法替换所选目录中的旧文件", error) }
        }
        val published =
            DocumentsContract.renameDocument(resolver, temporary, displayName)
                ?: temporary
        return published.toString()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
        if (error is OfflineStorageException) throw error
        throw OfflineStorageException("无法保存到所选下载目录", error)
    }
}

/**
 * Owns artifact names and filesystem operations. The manager retains the index lock and invokes
 * deletion inside the same critical sections as before; this class does not schedule any work.
 */
internal class OfflineArtifactStore(
    private val context: Context,
    private val directory: File,
) {
    fun delete(item: OfflineMedia): Boolean {
        val indexedPaths = listOfNotNull(item.localPath, item.subtitlePath)
        indexedPaths.forEach(::deleteOfflinePath)
        val videoTarget = offlineVideoTarget(context, directory, item)
        videoTarget.deletePartial()
        videoTarget.published()?.path?.let(::deleteOfflinePath)
        val artifacts =
            listOfNotNull(
                completedFile(item),
                legacyCompletedFile(item.id),
                partFile(item),
                subtitleFile(item.id),
            ).distinct()
        artifacts.forEach { artifact ->
            if (artifact.exists() && !artifact.delete()) {
                AppLog.warning(
                    category = "offline",
                    event = "offline_artifact_delete_failed",
                    message = "Offline artifact could not be deleted; retaining its index entry",
                    attributes = mapOf("itemId" to item.itemId),
                )
            }
        }
        deleteSubtitlePartFiles(item.id)
        return indexedPaths.none(::offlinePathExists) &&
            videoTarget.partialSize() == 0L &&
            videoTarget.published() == null &&
            artifacts.none(File::exists) &&
            directory.listFiles()?.none { candidate ->
                candidate.name.startsWith(safeFileName(item.id) + ".") &&
                    candidate.name.endsWith(".subtitle.part")
            } != false
    }

    private fun partFile(item: OfflineMedia) = File(directory, safeFileName(item.id) + ".part")

    // Video publication has a revision-specific name. A cancelled or superseded finalization
    // therefore cannot be mistaken for the replacement's source on a later recovery.
    fun completedFile(item: OfflineMedia) = File(directory, "${safeFileName(item.id)}.${item.downloadRevision}.media")

    // v1/v2 used an id-only target. It is never selected for a new download, but removal still
    // clears it so upgraded installs cannot retain an old artifact indefinitely.
    fun legacyCompletedFile(id: String) = File(directory, safeFileName(id) + ".media")

    fun subtitleFile(id: String) = File(directory, safeFileName(id) + ".srt")

    fun subtitlePartFile(item: OfflineMedia) =
        File(directory, "${safeFileName(item.id)}.${item.downloadRevision}.subtitle.part")

    fun deleteSubtitlePartFiles(id: String) {
        val prefix = safeFileName(id) + "."
        directory.listFiles()?.forEach { candidate ->
            if (candidate.name.startsWith(prefix) && candidate.name.endsWith(".subtitle.part")) {
                candidate.delete()
            }
        }
    }

    private fun safeFileName(value: String) = offlineArtifactPrefix(value)
}
