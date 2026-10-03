package com.yfuse.core.designsystem

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/**
 * [BlurHash](https://blurha.sh) decoding: the twenty-odd characters Jellyfin sends with every
 * item in place of the picture itself, from which a tile can show its artwork's colours and
 * rough shapes while the artwork is still on the way.
 *
 * Plain Kotlin rather than a library: the format is a few cosine terms in base 83, and the only
 * work is a 32 × 32 decode that runs off the main thread. Anything that is not a well-formed hash
 * decodes to null instead of throwing — the field comes from a server, and the worst a bad one
 * may cost is the placeholder.
 */
internal object BlurHash {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
    private const val MAX_COMPONENTS = 9
    private const val OPAQUE = 0xFF000000.toInt()

    /** Whether [hash] has a length that matches the component count it declares. */
    fun isValid(hash: String): Boolean = components(hash) != null

    /**
     * The hash's average colour as opaque ARGB — its first term, read without decoding anything.
     * It stands in for the picture on the frame a tile appears, before the decode has come back.
     */
    fun averageColor(hash: String): Int? {
        if (components(hash) == null) return null
        val value = decode83(hash, 2, 6) ?: return null
        return OPAQUE or value
    }

    /**
     * Decodes [hash] to [width] × [height] opaque ARGB pixels, row by row, or null when it is not
     * a well-formed hash. [punch] strengthens the contrast between the terms; 1 is as encoded.
     */
    fun decode(
        hash: String,
        width: Int,
        height: Int,
        punch: Float = 1f,
    ): IntArray? {
        if (width <= 0 || height <= 0) return null
        val (numX, numY) = components(hash) ?: return null
        val quantisedMaximum = decode83(hash, 1, 2) ?: return null
        val maximum = (quantisedMaximum + 1) / 166f * punch.coerceAtLeast(1f)

        val count = numX * numY
        val colours = FloatArray(count * 3)
        val average = decode83(hash, 2, 6) ?: return null
        colours[0] = srgbToLinear(average shr 16)
        colours[1] = srgbToLinear((average shr 8) and 0xFF)
        colours[2] = srgbToLinear(average and 0xFF)
        for (index in 1 until count) {
            val value = decode83(hash, 4 + index * 2, 6 + index * 2) ?: return null
            colours[index * 3] = signedSquare((value / (19 * 19) - 9) / 9f) * maximum
            colours[index * 3 + 1] = signedSquare(((value / 19) % 19 - 9) / 9f) * maximum
            colours[index * 3 + 2] = signedSquare((value % 19 - 9) / 9f) * maximum
        }

        // The basis is separable, so each axis's cosines are worked out once rather than per pixel.
        val cosX = FloatArray(width * numX) { cos(PI * (it / numX) * (it % numX) / width).toFloat() }
        val cosY = FloatArray(height * numY) { cos(PI * (it / numY) * (it % numY) / height).toFloat() }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var red = 0f
                var green = 0f
                var blue = 0f
                for (j in 0 until numY) {
                    val rowBasis = cosY[y * numY + j]
                    for (i in 0 until numX) {
                        val basis = cosX[x * numX + i] * rowBasis
                        val colour = (j * numX + i) * 3
                        red += colours[colour] * basis
                        green += colours[colour + 1] * basis
                        blue += colours[colour + 2] * basis
                    }
                }
                pixels[y * width + x] = opaque(linearToSrgb(red), linearToSrgb(green), linearToSrgb(blue))
            }
        }
        return pixels
    }

    private fun opaque(
        red: Int,
        green: Int,
        blue: Int,
    ): Int = OPAQUE or (red shl 16) or (green shl 8) or blue

    /** Components along x and y, or null when [hash] is not as long as it says it is. */
    private fun components(hash: String): Pair<Int, Int>? {
        if (hash.length < 6 || hash.any { ALPHABET.indexOf(it) < 0 }) return null
        val sizeFlag = decode83(hash, 0, 1) ?: return null
        val numX = sizeFlag % 9 + 1
        val numY = sizeFlag / 9 + 1
        if (numY > MAX_COMPONENTS || hash.length != 4 + 2 * numX * numY) return null
        return numX to numY
    }

    private fun decode83(
        text: String,
        from: Int,
        to: Int,
    ): Int? {
        var value = 0
        for (index in from until to) {
            val digit = ALPHABET.indexOf(text[index])
            if (digit < 0) return null
            value = value * 83 + digit
        }
        return value
    }

    private fun srgbToLinear(value: Int): Float {
        val encoded = value / 255f
        return if (encoded <= 0.04045f) encoded / 12.92f else ((encoded + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Int {
        val linear = value.coerceIn(0f, 1f)
        val encoded = if (linear <= 0.0031308f) linear * 12.92f else 1.055f * linear.pow(1f / 2.4f) - 0.055f
        return (encoded * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun signedSquare(value: Float): Float = (abs(value) * abs(value)).withSign(value)
}
