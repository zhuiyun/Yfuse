package com.yfuse.tv.focus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DirectionalFocusPolicyTest {
    private val policy = DirectionalFocusPolicy()
    private val navigation = id("nav", "home")
    private val first = id("content", "first")
    private val second = id("content", "second")

    @Test
    fun rightFromNavigationRestoresLastContentAnchor() {
        val decision =
            policy.resolve(
                request(
                    current = navigation,
                    direction = TvFocusDirection.Right,
                    nodes =
                        listOf(
                            node(navigation, 0f, 0f, FocusZone.Navigation),
                            node(first, 100f, 0f, FocusZone.Content),
                            node(second, 200f, 0f, FocusZone.Content),
                        ),
                    lastContent = second,
                ),
            )

        val move = assertIs<FocusMoveDecision.Move>(decision)
        assertEquals(second, move.targetId)
        assertEquals(FocusMoveReason.RestoreContentFromNavigation, move.reason)
    }

    @Test
    fun leftTraversesContentBeforeEnteringNavigationRail() {
        val nodes =
            listOf(
                node(navigation, 0f, 0f, FocusZone.Navigation),
                node(first, 100f, 0f, FocusZone.Content),
                node(second, 200f, 0f, FocusZone.Content),
            )
        val withinContent =
            policy.resolve(
                request(second, TvFocusDirection.Left, nodes, navigationRail = navigation),
            )
        assertEquals(first, assertIs<FocusMoveDecision.Move>(withinContent).targetId)

        val toRail =
            policy.resolve(
                request(first, TvFocusDirection.Left, nodes, navigationRail = navigation),
            )
        val railMove = assertIs<FocusMoveDecision.Move>(toRail)
        assertEquals(navigation, railMove.targetId)
        assertEquals(FocusMoveReason.EnterNavigationRail, railMove.reason)
    }

    @Test
    fun navigationRailHandshakeMirrorsInRtl() {
        val rtlNavigation = id("nav", "home")
        val rtlContent = id("content", "card")
        val nodes =
            listOf(
                node(rtlContent, 0f, 0f, FocusZone.Content),
                node(rtlNavigation, 300f, 0f, FocusZone.Navigation),
            )

        val enterContent =
            policy.resolve(
                request(
                    rtlNavigation,
                    TvFocusDirection.Left,
                    nodes,
                    lastContent = rtlContent,
                    layoutDirection = TvLayoutDirection.RightToLeft,
                ),
            )
        assertEquals(rtlContent, assertIs<FocusMoveDecision.Move>(enterContent).targetId)

        val enterRail =
            policy.resolve(
                request(
                    rtlContent,
                    TvFocusDirection.Right,
                    nodes,
                    navigationRail = rtlNavigation,
                    layoutDirection = TvLayoutDirection.RightToLeft,
                ),
            )
        assertEquals(rtlNavigation, assertIs<FocusMoveDecision.Move>(enterRail).targetId)
    }

    @Test
    fun explicitNeighborOverridesGeometry() {
        val far = id("content", "far")
        val currentNode =
            node(first, 100f, 0f, FocusZone.Content).copy(
                neighbors = mapOf(TvFocusDirection.Right to far),
            )
        val decision =
            policy.resolve(
                request(
                    first,
                    TvFocusDirection.Right,
                    listOf(
                        currentNode,
                        node(second, 200f, 0f, FocusZone.Content),
                        node(far, 500f, 0f, FocusZone.Content),
                    ),
                ),
            )

        val move = assertIs<FocusMoveDecision.Move>(decision)
        assertEquals(far, move.targetId)
        assertEquals(FocusMoveReason.ExplicitNeighbor, move.reason)
    }

    @Test
    fun beamCandidateWinsOverCloserDiagonalCandidate() {
        val diagonal = id("content", "diagonal")
        val inBeam = id("content", "beam")
        val decision =
            policy.resolve(
                request(
                    first,
                    TvFocusDirection.Right,
                    listOf(
                        node(first, 0f, 0f, FocusZone.Content),
                        node(diagonal, 60f, 80f, FocusZone.Content),
                        node(inBeam, 120f, 0f, FocusZone.Content),
                    ),
                ),
            )

        assertEquals(inBeam, assertIs<FocusMoveDecision.Move>(decision).targetId)
    }

    @Test
    fun modalScopeCannotLeakFocusAtBoundary() {
        val dialog = id("dialog", "confirm")
        val outside = id("content", "outside")
        val decision =
            policy.resolve(
                request(
                    dialog,
                    TvFocusDirection.Right,
                    listOf(
                        node(dialog, 100f, 0f, FocusZone.Dialog),
                        node(outside, 200f, 0f, FocusZone.Content),
                    ),
                    activeScope = "dialog",
                ),
            )

        val stay = assertIs<FocusMoveDecision.Stay>(decision)
        assertEquals(FocusMoveReason.TrappedAtScopeBoundary, stay.reason)
    }

    private fun request(
        current: FocusTargetId,
        direction: TvFocusDirection,
        nodes: List<FocusNode>,
        activeScope: String? = null,
        navigationRail: FocusTargetId? = null,
        lastContent: FocusTargetId? = null,
        layoutDirection: TvLayoutDirection = TvLayoutDirection.LeftToRight,
    ) = DirectionalFocusRequest(
        currentId = current,
        direction = direction,
        nodes = nodes,
        activeScopeId = activeScope,
        navigationRailTargetId = navigationRail,
        lastContentTargetId = lastContent,
        layoutDirection = layoutDirection,
    )

    private fun id(
        scope: String,
        value: String,
    ) = FocusTargetId(scope, value)

    private fun node(
        id: FocusTargetId,
        left: Float,
        top: Float,
        zone: FocusZone,
    ) = FocusNode(
        id = id,
        bounds = FocusBounds(left, top, left + 50f, top + 50f),
        zone = zone,
    )
}
