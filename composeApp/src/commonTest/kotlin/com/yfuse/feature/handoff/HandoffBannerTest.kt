package com.yfuse.feature.handoff

import com.yfuse.core.handoff.HandoffElsewhere
import com.yfuse.core.handoff.HandoffUiState
import com.yfuse.watch.protocol.HandoffEnvelope
import com.yfuse.watch.protocol.HandoffRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HandoffBannerTest {
    private val request =
        HandoffRequest("request-0000000001", "tv", "phone", "客厅电视", 120_000, HandoffEnvelope("nonce", "payload"))
    private val television = HandoffElsewhere("tv", "客厅电视", "雾港", 1_930_000, 2_700_000, "tmdb:1", "adult")
    private val tablet = HandoffElsewhere("pad", "平板", "深海回声", 60_000, 5_400_000, "tmdb:2", "child")
    private val idle = HandoffUiState(signedIn = true, online = true, canReceive = true)

    private fun content(
        state: HandoffUiState,
        closed: Set<String> = emptySet(),
        hidden: String? = null,
        rejectFailure: String? = null,
        canContinue: (HandoffElsewhere) -> Boolean = { true },
    ) = handoffBannerContent(state, closed, hidden, rejectFailure, canContinue)

    @Test
    fun an_offered_transfer_comes_first_and_steps_aside_while_it_is_being_refused() {
        val offered = idle.copy(incoming = request, playingElsewhere = listOf(television))
        assertEquals(HandoffBannerContent.Incoming(request), content(offered))
        assertEquals(
            HandoffBannerContent.Incoming(request, "未能拒绝请求，请重试"),
            content(offered, rejectFailure = "未能拒绝请求，请重试"),
        )
        // While 忽略 is on its way, nothing else takes the offer's place.
        assertNull(content(offered, hidden = request.id))
        assertNull(content(offered.copy(busy = true)))
    }

    @Test
    fun continuing_shows_until_the_transfer_it_started_is_over() {
        val asking = idle.copy(continuing = television, playingElsewhere = listOf(television))
        assertEquals(HandoffBannerContent.Continuing(television, preparing = false), content(asking))
        assertEquals(HandoffBannerContent.Continuing(television, preparing = true), content(asking.copy(busy = true)))
    }

    @Test
    fun a_failed_transfer_says_why_unless_another_is_offered() {
        val failed = idle.copy(receiveFailure = "客厅电视没有回应")
        assertEquals(HandoffBannerContent.Failure("客厅电视没有回应"), content(failed))
        assertEquals(HandoffBannerContent.Incoming(request), content(failed.copy(incoming = request)))
    }

    @Test
    fun another_devices_playback_is_offered_only_when_this_one_could_take_it() {
        val playing = idle.copy(playingElsewhere = listOf(television, tablet))
        assertEquals(HandoffBannerContent.Elsewhere(television), content(playing))
        assertNull(content(playing.copy(canReceive = false)))
        assertNull(content(playing.copy(busy = true)))
        // Closed on the television, the tablet is next; another profile's title is never offered.
        assertEquals(
            HandoffBannerContent.Elsewhere(tablet),
            content(playing, closed = setOf(elsewhereBannerKey(television))),
        )
        assertNull(content(playing, canContinue = { it.profileId == "guest" }))
        // A new title on the television is offered again.
        val next = television.copy(mediaKey = "tmdb:3")
        assertEquals(
            HandoffBannerContent.Elsewhere(next),
            content(idle.copy(playingElsewhere = listOf(next)), closed = setOf(elsewhereBannerKey(television))),
        )
    }

    @Test
    fun the_clock_reads_like_the_player() {
        assertEquals("32:10", handoffBannerClock(1_930_000))
        assertEquals("1:02:03", handoffBannerClock(3_723_999))
        assertEquals("0:00", handoffBannerClock(-1))
    }
}
