package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.util.scheduledEpochMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiringLocalTimeTest {
    @Test
    fun a_new_york_evening_is_the_next_morning_in_beijing() {
        val local = localAirTime("2026-08-27", "21:00", "America/New_York", deviceZoneId = BEIJING)

        assertEquals(LocalAirTime("09:00", dayOffset = 1), local)
        assertEquals("次日 09:00", local?.label)
    }

    @Test
    fun the_conversion_follows_the_zone_rules_rather_than_a_fixed_offset() {
        // New York is on standard time in January, an hour further from Beijing.
        assertEquals(
            LocalAirTime("10:00", dayOffset = 1),
            localAirTime("2026-01-15", "21:00", "America/New_York", deviceZoneId = BEIJING),
        )
    }

    @Test
    fun a_device_in_the_publishing_zone_sees_the_published_time() {
        val domestic = episode(airDate = "2026-08-25", airTime = "12:00", timeZoneId = BEIJING)

        assertEquals(LocalAirTime("12:00", dayOffset = 0), localAirTime("2026-08-25", "12:00", BEIJING, BEIJING))
        assertEquals("12:00", airTimeLabel(domestic, deviceZoneId = BEIJING))
    }

    @Test
    fun a_device_west_of_the_publisher_can_land_on_the_day_before() {
        val local = localAirTime("2026-08-25", "08:00", BEIJING, deviceZoneId = "America/Los_Angeles")

        assertEquals(LocalAirTime("17:00", dayOffset = -1), local)
        assertEquals("前一天 17:00", local?.label)
    }

    @Test
    fun a_time_without_a_zone_is_labelled_rather_than_passed_off_as_local() {
        assertNull(localAirTime("2026-08-27", "21:00", null, deviceZoneId = BEIJING))
        assertEquals("21:00（当地）", airTimeLabel(episode(timeZoneId = null), deviceZoneId = BEIJING))
        assertEquals("21:00（当地）", airTimeLabel(episode(timeZoneId = "Mars/Olympus"), deviceZoneId = BEIJING))
    }

    @Test
    fun a_known_zone_that_cannot_be_converted_names_where_the_time_is_from() {
        val malformedDate = episode(airDate = "2026-8-27", timeZoneId = "America/New_York")

        assertEquals("美东 21:00", airTimeLabel(malformedDate, deviceZoneId = BEIJING))
    }

    @Test
    fun no_time_means_no_label() {
        assertNull(airTimeLabel(episode(airTime = null), deviceZoneId = BEIJING))
        assertNull(broadcastTimeLabel(episode(airTime = null), deviceZoneId = BEIJING))
    }

    @Test
    fun a_foreign_release_instant_is_shown_on_the_device_clock() {
        val foreign = episode(airTime = "20:00", releaseAtUtc = "2026-08-28T01:00:00Z")

        assertEquals("次日 09:00", broadcastTimeLabel(foreign, deviceZoneId = BEIJING))
        assertEquals("21:00", broadcastTimeLabel(foreign, deviceZoneId = "America/New_York"))
    }

    @Test
    fun a_domestic_title_uses_its_published_time_and_zone() {
        val domestic =
            episode(
                airDate = "2026-08-25",
                airTime = "12:00",
                timeZoneId = BEIJING,
                origin = ShowOrigin.Domestic,
                releaseAtUtc = "2026-08-25T04:30:00Z",
            )

        assertEquals("12:00", broadcastTimeLabel(domestic, deviceZoneId = BEIJING))
        assertEquals("13:00", broadcastTimeLabel(domestic, deviceZoneId = "Asia/Tokyo"))
    }

    @Test
    fun the_reminder_text_reads_the_instant_it_was_scheduled_for() {
        val scheduled = checkNotNull(scheduledEpochMillis("2026-08-27", "21:00", "America/New_York"))

        assertEquals("09:00", localAirTimeAt(scheduled, "2026-08-27", deviceZoneId = BEIJING)?.time)
    }

    @Test
    fun a_days_rows_order_by_the_time_they_show_here() {
        val seoul = episode(airDate = "2026-08-27", airTime = "20:00", timeZoneId = "Asia/Seoul")
        val beijing = episode(airDate = "2026-08-27", airTime = "19:30", timeZoneId = BEIJING)
        val newYork = episode(airDate = "2026-08-27", airTime = "21:00", timeZoneId = "America/New_York")
        val undated = episode(airDate = "2026-08-27", airTime = null)

        val ordered =
            listOf(newYork, undated, beijing, seoul).sortedBy { localAirMinutes(it, deviceZoneId = BEIJING) }

        // Seoul's 20:00 is 19:00 here, ahead of Beijing's 19:30; New York's lands the next morning.
        assertEquals(listOf(seoul, beijing, newYork, undated), ordered)
    }

    private fun episode(
        airDate: String = "2026-08-27",
        airTime: String? = "21:00",
        timeZoneId: String? = "America/New_York",
        origin: ShowOrigin = ShowOrigin.Foreign,
        releaseAtUtc: String? = null,
    ) = AiringEpisode(
        showTmdbId = 1,
        showTitle = "Show",
        posterPath = null,
        seasonNumber = 1,
        episodeNumber = 1,
        episodeTitle = null,
        airDate = airDate,
        origin = origin,
        airTime = airTime,
        timeZoneId = timeZoneId,
        releaseAtUtc = releaseAtUtc,
    )

    private companion object {
        const val BEIJING = "Asia/Shanghai"
    }
}
