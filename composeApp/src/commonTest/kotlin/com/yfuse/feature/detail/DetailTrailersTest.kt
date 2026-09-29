package com.yfuse.feature.detail

import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.model.MediaTrailer
import com.yfuse.feature.extras.TrailerLauncher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailTrailersTest {
    private val local = MediaTrailer.Local("emby", "t1", "预告片", "http://host/Videos/t1/stream?static=true", null)
    private val link = MediaTrailer.Remote("预告片 2", "https://youtu.be/x", "YouTube")

    @Test
    fun there_is_no_key_without_a_trailer() {
        assertNull(trailers(emptyList()).key)
    }

    @Test
    fun one_trailer_opens_straight_away() {
        var choosing: Boolean? = null
        val holder = trailers(listOf(local)) { choosing = it }

        val key = requireNotNull(holder.key)
        key.onClick()

        assertEquals(DetailActionKeyIds.TRAILER, key.id)
        assertEquals("预告片", key.label)
        assertEquals(AppIcons.Movie, key.icon)
        assertEquals("播放预告片", key.description)
        assertEquals(false, choosing)
        assertTrue(holder.launcher.queued != null)
    }

    @Test
    fun several_trailers_are_listed_first() {
        var choosing: Boolean? = null
        val holder = trailers(listOf(local, link)) { choosing = it }

        holder.key?.onClick?.invoke()

        assertEquals(true, choosing)
        assertNull(holder.launcher.queued)
        assertEquals("预告片，共 2 个", holder.key?.description)
        assertEquals("预告片，在 YouTube 打开", trailerKeyDescription(listOf(link)))
    }

    private fun trailers(
        list: List<MediaTrailer>,
        onChoosing: (Boolean) -> Unit = {},
    ) = DetailTrailers(list, "花样年华", TrailerLauncher { true }, choosing = false, onChoosing = onChoosing)
}
