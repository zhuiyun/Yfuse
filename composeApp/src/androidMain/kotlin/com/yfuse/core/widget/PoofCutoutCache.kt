package com.yfuse.core.widget

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal const val POOF_CACHE_VERSION = "poof-png-v1"
internal const val POOF_CACHE_BYTES = 128L * 1024 * 1024
private const val POOF_CACHE_ENTRIES = 256
internal const val POOF_TRANSIENT_TTL = 15L * 60 * 1000
internal const val POOF_QUALITY_TTL = 30L * 24 * 60 * 60 * 1000

internal data class PoofCachedArtwork(
    val background: File,
    val foreground: File,
    val width: Int,
    val height: Int,
    val top: Float,
    val bottom: Float,
)

/** Persistent, content-addressed pairs. Only a complete metadata commit makes a pair readable. */
internal class PoofCutoutCache(
    val directory: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val budget: Long = POOF_CACHE_BYTES,
    private val imageBoundsValid: (File, Int, Int) -> Boolean,
) {
    fun findSource(key: String): PoofCachedArtwork? {
        val reference = file(key, "ref")
        if (!reference.isFile || reference.length() > 80) return null
        val content = reference.readText()
        if (!content.matches(KEY_PATTERN)) return null
        val result = findContent(content)
        if (result == null) reference.delete() else reference.setLastModified(now())
        return result
    }

    fun findContent(key: String): PoofCachedArtwork? {
        val meta = file(key, "meta")
        if (!meta.isFile || meta.length() > 1024) return null
        val data = meta.readLines()
        if (data.size != 7 || data[0] != POOF_CACHE_VERSION) return null
        val width = data[1].toIntOrNull() ?: return null
        val height = data[2].toIntOrNull() ?: return null
        val top = data[3].toFloatOrNull() ?: return null
        val bottom = data[4].toFloatOrNull() ?: return null
        if (width !in 96..768 ||
            height !in 96..768 ||
            !top.isFinite() ||
            !bottom.isFinite() ||
            top < 0f ||
            bottom > 1f ||
            top >= bottom
        ) {
            return null
        }
        val background = file(key, "background.png")
        val foreground = file(key, "foreground.png")
        for ((image, hash) in listOf(background to data[5], foreground to data[6])) {
            if (!image.isFile || image.length() !in 1..MAX_POOF_IMAGE_BYTES.toLong()) return null
            if (!imageBoundsValid(image, width, height) || poofHash(image.readBytes()) != hash) return null
        }
        meta.setLastModified(now())
        return PoofCachedArtwork(background, foreground, width, height, top, bottom)
    }

    fun bindSource(
        source: String,
        content: String,
    ) {
        require(content.matches(KEY_PATTERN))
        atomicWrite(file(source, "ref"), content.toByteArray())
        trim()
    }

    fun isRejected(
        key: String,
        credential: String,
    ): Boolean {
        val marker = file(key, "reject")
        if (!marker.isFile || marker.length() > 128) return false
        val parts = marker.readLines()
        val until = parts.firstOrNull()?.toLongOrNull() ?: return false
        val matches = parts.getOrNull(1) in setOf("*", credential)
        return (until > now() && until - now() <= POOF_QUALITY_TTL && matches).also {
            if (!it) marker.delete()
        }
    }

    fun reject(
        key: String,
        ttl: Long,
        credential: String = "*",
    ) {
        atomicWrite(file(key, "reject"), "${now() + ttl.coerceIn(1, POOF_QUALITY_TTL)}\n$credential".toByteArray())
        trim()
    }

    fun write(
        source: String,
        content: String,
        background: ByteArray,
        foreground: ByteArray,
        width: Int,
        height: Int,
        quality: WidgetCutoutQuality,
    ): PoofCachedArtwork? {
        require(background.size <= MAX_POOF_IMAGE_BYTES && foreground.size <= MAX_POOF_IMAGE_BYTES)
        atomicWrite(file(content, "background.png"), background)
        atomicWrite(file(content, "foreground.png"), foreground)
        atomicWrite(
            file(content, "meta"),
            listOf(
                POOF_CACHE_VERSION,
                width,
                height,
                quality.top,
                quality.bottom,
                poofHash(background),
                poofHash(foreground),
            ).joinToString("\n")
                .toByteArray(),
        )
        file(source, "reject").delete()
        file(content, "reject").delete()
        bindSource(source, content)
        return findContent(content)
    }

    fun trim() {
        val files = directory.listFiles()?.filter(File::isFile) ?: return
        files.filter { it.name.endsWith(".part") }.forEach(File::delete)
        val pairs = files.filter { it.extension == "meta" }.sortedBy(File::lastModified)
        var bytes = files.sumOf(File::length)
        var count = pairs.size
        for (meta in pairs) {
            if (bytes <= budget && count <= POOF_CACHE_ENTRIES) break
            val key = meta.name.substringBefore('.')
            for (item in files.filter { it.name.startsWith("$key.") && it.extension != "ref" }) {
                val length = item.length()
                if (item.delete()) bytes -= length
            }
            count--
        }
        // Failure records and URL aliases also have a hard count bound.
        val references = files.filter { it.extension == "ref" || it.extension == "reject" }.sortedBy(File::lastModified)
        references.take((references.size - 512).coerceAtLeast(0)).forEach(File::delete)
        val known =
            directory
                .listFiles()
                .orEmpty()
                .filter {
                    it.extension == "meta"
                }.map { it.name.substringBefore('.') }
                .toSet()
        directory
            .listFiles()
            .orEmpty()
            .filter {
                it.name.endsWith(".png") && it.name.substringBefore('.') !in known
            }.forEach(File::delete)
    }

    private fun file(
        key: String,
        extension: String,
    ): File {
        require(key.matches(KEY_PATTERN))
        return File(directory, "$key.$extension")
    }

    private fun atomicWrite(
        target: File,
        bytes: ByteArray,
    ) {
        check(directory.isDirectory || directory.mkdirs())
        val pending = File(directory, target.name + ".part")
        try {
            pending.outputStream().use { it.write(bytes) }
            Files.move(
                pending.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            pending.delete()
        }
    }
}

internal fun poofHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

private val KEY_PATTERN = Regex("[0-9a-f]{64}")
