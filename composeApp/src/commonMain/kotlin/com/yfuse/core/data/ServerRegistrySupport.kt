package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.model.ServersData
import com.yfuse.core.model.normalizedRoutes
import com.yfuse.core.network.validateEmbyServerEndpoint

/**
 * Carries the cosmetic and route settings of a saved server across a re-login or an edit.
 *
 * A fresh authentication only knows the address it was performed against, so without this a
 * user who re-enters their password loses the icon they picked and every backup route they
 * configured. The freshly authenticated address becomes the primary and the active one — it
 * has just been proven to work, and it is what [SavedServer.id] was derived from — while the
 * old backups survive under their existing ids.
 */
internal fun SavedServer.carryingUserSettingsFrom(existing: SavedServer?): SavedServer {
    if (existing == null) return this
    val backups =
        existing.effectiveRoutes
            .drop(1)
            .filterNot { it.url == baseUrl }
    val routes =
        if (backups.isEmpty()) {
            emptyList()
        } else {
            val primaryName = existing.effectiveRoutes.first().name
            listOf(ServerRoute(ServerRoute.PRIMARY_ID, primaryName, baseUrl)) + backups
        }
    return copy(
        cloudAccessToken = cloudAccessToken ?: existing.cloudAccessToken,
        cloudOwnerAccessToken = cloudOwnerAccessToken ?: existing.cloudOwnerAccessToken,
        routes = routes.normalizedRoutes(),
        activeRouteId = ServerRoute.PRIMARY_ID.takeIf { routes.isNotEmpty() },
        iconEmoji = iconEmoji ?: existing.iconEmoji,
        iconTint = iconTint ?: existing.iconTint,
        iconUrl = iconUrl ?: existing.iconUrl,
        localCleartextConfirmed = localCleartextConfirmed || existing.localCleartextConfirmed,
    )
}

/** Enforces the registry invariant before any credential can be persisted or exposed. */
internal fun SavedServer.requiringAllowedTransport(
    prefix: String,
    allowUnconfirmedLocalForTests: Boolean = false,
): SavedServer {
    val endpoints =
        buildList {
            add(primaryUrl)
            add(baseUrl)
            addAll(effectiveRoutes.map { it.url })
        }
    endpoints.forEach { endpoint ->
        val validation = validateEmbyServerEndpoint(endpoint, localCleartextConfirmed)
        require(validation.allowed || allowUnconfirmedLocalForTests && validation.requiresCleartextConfirmation) {
            validation.message ?: "${prefix}地址不安全"
        }
    }
    return this
}

/** Later occurrences win so reusing an older alias makes it recent again. */
internal fun recentPreviousIds(
    currentId: String,
    vararg histories: Iterable<String>,
): Set<String> {
    val newestFirst =
        buildList {
            histories.forEach { addAll(it) }
        }.asReversed()
            .asSequence()
            .filter { it != currentId }
            .distinct()
            .take(MAX_SERVER_PREVIOUS_IDS)
            .toList()

    return linkedSetOf<String>().apply {
        newestFirst.asReversed().forEach { add(it) }
    }
}

internal fun ServersData.withBoundedPreviousIds(): ServersData =
    copy(
        servers =
            servers.map { server ->
                server
                    .copy(previousIds = recentPreviousIds(server.id, server.previousIds))
                    .withNormalizedRoutes()
            },
    )

/**
 * One emoji at most. Stored icons are rendered as text, so an unbounded string would let a
 * long paste stretch the card's header row and push the name out of it.
 */
internal fun sanitizeIconEmoji(value: String?): String? =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
        ?.take(SERVER_ICON_EMOJI_MAX_CHARS)

/** A surrogate pair plus a variation selector or two — enough for one composed emoji. */
internal const val SERVER_ICON_EMOJI_MAX_CHARS = 8
