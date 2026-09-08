package com.yfuse.core.offline

/** The marker and imported rows share one database transaction, so a leftover v1 key cannot resurrect deletes. */
internal fun loadOfflineIndexOnce(
    load: () -> List<OfflineMedia>,
    migrationComplete: () -> Boolean,
    readLegacy: () -> List<OfflineMedia>?,
    migrateAtomically: (List<OfflineMedia>) -> Unit,
    discardLegacy: () -> Unit,
): List<OfflineMedia> {
    val current = load()
    val selected =
        if (migrationComplete()) {
            current
        } else {
            (if (current.isEmpty()) readLegacy() else null)
                .orEmpty()
                .ifEmpty { current }
                .also(migrateAtomically)
        }
    discardLegacy()
    return selected
}

/** Never remember a new episode as seen until its batch is durably queued. */
internal fun commitOfflineAutoDiscovery(
    enqueue: () -> Unit,
    rememberEpisodes: () -> Unit,
) {
    enqueue()
    rememberEpisodes()
}
