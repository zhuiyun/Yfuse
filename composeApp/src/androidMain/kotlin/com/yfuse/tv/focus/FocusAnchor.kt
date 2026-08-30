package com.yfuse.tv.focus

/**
 * Identifies the piece of navigation state that owns a focus anchor.
 *
 * Server and profile are part of the identity on purpose: restoring an item from another
 * account is both surprising and a potential data leak on a shared television.
 */
data class FocusContext(
    val route: String,
    val serverId: String? = null,
    val profileId: String? = null,
) {
    init {
        require(route.isNotBlank()) { "route must not be blank" }
        require(serverId == null || serverId.isNotBlank()) { "serverId must be null or non-blank" }
        require(profileId == null || profileId.isNotBlank()) { "profileId must be null or non-blank" }
    }
}

/**
 * Stable focus and scroll state for one item in a TV content section.
 *
 * [itemStableId] must be a provider-backed identifier, never a lazy-list index. The index is
 * retained only as a deterministic fallback when the item disappears after a refresh.
 */
data class FocusAnchor(
    val route: String,
    val serverId: String? = null,
    val profileId: String? = null,
    val sectionId: String,
    val itemStableId: String,
    val fallbackIndex: Int,
    val scrollOffset: Int,
) {
    init {
        require(route.isNotBlank()) { "route must not be blank" }
        require(serverId == null || serverId.isNotBlank()) { "serverId must be null or non-blank" }
        require(profileId == null || profileId.isNotBlank()) { "profileId must be null or non-blank" }
        require(sectionId.isNotBlank()) { "sectionId must not be blank" }
        require(itemStableId.isNotBlank()) { "itemStableId must not be blank" }
        require(fallbackIndex >= 0) { "fallbackIndex must be non-negative" }
        require(scrollOffset >= 0) { "scrollOffset must be non-negative" }
    }

    val context: FocusContext
        get() = FocusContext(route = route, serverId = serverId, profileId = profileId)

    fun withPosition(
        fallbackIndex: Int,
        scrollOffset: Int,
    ): FocusAnchor = copy(fallbackIndex = fallbackIndex, scrollOffset = scrollOffset)
}

/** A stable identity used by the Compose requester registry and direction graph. */
data class FocusTargetId(
    val scopeId: String,
    val value: String,
) {
    init {
        require(scopeId.isNotBlank()) { "scopeId must not be blank" }
        require(value.isNotBlank()) { "value must not be blank" }
    }
}
