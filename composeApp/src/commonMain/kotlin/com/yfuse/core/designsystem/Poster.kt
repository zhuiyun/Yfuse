package com.yfuse.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * `.poster` — a rounded, cropped artwork tile, optionally captioned by
 * `.poster-title` and underlined by the 继续观看 progress bar.
 */
@Composable
fun Poster(
    url: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    shape: Shape = GlassShapes.poster,
    /** 0f..1f — draws the 3px `#5B7FD1` resume bar along the bottom edge. */
    progress: Float? = null,
    contentDescription: String? = title,
    onClick: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val palette = LocalPalette.current
    Box(
        modifier
            .clip(shape)
            .background(if (palette.isDark) Color(0xFF232833) else Color(0xFFDDE2EA))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        overlay()

        if (title != null) {
            // `padding:8px 9px 7px; font:600 11px/1.25 'Noto Sans SC'; color:#fff;
            //  text-shadow:0 1px 4px rgba(0,0,0,.5)`
            Text(
                text = title,
                style = sc(11f, 600, lineHeight = 11f * 1.25f).copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.5f),
                        offset = Offset(0f, 1f),
                        blurRadius = 4f,
                    ),
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 9.dp, end = 9.dp, top = 8.dp, bottom = 7.dp),
            )
        }

        if (progress != null && progress > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(Brand.PrimaryGradBottom),
            )
        }
    }
}

/**
 * A poster with its identity outside the artwork. Keeping the copy below the
 * image makes titles readable without covering poster art and gives list/grid
 * cards one consistent treatment.
 */
@Composable
fun CaptionedPoster(
    url: String?,
    title: String,
    year: String?,
    modifier: Modifier = Modifier,
    posterModifier: Modifier = Modifier.fillMaxWidth(),
    progress: Float? = null,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Column(
        modifier.let { base ->
            if (onClick != null) base.clickable(onClick = onClick) else base
        },
    ) {
        Poster(
            url = url,
            progress = progress,
            contentDescription = title,
            modifier = posterModifier,
        )
        Spacer(Modifier.height(7.dp))
        Text(
            text = title,
            style = sc(12f, 600),
            color = palette.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(2.dp))
        if (year != null) {
            Text(
                text = year,
                style = mr(10f, 400),
                color = palette.sub2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // Reserve the metadata line so cards align even when a year is absent.
            Spacer(Modifier.height(13.dp))
        }
    }
}
