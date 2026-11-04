package com.yfuse.feature.player

import com.yfuse.core2.subtitle.YSubtitleFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Private, bounded copies: no dependency on a document provider retaining a temporary grant. */
internal class ImportedSubtitleStore(
    filesDirectory: File,
    private val maximumBytes: Long = 128L * 1024L * 1024L,
) {
    private val root = File(filesDirectory, "imported-subtitles")

    fun load(owner: SubtitleItemKey): List<PlayerExternalSubtitle> =
        synchronized(lock) {
            files(owner).sortedBy { it.name }.map { file ->
                PlayerExternalSubtitle(file.toURI().toString(), language = "本地字幕", codec = file.extension)
            }
        }

    /** Called only after the real parser has verified at least one displayable cue. */
    fun save(
        owner: SubtitleItemKey,
        text: String,
        format: YSubtitleFormat,
        ensureCurrent: () -> Unit = {},
    ): PlayerExternalSubtitle =
        synchronized(lock) {
            ensureCurrent()
            require(format.standaloneTextSupported)
            val bytes = text.encodeToByteArray()
            require(bytes.size in 1..8 * 1024 * 1024) { "字幕文件不能超过 8 MB" }
            val extension =
                when (format) {
                    YSubtitleFormat.WebVtt -> "vtt"
                    YSubtitleFormat.MicroDvd -> "sub"
                    else -> format.name.lowercase()
                }
            val directory = directory(owner)
            val target = File(directory, "${digest(bytes)}.$extension")
            if (!target.isFile) {
                check(files(owner).size < 8) { "本片已保存 8 条字幕，请先清除本片已导入字幕。" }
                val used = root.walkTopDown().filter(File::isFile).sumOf(File::length)
                check(bytes.size <= maximumBytes - used) { "本地字幕已达到 128 MB，请清除不需要的已导入字幕。" }
                check(directory.mkdirs() || directory.isDirectory) { "无法创建字幕保存目录" }
                val pending = File.createTempFile("import-", ".tmp", directory)
                try {
                    pending.outputStream().use { it.write(bytes) }
                    ensureCurrent()
                    Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    pending.delete()
                }
            }
            PlayerExternalSubtitle(target.toURI().toString(), language = "本地字幕", codec = extension)
        }

    fun clear(owner: SubtitleItemKey) =
        synchronized(lock) {
            val directory = directory(owner)
            check(!directory.exists() || directory.deleteRecursively()) { "字幕未能删除，请检查存储后重试。" }
        }

    private fun files(owner: SubtitleItemKey): List<File> =
        directory(owner).listFiles().orEmpty().filter { it.isFile && fileName.matches(it.name) }

    private fun directory(owner: SubtitleItemKey): File {
        // Length prefixes and an explicit null marker prevent collisions across server/version ids.
        val identity =
            listOf(owner.serverId, owner.itemId, owner.versionId)
                .joinToString("") { value -> value?.let { "${it.length}:$it" } ?: "-1:" }
        return File(root, digest(identity.encodeToByteArray()))
    }

    private companion object {
        val lock = Any()
        val fileName = Regex("[0-9a-f]{64}\\.(srt|vtt|ass|ssa|smi|sub|ttml)")

        fun digest(bytes: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
