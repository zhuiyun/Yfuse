package com.yfuse.core.offline

/** Nothing is cleaned or published until a complete, authoritative index has been read. */
internal fun recoverOfflineIndex(
    load: () -> List<OfflineMedia>,
    cleanup: (List<OfflineMedia>) -> Unit,
    recover: (List<OfflineMedia>) -> List<OfflineMedia>,
    persist: (List<OfflineMedia>, List<OfflineMedia>) -> Unit,
): List<OfflineMedia> {
    val stored = load()
    cleanup(stored)
    val recovered = recover(stored)
    persist(stored, recovered)
    return recovered
}
