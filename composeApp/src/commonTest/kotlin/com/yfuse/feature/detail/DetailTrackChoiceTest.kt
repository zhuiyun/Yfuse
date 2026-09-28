package com.yfuse.feature.detail

import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.MediaSourceDto
import com.yfuse.core.data.dto.MediaStreamDto
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.core.data.dto.toMediaVersion
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.SubtitleTrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DetailTrackChoiceTest {
    private val source =
        MediaSourceDto(
            Id = "v1",
            Container = "mkv",
            MediaStreams =
                listOf(
                    MediaStreamDto(Index = 0, Type = "Video", Codec = "hevc"),
                    MediaStreamDto(Index = 1, Type = "Audio", Codec = "truehd", Language = "chi", Channels = 8),
                    MediaStreamDto(Index = 2, Type = "Audio", Codec = "aac", Language = "chi", Title = "导演评论"),
                    MediaStreamDto(Index = 3, Type = "Subtitle", Codec = "ass", Language = "chi", Title = "简体"),
                    MediaStreamDto(Index = 4, Type = "Subtitle", Codec = "ass", Language = "chi", Title = "简英双语"),
                    MediaStreamDto(Index = 5, Type = "Subtitle", Codec = "srt", Language = "eng"),
                    MediaStreamDto(Index = 6, Type = "Subtitle", Codec = "ass", Title = "特效字幕"),
                ),
        )

    private val version: MediaVersion = source.toMediaVersion(fallbackId = "movie", ordinal = 0)

    @Test
    fun a_track_title_survives_its_language_tag_and_tells_same_language_tracks_apart() {
        assertEquals(
            listOf("中文 · 简体 · ASS", "中文 · 简英双语 · ASS", "英语 · SRT", "特效字幕 · ASS"),
            version.subtitleTracks.map { it.label },
        )
        // The language is still what travels to the player and to offline matching.
        assertEquals(listOf("中文", "中文", "英语", "特效字幕"), version.subtitleTracks.map { it.language })
        assertEquals("中文 · 导演评论 · AAC", version.audioTracks[1].choiceLabel)
        // The audio label doubles as the player's source-format hint, so it stays title-free.
        assertEquals("中文 · AAC", version.audioTracks[1].label)
    }

    @Test
    fun choosing_one_of_two_chinese_subtitles_lights_only_that_chip() {
        val choices = subtitleTrackChoices(version)
        val second = choices.single { it.label == "中文 · 简英双语 · ASS" }

        val lit = choices.filter { it.isSelected(second.value, second.ordinal) }

        assertEquals(listOf(second), lit)
        assertEquals(listOf("默认"), choices.filter { it.isSelected(null, null) }.map { it.label })
        assertEquals(
            listOf("关闭"),
            choices.filter { it.isSelected(PlaybackTrackRequest.SUBTITLES_OFF, null) }.map { it.label },
        )
        // A language stored without a place among its tracks means its first one.
        assertEquals(listOf("中文 · 简体 · ASS"), choices.filter { it.isSelected("中文", null) }.map { it.label })
    }

    @Test
    fun sidecars_count_after_the_container_streams_of_their_language() {
        val tracks =
            listOf(
                SubtitleTrackInfo(index = 9, codec = "srt", language = "中文", external = true),
                SubtitleTrackInfo(index = 3, codec = "ass", language = "中文"),
                SubtitleTrackInfo(index = 4, codec = "ass", language = "英语"),
                SubtitleTrackInfo(index = 5, codec = "pgssub", language = "中文"),
                SubtitleTrackInfo(index = 6, codec = "pgssub", language = null),
            )

        assertEquals(listOf(2, 0, 0, 1, null), tracks.sameLanguageOrdinals({ it.language }, { it.external }))
    }

    @Test
    fun the_player_hears_which_same_language_track_was_picked_and_nothing_more_otherwise() {
        val detail = BaseItemDto(Id = "movie", Type = "Movie", MediaSources = listOf(source)).toMediaDetail()
        val picked =
            DetailState(
                playTarget = detail,
                preferredSubtitleLanguage = "中文",
                preferredSubtitleOrdinal = 1,
                preferredAudioLanguage = "中文",
                preferredAudioOrdinal = 1,
            ).requestedTracks()

        assertEquals(PlaybackTrackRequest.TrackHint("简英双语", "ass", 1), picked.subtitleHint)
        assertEquals(PlaybackTrackRequest.TrackHint("导演评论", "aac", 1), picked.audioHint)

        val unique = DetailState(playTarget = detail, preferredSubtitleLanguage = "英语").requestedTracks()
        assertEquals("英语", unique.subtitleLanguage)
        assertNull(unique.subtitleHint)
        val off = DetailState(playTarget = detail, preferredSubtitleLanguage = PlaybackTrackRequest.SUBTITLES_OFF)
        assertNull(off.requestedTracks().subtitleHint)
    }

    @Test
    fun a_pick_among_a_language_is_kept_across_files_only_while_the_new_file_has_that_many() {
        val twoChinese = BaseItemDto(Id = "movie", Type = "Movie", MediaSources = listOf(source)).toMediaDetail()
        val picked =
            with(DetailReducer) {
                DetailState(playTarget = twoChinese, selectedVersionId = "v1")
                    .reduce(DetailMsg.SubtitleLanguageSelected("中文", 1))
            }
        assertEquals(1, picked.preferredSubtitleOrdinal)

        val oneChinese =
            BaseItemDto(
                Id = "movie",
                Type = "Movie",
                MediaSources =
                    listOf(
                        source.copy(
                            Id = "v2",
                            MediaStreams = source.MediaStreams.orEmpty().filterNot { it.Title == "简英双语" },
                        ),
                    ),
            ).toMediaDetail()
        val moved =
            with(DetailReducer) {
                picked
                    .copy(playTarget = oneChinese)
                    .reduce(DetailMsg.VersionSelected("v2"))
            }

        assertEquals("中文", moved.preferredSubtitleLanguage)
        assertNull(moved.preferredSubtitleOrdinal)
        val off =
            with(DetailReducer) {
                picked.reduce(DetailMsg.SubtitleLanguageSelected(PlaybackTrackRequest.SUBTITLES_OFF, 1))
            }
        assertNull(off.preferredSubtitleOrdinal)
    }
}
