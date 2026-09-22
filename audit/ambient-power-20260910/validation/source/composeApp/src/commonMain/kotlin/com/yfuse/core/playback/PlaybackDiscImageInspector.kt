package com.yfuse.core.playback

/**
 * Identifies the filesystem carried by a disc image without retaining its path or payload.
 *
 * A DVD image is ISO9660, which stores directory identifiers as 8-bit characters. A Blu-ray
 * image is UDF, whose file identifiers are OSTA Compressed Unicode: a leading compression id
 * of 8 means one byte per character, and 16 means two — and which one a mastering tool wrote
 * is not something the reader gets to choose. `INDEX.BDMV` therefore appears on disc either
 * as ASCII or as UTF-16, and a scan that only knows the first silently fails to recognise
 * every image that used the second. Both forms are matched here, in both byte orders.
 *
 * Feed the image through [PlaybackDiscImageScanner] rather than reading a prefix into one
 * array: an image is measured in gigabytes, the caller's ceiling is megabytes, and the
 * scanner settles as soon as a marker appears instead of reading to that ceiling every time.
 */
fun detectPlaybackDiscImageKind(sample: ByteArray): PlaybackDiscKind =
    PlaybackDiscImageScanner().apply { accept(sample) }.kind

/**
 * Incremental marker search over a disc image read in chunks.
 *
 * Chunks are matched with the tail of the previous one carried over, so a marker split
 * across a read boundary is still found. Nothing larger than one chunk plus [OVERLAP_BYTES]
 * is ever held.
 */
class PlaybackDiscImageScanner {
    private var carry: ByteArray = EMPTY
    private var decided: PlaybackDiscKind? = null

    /** True once a marker has been recognised and further reading cannot change the answer. */
    val settled: Boolean get() = decided != null

    /** [PlaybackDiscKind.Iso] until a DVD or Blu-ray layout is recognised. */
    val kind: PlaybackDiscKind get() = decided ?: PlaybackDiscKind.Iso

    /**
     * Matches [length] bytes of [chunk]. Returns [settled] so a read loop can stop early.
     */
    fun accept(
        chunk: ByteArray,
        length: Int = chunk.size,
    ): Boolean {
        if (decided != null) return true
        val usable = length.coerceIn(0, chunk.size)
        if (usable == 0) return false
        val window =
            if (carry.isEmpty()) {
                if (usable == chunk.size) chunk else chunk.copyOf(usable)
            } else {
                ByteArray(carry.size + usable).also {
                    carry.copyInto(it)
                    chunk.copyInto(it, carry.size, 0, usable)
                }
            }
        decided =
            when {
                BLU_RAY_MARKERS.any { window.contains(it) } -> PlaybackDiscKind.BluRay
                DVD_MARKERS.any { window.contains(it) } -> PlaybackDiscKind.Dvd
                else -> null
            }
        // Everything still short enough to be the start of a name is carried, which for a
        // chunk smaller than a marker is the whole chunk — dropping it there would lose a
        // name split across two short reads.
        carry =
            if (decided != null) {
                EMPTY
            } else {
                window.copyOfRange(maxOf(0, window.size - OVERLAP_BYTES), window.size)
            }
        return decided != null
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/** Pre-uppercased so the search only has to fold the bytes it is reading. */
private class DiscMarker(
    val upperCased: ByteArray,
)

private fun ByteArray.contains(marker: DiscMarker): Boolean {
    val target = marker.upperCased
    if (target.isEmpty() || size < target.size) return false
    val first = target[0]
    val last = size - target.size
    for (offset in 0..last) {
        if (this[offset].asciiUppercase() != first) continue
        var matched = true
        for (index in 1 until target.size) {
            if (this[offset + index].asciiUppercase() != target[index]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}

private fun Byte.asciiUppercase(): Byte =
    if (this in 'a'.code.toByte()..'z'.code.toByte()) {
        (this - ('a'.code - 'A'.code)).toByte()
    } else {
        this
    }

/** ASCII, UTF-16LE and UTF-16BE forms of one on-disc name. */
private fun discMarkers(name: String): List<DiscMarker> {
    val upper = name.uppercase()
    val ascii = ByteArray(upper.length) { upper[it].code.toByte() }
    val littleEndian = ByteArray(upper.length * 2) { if (it % 2 == 0) upper[it / 2].code.toByte() else 0 }
    val bigEndian = ByteArray(upper.length * 2) { if (it % 2 == 0) 0 else upper[it / 2].code.toByte() }
    return listOf(DiscMarker(ascii), DiscMarker(littleEndian), DiscMarker(bigEndian))
}

private val BLU_RAY_MARKERS =
    listOf(
        "INDEX.BDMV",
        "MOVIEOBJECT.BDMV",
        "BDMV/PLAYLIST",
        "BDMV\\PLAYLIST",
        "BDMV/CLIPINF",
        "BDMV\\CLIPINF",
    ).flatMap(::discMarkers)

private val DVD_MARKERS =
    listOf(
        "VIDEO_TS.IFO",
        "VIDEO_TS/VTS_",
        "VIDEO_TS\\VTS_",
        "DVDVIDEO-VMG",
    ).flatMap(::discMarkers)

/**
 * One byte short of the longest encoded marker, which is all that is needed to catch a name
 * straddling two reads.
 */
private val OVERLAP_BYTES =
    (BLU_RAY_MARKERS + DVD_MARKERS).maxOf { it.upperCased.size } - 1
