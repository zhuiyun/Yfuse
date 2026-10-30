package com.yfuse.core.logging

import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** Finish and verify the archive before opening the user-selected document. */
internal fun exportVerifiedDiagnostics(
    cacheDirectory: File,
    writeArchive: (OutputStream) -> Unit,
    openDestination: () -> OutputStream,
) {
    val staged = File.createTempFile("diagnostics-", ".zip", cacheDirectory)
    try {
        staged.outputStream().use(writeArchive)
        ZipFile(staged).use { zip ->
            check(zip.size() > 0) { "诊断包为空" }
            val buffer = ByteArray(8192)
            zip.entries().asSequence().forEach { entry ->
                val crc = CRC32()
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        crc.update(buffer, 0, count)
                        size += count
                    }
                }
                check(size == entry.size && crc.value == entry.crc) { "诊断包校验失败" }
            }
        }
        openDestination().use { output -> staged.inputStream().use { it.copyTo(output) } }
    } finally {
        staged.delete()
    }
}
