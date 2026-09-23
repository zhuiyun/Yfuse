package com.yfuse.feature.personal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccentColors
import com.yfuse.core.designsystem.ThemeText
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.personal.PersonalMediaRef
import com.yfuse.core.sync.watchKey
import com.yfuse.feature.detail.GlassActionButton
import org.koin.core.context.GlobalContext

/** A title's place in the selected family profile's own lists, and the switches that change it. */
class PersonalMediaLists(
    val favorite: Boolean,
    val wanted: Boolean,
    /** False when this profile may not use the title's server; the lists are then read-only. */
    val allowed: Boolean,
    val error: String?,
    val toggleFavorite: () -> Unit,
    val toggleWanted: () -> Unit,
)

@Composable
fun rememberPersonalMediaLists(
    detail: MediaDetail,
    serverId: String,
): PersonalMediaLists {
    val personal = remember { GlobalContext.get().get<PersonalLibraryRepository>() }
    val state by personal.state.collectAsState()
    val media =
        remember(detail, serverId) {
            PersonalMediaRef(
                mediaKey = detail.providerIds.watchKey(detail.id),
                title = detail.title,
                mediaType = detail.type,
                year = detail.year,
                tmdbId =
                    detail.providerIds.entries
                        .firstOrNull { it.key.equals("tmdb", true) }
                        ?.value
                        ?.toIntOrNull(),
                serverId = serverId,
                serverItemId = detail.id,
            )
        }
    val favorite = state.favorites.any { it.media.identity == media.identity }
    val wanted = state.watchLater.any { it.media.identity == media.identity }
    return PersonalMediaLists(
        favorite = favorite,
        wanted = wanted,
        allowed = personal.canAccessServer(serverId),
        error = state.error,
        toggleFavorite = { if (personal.canAccessServer(serverId)) personal.setFavorite(media, !favorite) },
        toggleWanted = { if (personal.canAccessServer(serverId)) personal.setWatchLater(media, !wanted) },
    )
}

/** These lists belong to the selected family profile; server lists remain separately available. */
@Composable
fun PersonalMediaActions(
    detail: MediaDetail,
    serverId: String,
    modifier: Modifier = Modifier,
    accent: androidx.compose.ui.graphics.Color = LocalAccentColors.current.accent,
) {
    val lists = rememberPersonalMediaLists(detail, serverId)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassActionButton(
                icon = if (lists.favorite) AppIcons.HeartFilled else AppIcons.Heart,
                label = if (lists.favorite) "已收藏到个人清单" else "收藏到个人清单",
                active = lists.favorite,
                accent = accent,
                enabled = lists.allowed,
                onClick = lists.toggleFavorite,
                modifier = Modifier.weight(1f),
            )
            GlassActionButton(
                icon = if (lists.wanted) AppIcons.Check else AppIcons.Bookmark,
                label = if (lists.wanted) "已加入个人想看" else "加入个人想看",
                active = lists.wanted,
                accent = accent,
                enabled = lists.allowed,
                onClick = lists.toggleWanted,
                modifier = Modifier.weight(1f),
            )
        }
        lists.error?.let { ThemeText(it, style = AppTypography.caption.regular) }
    }
}
