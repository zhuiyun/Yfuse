package com.yfuse.feature.player

import com.yfuse.core.model.languageDisplayName

/** Resolves the engine track that best answers to a preferred display language. */
internal fun List<EngineTrack>.matchingLanguage(language: String): String? {
    val wanted = language.trim().lowercase()
    if (wanted.isEmpty()) return null
    val wantedDisplay = languageDisplayName(wanted)
    return firstOrNull { it.language?.lowercase() == wanted }?.id
        ?: firstOrNull {
            languageDisplayName(it.language).equals(wantedDisplay, ignoreCase = true)
        }?.id
        ?: firstOrNull { it.language?.lowercase()?.startsWith(wanted.take(2)) == true }?.id
        ?: firstOrNull { it.label.contains(language, ignoreCase = true) }?.id
}

/** Best remaining physical file after every engine rejected the selected version. */
internal fun PlayerMediaItem.nextFallbackVersionId(tried: Set<String>): String? =
    versions
        .sortedWith(
            compareByDescending<PlayerMediaVersion> { it.sourceWidth ?: 0 }
                .thenByDescending { it.sourceBitrateBps ?: 0 },
        ).firstOrNull { it.id !in tried }
        ?.id

/** Manual selection starts a new recovery budget; automatic recovery preserves history. */
internal fun updatedVersionAttempts(
    tried: Set<String>,
    selected: String,
    automaticRecovery: Boolean,
): Set<String> = if (automaticRecovery) tried + selected else setOf(selected)
