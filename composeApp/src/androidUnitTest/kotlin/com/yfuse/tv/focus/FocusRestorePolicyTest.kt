package com.yfuse.tv.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusRestorePolicyTest {
    private val policy = FocusRestorePolicy()
    private val context = FocusContext("home", "emby", "profile")

    @Test
    fun stableIdWinsWhenListOrderChanges() {
        val saved = anchor(section = "continue", item = "episode-9", index = 1, offset = 37)
        val request =
            request(
                candidate("continue", "episode-3", 0),
                candidate("continue", "episode-9", 8),
            )

        val decision = policy.resolve(request, saved)

        assertEquals(FocusRestoreReason.ExactStableItem, decision.reason)
        assertEquals("episode-9", decision.candidate?.itemStableId)
        assertEquals(8, decision.anchor?.fallbackIndex)
        assertEquals(37, decision.anchor?.scrollOffset)
    }

    @Test
    fun followsStableItemWhenBackendMovesItToAnotherSection() {
        val saved = anchor(section = "trending", item = "movie-1", index = 5, offset = 80)

        val decision =
            policy.resolve(
                request(candidate("recommended", "movie-1", 2)),
                saved,
            )

        assertEquals(FocusRestoreReason.StableItemMovedSection, decision.reason)
        assertEquals("recommended", decision.anchor?.sectionId)
        assertEquals(0, decision.anchor?.scrollOffset)
    }

    @Test
    fun removedItemFallsBackToNearestEnabledIndexInSameSection() {
        val saved = anchor(section = "movies", item = "deleted", index = 4, offset = 19)
        val decision =
            policy.resolve(
                request(
                    candidate("movies", "disabled", 4, enabled = false),
                    candidate("movies", "previous", 3),
                    candidate("movies", "next", 6),
                    candidate("shows", "other", 4),
                ),
                saved,
            )

        assertEquals(FocusRestoreReason.SameSectionNearestItem, decision.reason)
        assertEquals("previous", decision.candidate?.itemStableId)
        assertEquals(19, decision.anchor?.scrollOffset)
    }

    @Test
    fun ignoresAnchorFromAnotherProfileAndUsesPreferredTarget() {
        val foreign =
            FocusAnchor(
                route = "home",
                serverId = "emby",
                profileId = "child",
                sectionId = "continue",
                itemStableId = "restricted",
                fallbackIndex = 0,
                scrollOffset = 0,
            )
        val preferred = candidate("hero", "safe", 0)
        val request =
            FocusRestoreRequest(
                context = context,
                candidates = listOf(candidate("continue", "restricted", 0), preferred),
                preferredTargetId = preferred.targetId,
            )

        val decision = policy.resolve(request, foreign)

        assertEquals(FocusRestoreReason.PreferredTarget, decision.reason)
        assertEquals("safe", decision.candidate?.itemStableId)
    }

    @Test
    fun emptyOrDisabledDatasetCannotRestore() {
        val decision =
            policy.resolve(
                request(candidate("hero", "disabled", 0, enabled = false)),
                savedAnchor = null,
            )

        assertFalse(decision.canRestore)
        assertEquals(FocusRestoreReason.NoAvailableItem, decision.reason)
        assertNull(decision.anchor)
        assertNull(decision.candidate)
    }

    @Test
    fun preferredSectionThenFirstAvailableAreDeterministic() {
        val sectionDecision =
            policy.resolve(
                FocusRestoreRequest(
                    context = context,
                    candidates =
                        listOf(
                            candidate("other", "zero", 0),
                            candidate("wanted", "second", 2),
                            candidate("wanted", "first", 1),
                        ),
                    preferredSectionId = "wanted",
                ),
                savedAnchor = null,
            )
        assertEquals("first", sectionDecision.candidate?.itemStableId)
        assertEquals(FocusRestoreReason.PreferredSectionFirstItem, sectionDecision.reason)
        assertTrue(sectionDecision.canRestore)

        val firstDecision =
            policy.resolve(
                request(candidate("z", "later", 4), candidate("a", "first", 0)),
                savedAnchor = null,
            )
        assertEquals("first", firstDecision.candidate?.itemStableId)
        assertEquals(FocusRestoreReason.FirstAvailableItem, firstDecision.reason)
    }

    private fun request(vararg candidates: FocusCandidate) =
        FocusRestoreRequest(context = context, candidates = candidates.toList())

    private fun candidate(
        section: String,
        item: String,
        index: Int,
        enabled: Boolean = true,
    ) = FocusCandidate(
        targetId = FocusTargetId(scopeId = "home-content", value = "$section/$item"),
        sectionId = section,
        itemStableId = item,
        index = index,
        enabled = enabled,
    )

    private fun anchor(
        section: String,
        item: String,
        index: Int,
        offset: Int,
    ) = FocusAnchor(
        route = context.route,
        serverId = context.serverId,
        profileId = context.profileId,
        sectionId = section,
        itemStableId = item,
        fallbackIndex = index,
        scrollOffset = offset,
    )
}
