package com.yfuse.core.playback

/**
 * Identifies the filesystem carried by a disc image without retaining its path or payload.
 *
 * ISO9660/UDF directory descriptors contain these ASCII names. Callers intentionally provide a
 * bounded prefix so a multi-gigabyte image never needs to be copied or fully scanned.
 */
fun detectPlaybackDiscImageKind(sample: ByteArray): PlaybackDiscKind {
    return when {
        BLU_RAY_IMAGE_MARKERS.any(sample::containsAsciiIgnoreCase) -> PlaybackDiscKind.BluRay
        DVD_IMAGE_MARKERS.any(sample::containsAsciiIgnoreCase) -> PlaybackDiscKind.Dvd
        else -> PlaybackDiscKind.Iso
    }
}

private fun ByteArray.containsAsciiIgnoreCase(marker: String): Boolean {
    val target = marker.encodeToByteArray()
    if (target.isEmpty() || size < target.size) return false
    for (offset in 0..size - target.size) {
        var matched = true
        for (index in target.indices) {
            if (this[offset + index].asciiUppercase() != target[index].asciiUppercase()) {
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

private val BLU_RAY_IMAGE_MARKERS =
    listOf(
        "INDEX.BDMV",
        "MOVIEOBJECT.BDMV",
        "BDMV/PLAYLIST",
        "BDMV\\PLAYLIST",
        "BDMV/CLIPINF",
        "BDMV\\CLIPINF",
    )

private val DVD_IMAGE_MARKERS =
    listOf(
        "VIDEO_TS.IFO",
        "VIDEO_TS/VTS_",
        "VIDEO_TS\\VTS_",
        "DVDVIDEO-VMG",
    )
