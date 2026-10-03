package com.yfuse.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.TmdbSeriesIdentityCandidate
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DecorativeTints
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.flatGlass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.resolveAccentColors
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.util.currentIsoDate
import com.yfuse.feature.calendar.ShowScheduleDialog
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * The detail page's 播出日历: the shared card for this series, with the synopsis and score the
 * page already holds. Until the series is matched to one TMDB identity, the card asks for it in
 * place of the calendar; once matched it can be changed from the card's foot.
 */
@Composable
internal fun SeriesAiringCalendarDialog(
    title: String,
    days: List<CalendarDay>,
    loading: Boolean,
    error: String?,
    posterUrls: List<String?> = emptyList(),
    artworkColorUrl: String? = null,
    overview: String? = null,
    rating: Double? = null,
    identityCandidates: List<TmdbSeriesIdentityCandidate> = emptyList(),
    followed: Boolean = false,
    reminderMode: CalendarReminderMode = CalendarReminderMode.Off,
    remindBeforeMinutes: Int = 30,
    onSelectIdentity: (TmdbSeriesIdentityCandidate) -> Unit = {},
    onToggleFollow: () -> Unit = {},
    onSetReminder: (CalendarReminderMode, Int) -> Unit = { _, _ -> },
    onRebindIdentity: () -> Unit = {},
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ShowScheduleDialog(
        title = title,
        days = days,
        today = currentIsoDate(),
        posterUrls = posterUrls.filterNotNull(),
        followed = followed,
        reminderMode = reminderMode,
        remindBeforeMinutes = remindBeforeMinutes,
        onToggleFollow = onToggleFollow,
        onSetReminder = onSetReminder,
        onDismiss = onDismiss,
        artworkColorUrl = artworkColorUrl,
        overview = overview,
        rating = rating,
        loading = loading,
        error = error,
        onRetry = onRetry,
        onRebindIdentity = onRebindIdentity,
        replacement =
            if (identityCandidates.isEmpty()) {
                null
            } else {
                { SeriesIdentityCandidates(identityCandidates, onSelectIdentity) }
            },
    )
}

@Composable
private fun SeriesIdentityCandidates(
    candidates: List<TmdbSeriesIdentityCandidate>,
    onSelect: (TmdbSeriesIdentityCandidate) -> Unit,
) {
    val palette = LocalPalette.current
    val plum = resolveAccentColors(DecorativeTints.plum, palette.isDark)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.space.lg, vertical = Dimens.space.sm),
        verticalArrangement = Arrangement.spacedBy(Dimens.space.sm),
    ) {
        Text(
            "媒体库缺少可靠的 TMDB 标识。请选择一次，结果会保存到本机。",
            style = AppTypography.body.regular,
            color = palette.sub,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = Dimens.space.xs),
        )
        candidates.forEach { candidate ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable { onSelect(candidate) }
                    .flatGlass(
                        AppShapes.card,
                        lerp(palette.card2, plum.container, 0.46f),
                        plum.border.copy(alpha = 0.36f),
                    ).padding(horizontal = 13.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        candidate.title,
                        style = AppTypography.body.strong,
                        color = palette.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOfNotNull(candidate.year?.toString(), "TMDB ${candidate.tmdbId}")
                            .joinToString(" · "),
                        style = AppTypography.caption.regular,
                        color = palette.sub2,
                    )
                }
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = plum.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
