package com.yfuse.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.model.MediaRating
import com.yfuse.core.model.RatingSource
import com.yfuse.core.model.validRatings

/** Local source marks stay visible offline; numbers always carry their original scale. */
@Composable
fun RatingBadges(
    ratings: List<MediaRating>,
    modifier: Modifier = Modifier,
    onArtwork: Boolean = false,
    large: Boolean = false,
) {
    val palette = LocalPalette.current
    val ink = if (onArtwork) Color.White else palette.text
    val shadow = if (onArtwork) Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1f), 3f) else null
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ratings.validRatings().forEach { rating ->
            Row(
                Modifier
                    .clearAndSetSemantics {
                        contentDescription =
                            "${rating.source.label} ${rating.display}，满分 ${rating.source.maximum}"
                    }.padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RatingSourceIcon(rating.source, large, onArtwork, ink, shadow)
                ThemeText(
                    if (rating.source == RatingSource.Metacritic) "${rating.display}/100" else rating.display,
                    style = AppTypography.body.strong.copy(fontSize = if (large) 16.sp else 14.sp, shadow = shadow),
                    color = ink,
                )
            }
        }
    }
}

@Composable
private fun RatingSourceIcon(
    source: RatingSource,
    large: Boolean,
    onArtwork: Boolean,
    ink: Color,
    shadow: Shadow?,
) {
    if (source == RatingSource.Library || source == RatingSource.LibraryCritics) {
        val size = if (large) 16.dp else 14.dp
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (onArtwork) {
                ThemeIcon(
                    AppIcons.StarFilled,
                    contentDescription = null,
                    tint = Color.Black.copy(alpha = 0.75f),
                    modifier = Modifier.offset(y = 0.5.dp).size(size),
                )
            }
            ThemeIcon(AppIcons.StarFilled, contentDescription = null, tint = ink, modifier = Modifier.size(size))
        }
        return
    }
    val color =
        when (source) {
            RatingSource.Tmdb -> Color(0xFF90CEA1)
            RatingSource.Imdb -> Color(0xFFF5C518)
            RatingSource.Douban -> Color(0xFF007A32)
            RatingSource.RottenTomatoes, RatingSource.RottenTomatoesAudience -> Color(0xFFB92B24)
            RatingSource.Metacritic -> Color(0xFF22334E)
            RatingSource.Library, RatingSource.LibraryCritics -> LocalAccentColors.current.accent
        }
    ThemeText(
        source.mark,
        style = AppTypography.caption.strong.copy(fontSize = if (large) 13.sp else 12.sp, shadow = shadow),
        color = if (onArtwork) ink else readableSurfaceInk(color, LocalPalette.current.background),
    )
}
