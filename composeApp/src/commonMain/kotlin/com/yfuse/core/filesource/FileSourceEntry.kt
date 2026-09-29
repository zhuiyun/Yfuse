package com.yfuse.core.filesource

/** One row of a folder listing, protocol-neutral. */
data class FileSourceEntry(
    /** The decoded name, exactly as the share spells it; path segments are built from it. */
    val name: String,
    val directory: Boolean,
    val sizeBytes: Long? = null,
    val modifiedEpochMs: Long? = null,
) {
    val extension: String
        get() = if (directory) "" else fileExtension(name)

    val isVideo: Boolean get() = !directory && extension in VIDEO_EXTENSIONS

    val isSubtitle: Boolean get() = !directory && extension in SUBTITLE_EXTENSIONS

    /** An ISO is played as a disc image, not as an ordinary file; see `fileSourcePlaybackItem`. */
    val isDiscImage: Boolean get() = !directory && extension == "iso"
}

/** Lower-case extension without the dot; empty when the name has none. */
fun fileExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return ""
    return name.substring(dot + 1).lowercase()
}

/** `Movie.2019.1080p.mkv` → `Movie.2019.1080p`. */
fun fileBaseName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name else name.substring(0, dot)
}

/**
 * Files no one browses for: dot files, the NAS vendors' thumbnail and recycle folders, and the
 * Windows system folders that show up on every SMB share of a system disk.
 */
fun isHiddenFileSourceName(name: String): Boolean =
    name.startsWith('.') ||
        name.startsWith("@ea", ignoreCase = true) ||
        name.lowercase() in HIDDEN_NAMES ||
        name.endsWith('$')

/**
 * What a folder shows: folders first, then videos, each in natural order so `第2集` sorts before
 * `第10集`. Subtitles are not listed on their own — they are paired with their video — and
 * nothing else is listed at all: this is a player, not a file manager.
 */
fun List<FileSourceEntry>.browsable(): List<FileSourceEntry> =
    filter { !isHiddenFileSourceName(it.name) && (it.directory || it.isVideo) }
        .sortedWith(
            compareByDescending<FileSourceEntry> { it.directory }
                .thenComparator { left, right -> compareNatural(left.name, right.name) },
        )

/**
 * Compares names the way people number files: digit runs by value, everything else
 * case-insensitively. Equal values fall back to the plain comparison so the order is total.
 */
fun compareNatural(
    left: String,
    right: String,
): Int {
    var i = 0
    var j = 0
    while (i < left.length && j < right.length) {
        val a = left[i]
        val b = right[j]
        if (a.isAsciiDigit() && b.isAsciiDigit()) {
            val startA = i
            val startB = j
            while (i < left.length && left[i].isAsciiDigit()) i++
            while (j < right.length && right[j].isAsciiDigit()) j++
            val numberA = left.substring(startA, i).trimStart('0')
            val numberB = right.substring(startB, j).trimStart('0')
            if (numberA.length != numberB.length) return numberA.length - numberB.length
            val byValue = numberA.compareTo(numberB)
            if (byValue != 0) return byValue
        } else {
            val byChar = a.lowercaseChar().compareTo(b.lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    val byLength = (left.length - i) - (right.length - j)
    return if (byLength != 0) byLength else left.compareTo(right)
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/**
 * What YCore's demuxers open. `rmvb`/`rm` are kept because they are still common on older
 * Chinese shares and the FFmpeg route reads them; `strm` is not, since it is a pointer file whose
 * target would be fetched without the share's credentials.
 */
val VIDEO_EXTENSIONS: Set<String> =
    setOf(
        "mkv",
        "mp4",
        "m4v",
        "mov",
        "avi",
        "wmv",
        "asf",
        "flv",
        "webm",
        "ts",
        "m2ts",
        "mts",
        "m2t",
        "tp",
        "trp",
        "mpg",
        "mpeg",
        "mpe",
        "vob",
        "rmvb",
        "rm",
        "3gp",
        "ogv",
        "divx",
        "iso",
    )

/** Text sidecars the player's loaders read; bitmap `sub/idx` pairs are not paired. */
val SUBTITLE_EXTENSIONS: Set<String> = setOf("srt", "ass", "ssa", "vtt")

private val HIDDEN_NAMES =
    setOf(
        "#recycle",
        "#snapshot",
        "\$recycle.bin",
        "system volume information",
        "lost+found",
        "thumbs.db",
        "desktop.ini",
        "@recycle",
        "@recently-snapshot",
        ".@__thumb",
    )
