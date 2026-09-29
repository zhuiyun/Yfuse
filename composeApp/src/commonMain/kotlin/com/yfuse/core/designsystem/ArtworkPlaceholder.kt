package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * What stands in for a picture until it arrives (图片渐进加载 §3.1), best first:
 *
 * 1. Jellyfin's BlurHash for it — the picture's own colours and rough shapes, decoded off the
 *    main thread and kept for the next tile that shows the same artwork. For the frame or two
 *    before that decode is back, the hash's average colour.
 * 2. For Emby and Plex, which send no hash, the dominant colour already worked out for the same
 *    artwork somewhere else — see [ArtworkPlaceholderColors] — washed over the skeleton.
 * 3. Otherwise nothing, and the caller's own skeleton shows, as it always has.
 */
internal sealed interface ArtworkPlaceholder {
    class Picture(
        val image: ImageBitmap,
    ) : ArtworkPlaceholder

    data class Wash(
        val color: Color,
    ) : ArtworkPlaceholder
}

/**
 * The side of the square a hash is decoded to. A handful of cosine terms has no detail to lose:
 * stretched over a tile with bilinear filtering, 32 px is already as smooth as the hash is.
 */
internal const val BLUR_HASH_DECODE_SIZE = 32

/** How strongly a remembered colour tints the skeleton: it is the artwork's accent, not its average. */
private const val REMEMBERED_WASH_ALPHA = 0.5f

/**
 * The placeholder for a picture requested from [candidates], whose first URL [blurHash] belongs
 * to when the server sent one; null when there is nothing better than the caller's skeleton.
 *
 * Only a decode still to come changes it afterwards — its average colour becomes the decoded
 * hash — and that reaches the draw alone: the caller reads the state while drawing.
 */
@Composable
internal fun rememberArtworkPlaceholder(
    blurHash: String?,
    candidates: List<String>,
): State<ArtworkPlaceholder>? {
    val hash = remember(blurHash) { blurHash?.takeIf(BlurHash::isValid) }
    val placeholder =
        remember(hash, candidates) {
            artworkPlaceholderNow(hash, candidates)?.let { mutableStateOf(it) }
        } ?: return null
    if (hash != null) {
        LaunchedEffect(placeholder) {
            if (placeholder.value is ArtworkPlaceholder.Picture) return@LaunchedEffect
            BlurHashPictures.decode(hash)?.let { placeholder.value = ArtworkPlaceholder.Picture(it) }
        }
    }
    return placeholder
}

/** What can be shown on this very frame, without waiting for anything. */
private fun artworkPlaceholderNow(
    hash: String?,
    candidates: List<String>,
): ArtworkPlaceholder? {
    if (hash != null) {
        BlurHashPictures.peek(hash)?.let { return ArtworkPlaceholder.Picture(it) }
        BlurHash.averageColor(hash)?.let { return ArtworkPlaceholder.Wash(Color(it)) }
    }
    return ArtworkPlaceholderColors
        .peek(candidates)
        ?.let { ArtworkPlaceholder.Wash(Color(it).copy(alpha = REMEMBERED_WASH_ALPHA)) }
}

internal fun DrawScope.drawArtworkPlaceholder(placeholder: ArtworkPlaceholder) {
    when (placeholder) {
        is ArtworkPlaceholder.Picture ->
            drawImage(
                image = placeholder.image,
                // Rounded up, so a fractional tile never shows a hairline of skeleton at its edge.
                dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()),
                // Bilinear: a few dozen pixels of cosines stretched over a tile are meant to look soft.
                filterQuality = FilterQuality.Low,
            )
        is ArtworkPlaceholder.Wash -> drawRect(placeholder.color)
    }
}

/**
 * Decoded hashes, by hash: 4 KB each, so a screenful of shelves and the page behind it cost well
 * under a megabyte. A grid scrolling back over tiles it has shown finds them here on the frame
 * they return, instead of flashing each one through its average colour again.
 */
private object BlurHashPictures {
    private const val MAX_ENTRIES = 160
    private val lock = Any()
    private val pictures = linkedMapOf<String, ImageBitmap>()

    fun peek(hash: String): ImageBitmap? =
        synchronized(lock) {
            pictures.remove(hash)?.also { pictures[hash] = it }
        }

    suspend fun decode(hash: String): ImageBitmap? {
        peek(hash)?.let { return it }
        // Off the main thread: a shelf arriving at once is a dozen decodes in the same frame.
        val image =
            withContext(Dispatchers.Default) {
                BlurHash
                    .decode(hash, BLUR_HASH_DECODE_SIZE, BLUR_HASH_DECODE_SIZE)
                    ?.let { blurHashImage(it, BLUR_HASH_DECODE_SIZE, BLUR_HASH_DECODE_SIZE) }
                    ?.also(ImageBitmap::prepareToDraw)
            } ?: return null
        synchronized(lock) {
            pictures[hash] = image
            while (pictures.size > MAX_ENTRIES) pictures.remove(pictures.keys.first())
        }
        return image
    }
}

/** Wraps decoded opaque ARGB pixels, row by row, as a bitmap a draw can stretch. */
internal expect fun blurHashImage(
    pixels: IntArray,
    width: Int,
    height: Int,
): ImageBitmap
