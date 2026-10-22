package com.yfuse.core.model

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.feature.json
import com.yfuse.feature.testRepo
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaRatingTest {
    @Test
    fun plex_detail_preserves_all_attributed_scores_and_deduplicates_top_level_fields() =
        runTest {
            val repo =
                testRepo {
                    json(
                        """{"MediaContainer":{"Metadata":[{"ratingKey":"100","type":"movie","title":"Film","audienceRating":8.1,"audienceRatingImage":"imdb://image.rating","rating":9.2,"ratingImage":"rottentomatoes://image.rating.ripe","Rating":[{"image":"imdb://image.rating","value":8.1,"type":"audience"},{"image":"themoviedb://image.rating","value":8.4,"type":"audience"},{"image":"rottentomatoes://image.rating.ripe","value":9.2,"type":"critic"},{"image":"rottentomatoes://image.rating.upright","value":8.8,"type":"audience"}]}]}}""",
                    )
                }
            val server =
                SavedServer(
                    id = "plex",
                    baseUrl = "http://plex:32400",
                    serverName = "Plex",
                    userId = "user",
                    userName = "User",
                    accessToken = "token",
                    kind = MediaServerKind.Plex,
                )
            val ratings = repo.itemDetail(server, "100").getOrThrow().displayRatings()
            assertEquals(
                listOf(
                    RatingSource.Imdb,
                    RatingSource.Tmdb,
                    RatingSource.RottenTomatoes,
                    RatingSource.RottenTomatoesAudience,
                ),
                ratings.map {
                    it.source
                },
            )
            assertEquals(listOf("8.1", "8.4", "92%", "88%"), ratings.map { it.display })
        }

    @Test
    fun library_scores_do_not_claim_a_provider_based_on_external_ids() {
        val item =
            Json.decodeFromString<BaseItemDto>(
                """{"Id":"film","CommunityRating":8.2,"CriticRating":92,"ProviderIds":{"Imdb":"tt123","Tmdb":"123"}}""",
            )
        assertEquals(
            listOf(
                RatingSource.Library,
                RatingSource.LibraryCritics,
            ),
            item.toMediaDetail().displayRatings().map {
                it.source
            },
        )
    }

    @Test
    fun plex_provenance_and_scales_are_preserved() {
        assertEquals(MediaRating(RatingSource.Imdb, 8.1), plexRating(8.1, "imdb://image.rating", "audience"))
        assertEquals(
            MediaRating(RatingSource.RottenTomatoes, 91.0),
            plexRating(9.1, "rottentomatoes://image.rating.ripe", "critic"),
        )
        assertEquals(
            RatingSource.RottenTomatoesAudience,
            plexRating(7.5, "rottentomatoes://image.rating.upright", "audience")?.source,
        )
        assertEquals(RatingSource.Library, plexRating(8.0, null, "audience")?.source)
        assertNull(plexRating(92.0, "imdb://image.rating", "audience"))
        assertEquals("0%", plexRating(0.0, "rottentomatoes://image.rating.rotten", "critic")?.display)
        assertNull(plexRating(0.0, "themoviedb://image.rating", "audience"))
    }

    @Test
    fun missing_invalid_and_duplicate_scores_never_create_placeholder_badges() {
        assertTrue(BaseItemDto(Id = "empty").toMediaDetail().displayRatings().isEmpty())
        val scores =
            listOf(
                MediaRating(RatingSource.Imdb, Double.NaN),
                MediaRating(RatingSource.Tmdb, 0.0),
                MediaRating(RatingSource.Douban, 11.0),
                MediaRating(RatingSource.Imdb, 8.0),
                MediaRating(RatingSource.Imdb, 8.1),
            )
        assertEquals(listOf(MediaRating(RatingSource.Imdb, 8.0)), scores.validRatings())
        assertEquals("8.3", MediaRating(RatingSource.Imdb, 8.26).display)
        assertEquals("92%", MediaRating(RatingSource.RottenTomatoes, 92.0).display)
    }
}
