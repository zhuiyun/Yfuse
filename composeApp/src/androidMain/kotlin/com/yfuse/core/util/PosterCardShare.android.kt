package com.yfuse.core.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Scale
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val CARD_WIDTH = 1080
private const val CARD_HEIGHT = 1440
private const val POSTER_WIDTH = 600
private const val POSTER_HEIGHT = 900
private const val POSTER_TOP = 120f
private const val POSTER_RADIUS = 40f
private const val TEXT_WIDTH = 920
private const val TITLE_TOP = 1_090f

/** Share images kept on disk; older ones go, so the cache never collects a gallery. */
private const val KEPT_CARDS = 3

// The share sheet opens after the page that asked may already be gone; the card still goes.
private val posterShareScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

@Composable
actual fun rememberPosterCardSharer(): PosterCardSharer {
    val context = LocalContext.current
    return remember(context) { AndroidPosterCardSharer(context) }
}

private class AndroidPosterCardSharer(
    private val context: Context,
) : PosterCardSharer {
    private var pending: Job? = null

    override fun sharePosterCard(card: PosterShareCard) {
        // A second tap while the first card is still being painted would open two sheets.
        if (pending?.isActive == true) return
        pending =
            posterShareScope.launch {
                val image =
                    try {
                        paintPosterCard(context, card)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AppLog.warning("share", "poster_card_failed", "Poster card could not be painted", error)
                        null
                    }
                openShareSheet(image, card)
            }
    }

    private fun openShareSheet(
        image: Uri?,
        card: PosterShareCard,
    ) {
        val send =
            Intent(Intent.ACTION_SEND).apply {
                if (image != null) {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, image)
                    // The sheet previews the picture from the clip, and the grant travels with it.
                    clipData = ClipData.newRawUri("", image)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                }
                putExtra(Intent.EXTRA_TEXT, posterCardCaption(card))
                putExtra(Intent.EXTRA_TITLE, card.title)
            }
        runCatching {
            context.startActivity(Intent.createChooser(send, "分享海报").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { error ->
            AppLog.warning("share", "poster_card_sheet_failed", "Share sheet could not be opened", error)
        }
    }
}

/** Paints the card and stores it where the `.updates` FileProvider can hand it out. */
private suspend fun paintPosterCard(
    context: Context,
    card: PosterShareCard,
): Uri? {
    val poster = loadPoster(context, card.posterUrl)
    val bitmap = withContext(Dispatchers.Default) { drawPosterCard(poster, card) }
    return withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates/share").apply { mkdirs() }
        directory
            .listFiles { file -> file.name.startsWith("yfuse-poster-") }
            ?.sortedByDescending(File::lastModified)
            ?.drop(KEPT_CARDS - 1)
            ?.forEach(File::delete)
        val file = File(directory, "yfuse-poster-${System.currentTimeMillis()}.png")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    }
}

/** The poster through the app's own image loader, so a title already on screen costs nothing. */
private suspend fun loadPoster(
    context: Context,
    url: String?,
): Bitmap? {
    if (url.isNullOrBlank()) return null
    return try {
        val request =
            ImageRequest
                .Builder(context)
                .data(url)
                .size(POSTER_WIDTH, POSTER_HEIGHT)
                .scale(Scale.FILL)
                .precision(Precision.EXACT)
                // Painted onto a software canvas, which cannot read hardware bitmaps.
                .allowHardware(false)
                .build()
        ((SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image as? BitmapImage)?.bitmap
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private fun drawPosterCard(
    poster: Bitmap?,
    card: PosterShareCard,
): Bitmap {
    val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val full = RectF(0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat())
    val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    if (poster != null) {
        // The poster itself, shrunk to a few pixels and stretched back: a blur with no blur API.
        val wash = Bitmap.createScaledBitmap(poster, 12, 18, true)
        canvas.drawBitmap(wash, null, full, fill)
        // The poster itself belongs to the image cache and must never be recycled here.
        if (wash !== poster) wash.recycle()
    }
    fill.shader =
        LinearGradient(
            0f,
            0f,
            0f,
            CARD_HEIGHT.toFloat(),
            intArrayOf(
                Color.argb(if (poster != null) 150 else 255, 22, 29, 44),
                Color.argb(if (poster != null) 215 else 255, 9, 12, 20),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
    canvas.drawRect(full, fill)
    fill.shader = null

    val left = (CARD_WIDTH - POSTER_WIDTH) / 2f
    val frame = RectF(left, POSTER_TOP, left + POSTER_WIDTH, POSTER_TOP + POSTER_HEIGHT)
    fill.color = Color.argb(255, 18, 24, 36)
    fill.setShadowLayer(48f, 0f, 20f, Color.argb(150, 0, 0, 0))
    canvas.drawRoundRect(frame, POSTER_RADIUS, POSTER_RADIUS, fill)
    fill.clearShadowLayer()
    if (poster != null) {
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(frame, POSTER_RADIUS, POSTER_RADIUS, Path.Direction.CW) })
        canvas.drawBitmap(poster, centreCrop(poster, POSTER_WIDTH, POSTER_HEIGHT), frame, fill)
        canvas.restore()
    }

    var top = TITLE_TOP
    val titlePaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    top += canvas.drawCentredText(card.title.trim(), titlePaint, top, maxLines = 2) + 22f
    posterCardMeta(card.year, card.rating)?.let { meta ->
        val metaPaint =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 255, 255, 255)
                textSize = 40f
            }
        canvas.drawCentredText(meta, metaPaint, top, maxLines = 1)
    }
    val mark =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 255, 255, 255)
            textSize = 30f
            letterSpacing = 0.2f
        }
    canvas.drawCentredText("YFUSE", mark, CARD_HEIGHT - 90f, maxLines = 1)
    return bitmap
}

/** Draws [text] centred across the card from [top]; returns the height it took. */
private fun Canvas.drawCentredText(
    text: String,
    paint: TextPaint,
    top: Float,
    maxLines: Int,
): Float {
    val layout =
        StaticLayout.Builder
            .obtain(text, 0, text.length, paint, TEXT_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.1f)
            .build()
    save()
    translate((CARD_WIDTH - TEXT_WIDTH) / 2f, top)
    layout.draw(this)
    restore()
    return layout.height.toFloat()
}

/** The largest [width]:[height] window in the middle of [source]. */
private fun centreCrop(
    source: Bitmap,
    width: Int,
    height: Int,
): Rect {
    val target = width.toFloat() / height
    val actual = source.width.toFloat() / source.height
    return if (actual > target) {
        val cropped = (source.height * target).toInt()
        val x = (source.width - cropped) / 2
        Rect(x, 0, x + cropped, source.height)
    } else {
        val cropped = (source.width / target).toInt()
        val y = (source.height - cropped) / 2
        Rect(0, y, source.width, y + cropped)
    }
}
