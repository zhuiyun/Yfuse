package com.yfuse.core2.hdr

/** Per-picture SMPTE ST 2094-40 values used by the owned Vulkan tone mapper. */
data class YHdr10PlusSceneMetadata(
    val targetedDisplayMaximumNits: Float,
    val maxSclNits: List<Float>,
    val averageMaxRgbNits: Float,
    val kneePointX: Float? = null,
    val kneePointY: Float? = null,
    val bezierAnchors: List<Float> = emptyList(),
) {
    val scenePeakNits: Float = maxSclNits.maxOrNull()?.coerceAtLeast(averageMaxRgbNits) ?: averageMaxRgbNits
}

/** Fail-closed parser for the registered ITU-T T.35 HDR10+ payload carried by HEVC SEI. */
object YHdr10PlusParser {
    fun parse(payload: ByteArray): YHdr10PlusSceneMetadata? =
        runCatching {
            if (payload.size < IDENTIFIER.size + 2 || !IDENTIFIER.indices.all { payload[it] == IDENTIFIER[it] }) {
                return null
            }
            val bits = BitReader(payload, IDENTIFIER.size * 8)
            bits.read(8) // application_version
            val windows = bits.read(2)
            require(windows in 1..3)
            repeat(windows - 1) { bits.skip(153) }
            val targetNits = bits.read(27).toFloat()
            if (bits.read(1) != 0) bits.skipPeakLuminanceGrid()

            var firstMaxScl = emptyList<Float>()
            var firstAverage = 0f
            repeat(windows) { window ->
                val maxScl = List(3) { bits.read(17) / 10f }
                val average = bits.read(17) / 10f
                val distributions = bits.read(4)
                repeat(distributions) {
                    bits.skip(7)
                    bits.skip(17)
                }
                bits.skip(10) // fraction_bright_pixels
                if (window == 0) {
                    firstMaxScl = maxScl
                    firstAverage = average
                }
            }
            if (bits.read(1) != 0) bits.skipPeakLuminanceGrid()

            var firstKneeX: Float? = null
            var firstKneeY: Float? = null
            var firstAnchors = emptyList<Float>()
            repeat(windows) { window ->
                if (bits.read(1) != 0) {
                    val kneeX = bits.read(12) / 4095f
                    val kneeY = bits.read(12) / 4095f
                    val anchorCount = bits.read(4)
                    val anchors = List(anchorCount) { bits.read(10) / 1023f }
                    if (window == 0) {
                        firstKneeX = kneeX
                        firstKneeY = kneeY
                        firstAnchors = anchors
                    }
                }
                if (bits.read(1) != 0) bits.skip(6)
            }
            YHdr10PlusSceneMetadata(
                targetedDisplayMaximumNits = targetNits,
                maxSclNits = firstMaxScl,
                averageMaxRgbNits = firstAverage,
                kneePointX = firstKneeX,
                kneePointY = firstKneeY,
                bezierAnchors = firstAnchors,
            )
        }.getOrNull()

    private class BitReader(
        private val data: ByteArray,
        private var bitOffset: Int,
    ) {
        fun read(count: Int): Int {
            require(count in 0..30 && bitOffset + count <= data.size * 8)
            var value = 0
            repeat(count) {
                val byte = data[bitOffset ushr 3].toInt() and 0xff
                value = (value shl 1) or ((byte ushr (7 - (bitOffset and 7))) and 1)
                bitOffset++
            }
            return value
        }

        fun skip(count: Int) {
            require(count >= 0 && bitOffset + count <= data.size * 8)
            bitOffset += count
        }

        fun skipPeakLuminanceGrid() {
            val rows = read(5)
            val columns = read(5)
            require(rows in 2..25 && columns in 2..25)
            skip(rows * columns * 4)
        }
    }

    private val IDENTIFIER = byteArrayOf(0xb5.toByte(), 0x00, 0x3c, 0x00, 0x01, 0x04)
}
