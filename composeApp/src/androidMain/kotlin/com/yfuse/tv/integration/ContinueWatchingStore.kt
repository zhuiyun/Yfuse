package com.yfuse.tv.integration

import android.content.Context
import android.content.SharedPreferences
import com.yfuse.core.logging.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class PendingContinueWatchingPublication(
    val revision: Long,
    val entries: List<ContinueWatchingEntry>,
)

internal interface ContinueWatchingStore {
    fun apply(decision: ContinueWatchingDecision): Boolean

    fun pendingPublication(): PendingContinueWatchingPublication?

    fun markPublished(revision: Long)

    fun retainScopes(validScopes: Set<ContinueWatchingScope>): Boolean

    fun clearScope(scope: ContinueWatchingScope): Boolean

    fun clearAll(): Boolean
}

/**
 * Durable, credential-free mirror of the exact snapshot that still needs to reach TV surfaces.
 * Writes commit before WorkManager is scheduled so a process death cannot lose a completion or
 * logout deletion.
 */
internal class SharedPreferencesContinueWatchingStore(
    context: Context,
    private val policy: ContinueWatchingPolicy = ContinueWatchingPolicy(),
) : ContinueWatchingStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun apply(decision: ContinueWatchingDecision): Boolean =
        synchronized(PROCESS_LOCK) {
            if (decision is ContinueWatchingDecision.Ignore) return@synchronized false
            val current = load()
            val identity =
                when (decision) {
                    is ContinueWatchingDecision.Upsert -> decision.entry.identity
                    is ContinueWatchingDecision.Delete -> decision.identity
                    ContinueWatchingDecision.Ignore -> error("handled above")
                }
            val withoutIdentity = current.entries.filterNot { it.identity == identity }
            val changedScopeEntries =
                when (decision) {
                    is ContinueWatchingDecision.Upsert -> withoutIdentity + decision.entry
                    is ContinueWatchingDecision.Delete -> withoutIdentity
                    ContinueWatchingDecision.Ignore -> withoutIdentity
                }
            val selectedScope = policy.selectForScope(identity.scope, changedScopeEntries)
            val nextEntries =
                (
                    changedScopeEntries.filterNot { it.identity.scope == identity.scope } +
                        selectedScope
                ).sortedByDescending(ContinueWatchingEntry::lastEngagementEpochMs)
                    .take(MAX_STORED_ENTRIES)
            persist(
                current.copy(
                    revision = nextRevision(current.revision),
                    entries = nextEntries,
                ),
            )
            true
        }

    override fun pendingPublication(): PendingContinueWatchingPublication? =
        synchronized(PROCESS_LOCK) {
            val state = load()
            if (state.publishedRevision == state.revision) {
                null
            } else {
                PendingContinueWatchingPublication(
                    revision = state.revision,
                    entries = policy.selectForPublication(state.entries),
                )
            }
        }

    override fun markPublished(revision: Long) {
        synchronized(PROCESS_LOCK) {
            val current = load()
            if (current.revision == revision && current.publishedRevision != revision) {
                persist(current.copy(publishedRevision = revision))
            }
        }
    }

    override fun retainScopes(validScopes: Set<ContinueWatchingScope>): Boolean =
        synchronized(PROCESS_LOCK) {
            val current = load()
            val retained = current.entries.filter { it.identity.scope in validScopes }
            if (retained.size == current.entries.size) return@synchronized false
            persist(
                current.copy(
                    revision = nextRevision(current.revision),
                    entries = retained,
                ),
            )
            true
        }

    override fun clearScope(scope: ContinueWatchingScope): Boolean =
        synchronized(PROCESS_LOCK) {
            val current = load()
            val retained = current.entries.filterNot { it.identity.scope == scope }
            if (retained.size == current.entries.size && current.publishedRevision != current.revision) {
                return@synchronized false
            }
            persist(
                current.copy(
                    revision = nextRevision(current.revision),
                    entries = retained,
                ),
            )
            true
        }

    override fun clearAll(): Boolean =
        synchronized(PROCESS_LOCK) {
            val current = load()
            if (current.entries.isEmpty() && current.publishedRevision != current.revision) {
                return@synchronized false
            }
            persist(
                current.copy(
                    revision = nextRevision(current.revision),
                    entries = emptyList(),
                ),
            )
            true
        }

    private fun load(): PersistedContinueWatchingState {
        val payload = preferences.getString(KEY_STATE, null) ?: return PersistedContinueWatchingState()
        return runCatching {
            JSON.decodeFromString(PersistedContinueWatchingState.serializer(), payload)
        }.getOrElse { error ->
            AppLog.warning(
                category = "tv.continue_watching",
                event = "state_discarded",
                message = "Discarded an unreadable credential-free TV continuation snapshot",
                throwable = error,
            )
            preferences.edit().remove(KEY_STATE).commit()
            PersistedContinueWatchingState()
        }
    }

    private fun persist(state: PersistedContinueWatchingState) {
        val saved =
            preferences
                .edit()
                .putString(KEY_STATE, JSON.encodeToString(state))
                .commit()
        check(saved) { "Could not persist TV continuation snapshot" }
    }

    private fun nextRevision(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private companion object {
        const val PREFERENCES_NAME = "yfuse.tv.continue_watching.v1"
        const val KEY_STATE = "state"
        const val MAX_STORED_ENTRIES = 100
        val PROCESS_LOCK = Any()
        val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            }
    }
}

@Serializable
private data class PersistedContinueWatchingState(
    val schemaVersion: Int = 1,
    val revision: Long = 0L,
    val publishedRevision: Long = 0L,
    val entries: List<ContinueWatchingEntry> = emptyList(),
)

/** Small index of rows owned by Yfuse; TvProvider remains the source of truth for row existence. */
internal class TvProviderPublicationIndex(
    context: Context,
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(INDEX_PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun watchNextIds(): Set<String> = preferences.getStringSet(KEY_WATCH_NEXT, emptySet()).orEmpty().toSet()

    fun previewIds(): Set<String> = preferences.getStringSet(KEY_PREVIEW, emptySet()).orEmpty().toSet()

    fun replaceWatchNextIds(ids: Set<String>) {
        check(preferences.edit().putStringSet(KEY_WATCH_NEXT, ids.toSet()).commit())
    }

    fun replacePreviewIds(ids: Set<String>) {
        check(preferences.edit().putStringSet(KEY_PREVIEW, ids.toSet()).commit())
    }

    private companion object {
        const val INDEX_PREFERENCES_NAME = "yfuse.tv.provider_index.v1"
        const val KEY_WATCH_NEXT = "watch_next_ids"
        const val KEY_PREVIEW = "preview_ids"
    }
}
