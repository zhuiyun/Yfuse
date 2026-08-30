package com.yfuse.tv.focus

import kotlin.math.abs

/** A currently composed or compose-able focus target. */
data class FocusCandidate(
    val targetId: FocusTargetId,
    val sectionId: String,
    val itemStableId: String,
    val index: Int,
    val enabled: Boolean = true,
) {
    init {
        require(sectionId.isNotBlank()) { "sectionId must not be blank" }
        require(itemStableId.isNotBlank()) { "itemStableId must not be blank" }
        require(index >= 0) { "index must be non-negative" }
    }
}

data class FocusRestoreRequest(
    val context: FocusContext,
    val candidates: List<FocusCandidate>,
    val preferredTargetId: FocusTargetId? = null,
    val preferredSectionId: String? = null,
) {
    init {
        require(preferredSectionId == null || preferredSectionId.isNotBlank()) {
            "preferredSectionId must be null or non-blank"
        }
    }
}

enum class FocusRestoreReason {
    ExactStableItem,
    StableItemMovedSection,
    SameSectionNearestItem,
    PreferredTarget,
    PreferredSectionFirstItem,
    FirstAvailableItem,
    NoAvailableItem,
}

data class FocusRestoreDecision(
    val candidate: FocusCandidate?,
    val anchor: FocusAnchor?,
    val reason: FocusRestoreReason,
) {
    val canRestore: Boolean
        get() = candidate != null && anchor != null
}

/** Stable-ID-first restore policy used after returning, paging, or refreshing content. */
class FocusRestorePolicy {
    fun resolve(
        request: FocusRestoreRequest,
        savedAnchor: FocusAnchor?,
    ): FocusRestoreDecision {
        val available = request.candidates.filter(FocusCandidate::enabled)
        if (available.isEmpty()) {
            return FocusRestoreDecision(
                candidate = null,
                anchor = null,
                reason = FocusRestoreReason.NoAvailableItem,
            )
        }

        val usableAnchor = savedAnchor?.takeIf { it.context == request.context }
        if (usableAnchor != null) {
            available
                .firstOrNull {
                    it.sectionId == usableAnchor.sectionId &&
                        it.itemStableId == usableAnchor.itemStableId
                }?.let { candidate ->
                    return decision(candidate, usableAnchor, FocusRestoreReason.ExactStableItem)
                }

            // A backend may move an item between home sections while retaining its provider ID.
            available
                .firstOrNull { it.itemStableId == usableAnchor.itemStableId }
                ?.let { candidate ->
                    return decision(
                        candidate,
                        usableAnchor.copy(
                            sectionId = candidate.sectionId,
                            scrollOffset = 0,
                        ),
                        FocusRestoreReason.StableItemMovedSection,
                    )
                }

            available
                .filter { it.sectionId == usableAnchor.sectionId }
                .minWithOrNull(
                    compareBy<FocusCandidate> { abs(it.index - usableAnchor.fallbackIndex) }
                        .thenBy { it.index }
                        .thenBy { it.itemStableId },
                )?.let { candidate ->
                    return decision(
                        candidate,
                        usableAnchor,
                        FocusRestoreReason.SameSectionNearestItem,
                    )
                }
        }

        request.preferredTargetId
            ?.let { preferred -> available.firstOrNull { it.targetId == preferred } }
            ?.let { candidate ->
                return decision(
                    candidate,
                    baseAnchor(request.context, candidate),
                    FocusRestoreReason.PreferredTarget,
                )
            }

        request.preferredSectionId
            ?.let { section ->
                available
                    .asSequence()
                    .filter { it.sectionId == section }
                    .minWithOrNull(compareBy<FocusCandidate> { it.index }.thenBy { it.itemStableId })
            }?.let { candidate ->
                return decision(
                    candidate,
                    baseAnchor(request.context, candidate),
                    FocusRestoreReason.PreferredSectionFirstItem,
                )
            }

        val first =
            available.minWithOrNull(
                compareBy<FocusCandidate> { it.index }
                    .thenBy { it.sectionId }
                    .thenBy { it.itemStableId },
            ) ?: error("available candidates unexpectedly became empty")
        return decision(
            first,
            baseAnchor(request.context, first),
            FocusRestoreReason.FirstAvailableItem,
        )
    }

    private fun decision(
        candidate: FocusCandidate,
        base: FocusAnchor,
        reason: FocusRestoreReason,
    ): FocusRestoreDecision =
        FocusRestoreDecision(
            candidate = candidate,
            anchor =
                base.copy(
                    sectionId = candidate.sectionId,
                    itemStableId = candidate.itemStableId,
                    fallbackIndex = candidate.index,
                ),
            reason = reason,
        )

    private fun baseAnchor(
        context: FocusContext,
        candidate: FocusCandidate,
    ): FocusAnchor =
        FocusAnchor(
            route = context.route,
            serverId = context.serverId,
            profileId = context.profileId,
            sectionId = candidate.sectionId,
            itemStableId = candidate.itemStableId,
            fallbackIndex = candidate.index,
            scrollOffset = 0,
        )
}
