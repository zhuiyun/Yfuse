package com.yfuse.core.personal

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Actual viewing on this device. These records are deliberately outside the sync document. */
@Serializable
data class LocalViewingSession(
    val id: String,
    val media: PersonalMediaRef,
    val seriesKey: String? = null,
    val seriesTitle: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val posterItemId: String? = null,
    val posterTag: String? = null,
    val startedAtEpochMs: Long,
    val lastWatchedAtEpochMs: Long = startedAtEpochMs,
    val watchedByDay: Map<String, Long> = emptyMap(),
    val completed: Boolean = false,
) {
    val watchedMs: Long get() = watchedByDay.values.sum()
    val isEpisode: Boolean get() = seriesKey != null || media.mediaType.equals("Episode", true)
    val displayTitle: String get() = seriesTitle?.takeIf(String::isNotBlank) ?: media.title
}

class LocalViewingStore(
    settings: Settings,
    private val personal: PersonalLibraryRepository,
) {
    private val lock = personal.coordinationLock
    private val scoped = PersonalScopedSettings(settings) { personal.storageNamespace }
    private val json = Json { ignoreUnknownKeys = true }
    private var loadedNamespace = ""
    private var allSessions = emptyList<LocalViewingSession>()
    private val mutableSessions = MutableStateFlow<List<LocalViewingSession>>(emptyList())
    val sessions: StateFlow<List<LocalViewingSession>> = mutableSessions.asStateFlow()
    val scopeToken: String get() = personal.scopeToken

    init {
        personal.observeChanges {
            synchronized(lock) {
                if (loadedNamespace != personal.storageNamespace) {
                    loadedNamespace = personal.storageNamespace
                    allSessions =
                        scoped.keys
                            .filter { it.startsWith(PREFIX) }
                            .mapNotNull { key ->
                                scoped.getStringOrNull(key)?.let { raw ->
                                    runCatching { json.decodeFromString<LocalViewingSession>(raw) }.getOrNull()
                                }
                            }.filter { it.watchedMs > 0L }
                            .sortedByDescending { it.lastWatchedAtEpochMs }
                }
                publish()
            }
        }
    }

    /** A cumulative snapshot makes retries idempotent; a stale player cannot write another profile. */
    fun record(
        session: LocalViewingSession,
        expectedScopeToken: String,
    ): Boolean =
        synchronized(lock) {
            if (expectedScopeToken != scopeToken ||
                session.id.isBlank() ||
                session.watchedMs <= 0L ||
                session.watchedByDay.any { (day, duration) ->
                    duration <= 0L || runCatching { LocalDate.parse(day) }.isFailure
                } ||
                session.media.serverId?.let(personal::canAccessServer) == false
            ) {
                return@synchronized false
            }
            val previous = allSessions.firstOrNull { it.id == session.id }
            if (previous != null &&
                (previous.media.identity != session.media.identity || previous.completed && !session.completed)
            ) {
                return@synchronized false
            }
            if (previous != null &&
                previous.watchedByDay.any { (day, duration) ->
                    (session.watchedByDay[day] ?: 0L) < duration
                }
            ) {
                return@synchronized false
            }
            if (previous == session) return@synchronized true
            runCatching { scoped.putString(PREFIX + session.id, json.encodeToString(session)) }
                .getOrElse { return@synchronized false }
            allSessions =
                (allSessions.filterNot { it.id == session.id } + session)
                    .sortedByDescending { it.lastWatchedAtEpochMs }
            publish()
            true
        }

    private fun publish() {
        mutableSessions.value = allSessions.filter { it.media.serverId?.let(personal::canAccessServer) != false }
    }

    private companion object {
        const val PREFIX = "viewing.session."
    }
}

data class ViewingSummary(
    val watchedMs: Long,
    val movies: Int,
    val series: Int,
    val episodes: Int,
    val days: Int,
)

fun viewingSummary(
    sessions: List<LocalViewingSession>,
    from: LocalDate? = null,
    through: LocalDate? = null,
): ViewingSummary {
    val active = sessions.filter { it.watchedInRange(from, through) > 0L }
    val dates = active.flatMap { it.watchedByDay.keys }.filter { inViewingRange(it, from, through) }.toSet()
    return ViewingSummary(
        watchedMs = active.sumOf { it.watchedInRange(from, through) },
        movies =
            active
                .filter { it.media.mediaType.equals("Movie", true) }
                .map { it.media.identity }
                .distinct()
                .size,
        series =
            active
                .filter { it.isEpisode }
                .map { it.seriesKey ?: it.media.identity }
                .distinct()
                .size,
        episodes =
            active
                .filter { it.isEpisode }
                .map { it.media.identity }
                .distinct()
                .size,
        days = dates.size,
    )
}

fun LocalViewingSession.watchedInRange(
    from: LocalDate?,
    through: LocalDate?,
): Long = watchedByDay.filterKeys { inViewingRange(it, from, through) }.values.sum()

private fun inViewingRange(
    day: String,
    from: LocalDate?,
    through: LocalDate?,
): Boolean = (from == null || day >= from.toString()) && (through == null || day <= through.toString())

/** Monday-first cells, including only the padding needed for complete weeks. */
fun viewingMonthCells(month: YearMonth): List<LocalDate?> {
    val padding = month.atDay(1).dayOfWeek.value - 1
    val cells = List(padding) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    return cells + List((7 - cells.size % 7) % 7) { null }
}

/** Uses the civil day at the time of watching, including DST and midnight boundaries. */
fun viewingDayDurations(
    endEpochMs: Long,
    watchedMs: Long,
    zone: ZoneId,
): Map<String, Long> {
    if (watchedMs <= 0L) return emptyMap()
    var cursor = endEpochMs - watchedMs
    val result = linkedMapOf<String, Long>()
    while (cursor < endEpochMs) {
        val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        val midnight =
            day
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        val end = minOf(endEpochMs, midnight)
        result[day.toString()] = (result[day.toString()] ?: 0L) + end - cursor
        cursor = end
    }
    return result
}
