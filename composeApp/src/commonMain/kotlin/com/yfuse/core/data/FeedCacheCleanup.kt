package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Covers logout/import/removal as well as explicit server-page deletion after splitting storage. */
fun observeFeedCacheCleanup(
    scope: CoroutineScope,
    registry: ServerRegistry,
    cache: LibraryCache,
): Job =
    scope.launch(Dispatchers.Default) {
        registry.data
            .map { data -> data.servers.mapTo(mutableSetOf()) { it.id } }
            .distinctUntilChanged()
            .collect { activeIds ->
                try {
                    cache.clearOrphans(activeIds)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLog.warning(
                        "feature.library",
                        "cache_cleanup_failed",
                        "Orphaned library caches could not be removed",
                        throwable = error,
                    )
                }
            }
    }
