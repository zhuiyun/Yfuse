package com.yfuse.feature.library

import com.yfuse.core.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaItemLiftMenuTest {
    private val ticksPerMinute = 600_000_000L

    private fun item(
        subtitle: String? = null,
        year: Int? = 2025,
        runtimeMinutes: Int? = 108,
        rating: Double? = 8.4,
        resumeMinutes: Long? = null,
        percentage: Double? = null,
        played: Boolean = false,
        favorite: Boolean = false,
    ) = MediaItem(
        id = "1",
        title = "深海回声",
        subtitle = subtitle,
        type = "Movie",
        posterItemId = "1",
        posterTag = null,
        backdropItemId = null,
        backdropTag = null,
        playedPercentage = percentage,
        resumePositionTicks = resumeMinutes?.let { it * ticksPerMinute },
        year = year,
        runtimeMinutes = runtimeMinutes,
        communityRating = rating,
        isFavorite = favorite,
        played = played,
    )

    @Test
    fun theCardLineNamesTheEpisodeWhenThereIsOneAndTheYearOtherwise() {
        assertEquals("2025 · 1小时48分钟 · ★ 8.4", item().liftMeta())
        assertEquals("第 2 季 · 第 3 集 · 1小时48分钟 · ★ 8.4", item(subtitle = "第 2 季 · 第 3 集").liftMeta())
        assertNull(item(year = null, runtimeMinutes = null, rating = null).liftMeta())
    }

    @Test
    fun progressSaysWhereItStoppedAndWhatIsLeft() {
        val started = item(resumeMinutes = 66, percentage = 61.0)
        assertEquals("看到 1:06:00 · 剩余 42分钟", started.liftProgressLabel())
        assertEquals("剩余 42分钟", started.liftRemainingLabel())
        assertEquals(0.61f, started.liftProgress()!!, 0.001f)
        assertEquals("已看完", item(played = true).liftProgressLabel())
        assertEquals(1f, item(played = true).liftProgress()!!, 0.001f)
        assertNull(item(played = true).liftRemainingLabel())
        assertNull(item().liftProgressLabel())
        assertNull(item().liftProgress())
    }

    @Test
    fun aTitleOfUnknownLengthStillSaysWhereItStopped() {
        val unknown = item(runtimeMinutes = null, resumeMinutes = 12)
        assertEquals("看到 12:00", unknown.liftProgressLabel())
        assertNull(unknown.liftRemainingLabel())
    }

    @Test
    fun clockDropsTheHourUnderAnHour() {
        assertEquals("42:05", liftClock(42 * 60L + 5))
        assertEquals("1:06:10", liftClock(3_600L + 6 * 60L + 10))
        assertEquals("0:09", liftClock(9))
    }

    @Test
    fun flagRowsAreNamedForWhatTheyWillDo() {
        var written: Boolean? = null
        val favorite = favoriteLiftAction(favorite = true) { written = it }
        assertEquals("取消收藏", favorite.label)
        favorite.onSelect()
        assertEquals(false, written)
        val played = playedLiftAction(played = false) { written = it }
        assertEquals("标记为已看", played.label)
        played.onSelect()
        assertEquals(true, written)
    }

    @Test
    fun theLibraryRailOffersPlayThenTheFavourite() {
        var played = false
        val menu =
            libraryHomeLiftMenu(
                item = item(resumeMinutes = 30),
                backdropUrl = null,
                onOpen = {},
                onPlay = { played = true },
                onFavorite = {},
            )
        assertEquals(listOf("继续播放", "收藏"), menu.actions.map { it.label })
        menu.actions.first().onSelect()
        assertEquals(true, played)
        assertEquals(
            "播放",
            libraryHomeLiftMenu(item(), null, {}, {}, {}).actions.first().label,
        )
    }

    @Test
    fun theToastSaysWhatChangedOrThatItIsWaitingToSync() {
        assertEquals("已加入收藏", flagChangeMessage(favorite = true, played = null))
        assertEquals("已取消收藏", flagChangeMessage(favorite = false, played = null))
        assertEquals("已标记为已看", flagChangeMessage(favorite = null, played = true))
        assertEquals("已标记为未看", flagChangeMessage(favorite = null, played = false))
        assertEquals("服务器暂不可用，已排队同步", flagChangeMessage(favorite = true, played = null, queued = true))
    }
}
