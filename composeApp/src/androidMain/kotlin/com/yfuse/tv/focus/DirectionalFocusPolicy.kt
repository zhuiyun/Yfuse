package com.yfuse.tv.focus

import kotlin.math.abs
import kotlin.math.max

enum class TvFocusDirection {
    Up,
    Down,
    Left,
    Right,
}

enum class TvLayoutDirection {
    LeftToRight,
    RightToLeft,
}

enum class FocusZone {
    Navigation,
    Content,
    Dialog,
    Player,
    Overlay,
}

data class FocusBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() }) {
            "focus bounds must be finite"
        }
        require(right >= left) { "right must be greater than or equal to left" }
        require(bottom >= top) { "bottom must be greater than or equal to top" }
    }

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f
}

data class FocusNode(
    val id: FocusTargetId,
    val bounds: FocusBounds,
    val zone: FocusZone,
    val enabled: Boolean = true,
    val order: Int = 0,
    val neighbors: Map<TvFocusDirection, FocusTargetId> = emptyMap(),
)

data class DirectionalFocusRequest(
    val currentId: FocusTargetId,
    val direction: TvFocusDirection,
    val nodes: List<FocusNode>,
    val activeScopeId: String? = null,
    val navigationRailTargetId: FocusTargetId? = null,
    val lastContentTargetId: FocusTargetId? = null,
    val layoutDirection: TvLayoutDirection = TvLayoutDirection.LeftToRight,
)

enum class FocusMoveReason {
    ExplicitNeighbor,
    RestoreContentFromNavigation,
    EnterNavigationRail,
    SpatialCandidate,
    InitialScopeTarget,
    TrappedAtScopeBoundary,
    DelegateToCompose,
}

sealed interface FocusMoveDecision {
    val reason: FocusMoveReason

    data class Move(
        val targetId: FocusTargetId,
        override val reason: FocusMoveReason,
    ) : FocusMoveDecision

    data class Stay(
        override val reason: FocusMoveReason,
    ) : FocusMoveDecision

    data class Delegate(
        override val reason: FocusMoveReason = FocusMoveReason.DelegateToCompose,
    ) : FocusMoveDecision
}

/**
 * Deterministic, non-wrapping TV navigation policy.
 *
 * Compose remains free to handle ordinary movement. This policy is useful for explicit graph
 * edges, navigation rail hand-off, and modal focus trapping where platform heuristics are not
 * sufficient or are unstable after a lazy-list refresh.
 */
class DirectionalFocusPolicy {
    fun resolve(request: DirectionalFocusRequest): FocusMoveDecision {
        val eligible =
            request.nodes.filter { node ->
                node.enabled &&
                    (request.activeScopeId == null || node.id.scopeId == request.activeScopeId)
            }
        val current = eligible.firstOrNull { it.id == request.currentId }
        if (current == null) {
            val first = eligible.minWithOrNull(compareBy<FocusNode> { it.order }.thenBy { it.id.value })
            return if (first != null && request.activeScopeId != null) {
                FocusMoveDecision.Move(first.id, FocusMoveReason.InitialScopeTarget)
            } else {
                FocusMoveDecision.Delegate()
            }
        }

        current.neighbors[request.direction]
            ?.let { explicit -> eligible.firstOrNull { it.id == explicit } }
            ?.let { target ->
                return FocusMoveDecision.Move(target.id, FocusMoveReason.ExplicitNeighbor)
            }

        val towardContent =
            if (request.layoutDirection == TvLayoutDirection.LeftToRight) {
                TvFocusDirection.Right
            } else {
                TvFocusDirection.Left
            }
        val towardRail =
            if (request.layoutDirection == TvLayoutDirection.LeftToRight) {
                TvFocusDirection.Left
            } else {
                TvFocusDirection.Right
            }

        if (current.zone == FocusZone.Navigation && request.direction == towardContent) {
            request.lastContentTargetId
                ?.let { id -> eligible.firstOrNull { it.id == id && it.zone == FocusZone.Content } }
                ?.let { target ->
                    return FocusMoveDecision.Move(
                        target.id,
                        FocusMoveReason.RestoreContentFromNavigation,
                    )
                }
        }

        val sameZoneSpatial = spatialCandidate(current, request.direction, eligible, sameZoneOnly = true)
        if (sameZoneSpatial != null) {
            return FocusMoveDecision.Move(sameZoneSpatial.id, FocusMoveReason.SpatialCandidate)
        }

        if (current.zone == FocusZone.Content && request.direction == towardRail) {
            request.navigationRailTargetId
                ?.let { id -> eligible.firstOrNull { it.id == id && it.zone == FocusZone.Navigation } }
                ?.let { target ->
                    return FocusMoveDecision.Move(target.id, FocusMoveReason.EnterNavigationRail)
                }
        }

        spatialCandidate(current, request.direction, eligible, sameZoneOnly = false)?.let { target ->
            return FocusMoveDecision.Move(target.id, FocusMoveReason.SpatialCandidate)
        }

        return if (request.activeScopeId != null) {
            FocusMoveDecision.Stay(FocusMoveReason.TrappedAtScopeBoundary)
        } else {
            FocusMoveDecision.Delegate()
        }
    }

    private fun spatialCandidate(
        current: FocusNode,
        direction: TvFocusDirection,
        candidates: List<FocusNode>,
        sameZoneOnly: Boolean,
    ): FocusNode? =
        candidates
            .asSequence()
            .filter { it.id != current.id }
            .filter { !sameZoneOnly || it.zone == current.zone }
            .filter { isInDirection(current.bounds, it.bounds, direction) }
            .map { candidate ->
                ScoredNode(
                    node = candidate,
                    inBeam = isInBeam(current.bounds, candidate.bounds, direction),
                    majorDistance = majorDistance(current.bounds, candidate.bounds, direction),
                    minorDistance = minorDistance(current.bounds, candidate.bounds, direction),
                )
            }.minWithOrNull(
                compareByDescending<ScoredNode> { it.inBeam }
                    .thenBy { it.majorDistance }
                    .thenBy { it.minorDistance }
                    .thenBy { it.node.order }
                    .thenBy { it.node.id.value },
            )?.node

    private data class ScoredNode(
        val node: FocusNode,
        val inBeam: Boolean,
        val majorDistance: Float,
        val minorDistance: Float,
    )

    private fun isInDirection(
        source: FocusBounds,
        target: FocusBounds,
        direction: TvFocusDirection,
    ): Boolean =
        when (direction) {
            TvFocusDirection.Left -> target.centerX < source.centerX
            TvFocusDirection.Right -> target.centerX > source.centerX
            TvFocusDirection.Up -> target.centerY < source.centerY
            TvFocusDirection.Down -> target.centerY > source.centerY
        }

    private fun isInBeam(
        source: FocusBounds,
        target: FocusBounds,
        direction: TvFocusDirection,
    ): Boolean =
        when (direction) {
            TvFocusDirection.Left,
            TvFocusDirection.Right,
            -> target.bottom > source.top && target.top < source.bottom

            TvFocusDirection.Up,
            TvFocusDirection.Down,
            -> target.right > source.left && target.left < source.right
        }

    private fun majorDistance(
        source: FocusBounds,
        target: FocusBounds,
        direction: TvFocusDirection,
    ): Float =
        when (direction) {
            TvFocusDirection.Left -> max(0f, source.left - target.right)
            TvFocusDirection.Right -> max(0f, target.left - source.right)
            TvFocusDirection.Up -> max(0f, source.top - target.bottom)
            TvFocusDirection.Down -> max(0f, target.top - source.bottom)
        }

    private fun minorDistance(
        source: FocusBounds,
        target: FocusBounds,
        direction: TvFocusDirection,
    ): Float =
        when (direction) {
            TvFocusDirection.Left,
            TvFocusDirection.Right,
            -> abs(target.centerY - source.centerY)

            TvFocusDirection.Up,
            TvFocusDirection.Down,
            -> abs(target.centerX - source.centerX)
        }
}
