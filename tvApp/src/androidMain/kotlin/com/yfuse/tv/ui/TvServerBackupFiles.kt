package com.yfuse.tv.ui

import com.yfuse.core.migration.MigrationRelayApi
import com.yfuse.feature.profile.ProfileComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** The largest supported encrypted v3 envelope is 768 KiB of ASCII JSON. */
internal const val MAX_TV_BACKUP_BYTES = 768 * 1024

internal data class TvBackupFile(
    val file: File,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

internal data class TvSelectedBackup(
    val file: File,
    val payload: String,
    val isRelay: Boolean,
)

internal suspend fun listTvBackupFiles(directory: File): List<TvBackupFile> =
    withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建备份目录" }
        directory
            .listFiles()
            ?.filter(File::isFile)
            ?.map { TvBackupFile(it, it.length(), it.lastModified()) }
            ?.filter { it.sizeBytes > 0L }
            ?.sortedByDescending(TvBackupFile::modifiedAt)
            ?: error("无法读取备份目录")
    }

internal suspend fun loadTvBackupFile(
    file: File,
    isRelay: (String) -> Boolean,
): TvSelectedBackup =
    withContext(Dispatchers.IO) {
        require(file.length() in 1..MAX_TV_BACKUP_BYTES.toLong()) { "备份文件须为 1 字节至 768 KB" }
        val context = currentCoroutineContext()
        val payload = file.inputStream().use { it.readTvBackupText(checkCancelled = context::ensureActive) }
        context.ensureActive()
        TvSelectedBackup(file, payload, isRelay(payload))
    }

/** Checks the bytes actually read, so a growing file cannot bypass the preliminary size check. */
internal fun InputStream.readTvBackupText(
    limit: Int = MAX_TV_BACKUP_BYTES,
    checkCancelled: () -> Unit = {},
): String {
    require(limit in 1..MAX_TV_BACKUP_BYTES)
    val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(minOf(limit + 1, DEFAULT_BUFFER_SIZE))
    while (true) {
        checkCancelled()
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
        if (count < 0) break
        require(count <= limit - output.size()) { "备份文件超过 768 KB" }
        output.write(buffer, 0, count)
    }
    require(output.size() > 0) { "备份文件为空" }
    return output.toString(Charsets.UTF_8.name())
}

internal suspend fun importTvBackup(
    backup: TvSelectedBackup,
    passphrase: String,
    component: ProfileComponent,
    relayApi: MigrationRelayApi,
): Int {
    if (backup.isRelay) {
        require(passphrase.length == 6 && passphrase.all { it in '0'..'9' }) { "请输入 6 位数字迁移码" }
        val descriptor = withContext(Dispatchers.Default) { component.inspectRelayServers(backup.payload) }
        val secret = relayApi.redeem(descriptor.relayId, passphrase, descriptor.payloadSha256)
        try {
            return withContext(Dispatchers.Default) {
                component.importRelayServers(backup.payload, secret, System.currentTimeMillis() / 1_000L).getOrThrow()
            }
        } finally {
            secret.fill(0)
        }
    }
    val secret = passphrase.toCharArray()
    try {
        return withContext(Dispatchers.Default) {
            component.importServers(backup.payload, secret, System.currentTimeMillis() / 1_000L).getOrThrow()
        }
    } finally {
        secret.fill('\u0000')
    }
}
