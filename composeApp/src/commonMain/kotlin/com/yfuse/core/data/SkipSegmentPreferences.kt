package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One show's hand-entered 片头 / 片尾 boundaries, in whole seconds. */
@Serializable
data class SkipTimes(
    /** Measured from the start of the file — an opening is at a fixed offset into it. */
    val introStartSeconds: Long = 0L,
    val introEndSeconds: Long = 0L,
    /**
     * 片尾 begins this many seconds **before the end** of whatever is playing.
     *
     * Closing credits are the one boundary that is not at a fixed offset from the start:
     * episodes of the same show routinely differ by a minute or two of runtime, so an
     * absolute 片尾开始 tuned on one episode lands mid-scene on the next and leaves the
     * 跳过片尾 pill hanging over the wrong part of the file. The distance from the end is
     * what actually stays constant, so that is what is stored.
     *
     * Entries written before this changed carried an absolute start under a different
     * name. There is no runtime to convert those against at load time, and reading an
     * absolute position as a distance from the end would be far worse than reading
     * nothing, so they decode to 0 — 片尾 is simply unset again for those shows.
     */
    val creditsLeadSeconds: Long = 0L,
    /** Only for naming the row in 我的; never used to match. */
    val seriesName: String = "",
) {
    /**
     * False once every boundary has been cleared, which is how an entry gets dropped.
     *
     * Deliberately looser than [hasIntro]: the boundaries are set one at a time from the
     * player, so a 片头开始 with no 片头结束 yet is a half-finished entry, not an empty one.
     * Treating it as empty threw the first tap away and made the row look broken.
     */
    val configured: Boolean
        get() = introStartSeconds > 0L || introEndSeconds > 0L || creditsLeadSeconds > 0L

    /** True once the intro describes a real interval, rather than half of one. */
    val hasIntro: Boolean
        get() = introEndSeconds > introStartSeconds

    val hasCredits: Boolean
        get() = creditsLeadSeconds > 0L
}

/**
 * Hand-entered 片头 / 片尾 boundaries, for libraries whose server never analysed them.
 *
 * Emby only reports chapter-derived segments for content that has been run through its
 * analysis, which for most personal libraries means the 跳过片头 button never appears at all.
 * These times stand in for that.
 *
 * **Scoped per series, not globally.** An opening is a property of a show: every episode of
 * it shares one, and it stays put across seasons even when runtimes drift by a minute.
 * Films therefore have no entry and are never affected.
 *
 * The intro is stored as absolute positions and the credits as a distance from the end,
 * because that is what stays constant for each — see [SkipTimes.creditsLeadSeconds].
 */
class SkipSegmentPreferences(private val settings: Settings) {
    private companion object {
        const val KEY_SERIES = "player.skip.bySeries"
        const val KEY_AUTO_SKIP = "player.skip.auto"

        /** Longer than any plausible runtime, and a guard against a mistyped extra digit. */
        const val MAX_SECONDS = 10 * 60 * 60L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val seriesSerializer = MapSerializer(String.serializer(), SkipTimes.serializer())

    private val _bySeries = MutableStateFlow(load())
    val bySeries: StateFlow<Map<String, SkipTimes>> = _bySeries.asStateFlow()

    /**
     * Whether entering a configured segment skips it on its own. Off by default: silently
     * moving the playhead is a surprising thing for a player to do unasked, and the manual
     * pill already covers the case.
     */
    private val _autoSkip = MutableStateFlow(settings.getBoolean(KEY_AUTO_SKIP, false))
    val autoSkip: StateFlow<Boolean> = _autoSkip.asStateFlow()

    fun setAutoSkip(enabled: Boolean) {
        _autoSkip.value = enabled
        settings.putBoolean(KEY_AUTO_SKIP, enabled)
    }

    fun timesFor(seriesId: String?): SkipTimes? =
        seriesId?.takeIf { it.isNotBlank() }?.let { _bySeries.value[it] }

    fun set(seriesId: String, times: SkipTimes) {
        if (seriesId.isBlank()) return
        val clamped = times.copy(
            introStartSeconds = times.introStartSeconds.coerceIn(0L, MAX_SECONDS),
            introEndSeconds = times.introEndSeconds.coerceIn(0L, MAX_SECONDS),
            creditsLeadSeconds = times.creditsLeadSeconds.coerceIn(0L, MAX_SECONDS),
            seriesName = times.seriesName.trim().take(80),
        )
        // An entry that configures nothing is indistinguishable from having no entry, and
        // keeping it would leave a permanently empty row in 我的.
        _bySeries.value = if (clamped.configured) {
            _bySeries.value + (seriesId to clamped)
        } else {
            _bySeries.value - seriesId
        }
        persist()
    }

    fun clear(seriesId: String) {
        if (seriesId !in _bySeries.value) return
        _bySeries.value = _bySeries.value - seriesId
        persist()
    }

    /**
     * The server's segments for one entry, with this series' overrides substituted in.
     *
     * Takes the server list rather than being read alongside it so there is one definition
     * of "which segment is in force", instead of every caller re-deciding.
     */
    fun applyTo(
        seriesId: String?,
        serverSegments: List<PlaybackSegment>,
        /** Needed to place 片尾, which is stored as a distance back from this. */
        durationMs: Long,
    ): List<PlaybackSegment> {
        val custom = customSegments(timesFor(seriesId), durationMs)
        if (custom.isEmpty()) return serverSegments
        // Replace rather than add: two competing 跳过片头 pills for one opening would be
        // worse than either alone. Each type is independent, so configuring only the intro
        // leaves the server's credits in place.
        val overridden = custom.map { it.type }.toSet()
        return serverSegments.filterNot { it.type in overridden } + custom
    }

    private fun customSegments(times: SkipTimes?, durationMs: Long): List<PlaybackSegment> {
        if (times == null) return emptyList()
        return buildList {
            // An end at or before the start describes no interval at all; a half-entered
            // intro is kept (see SkipTimes.configured) but offers nothing to skip yet.
            if (times.hasIntro) {
                add(
                    PlaybackSegment(
                        type = PlaybackSegmentType.Intro,
                        startMs = times.introStartSeconds * 1000,
                        endMs = times.introEndSeconds * 1000,
                    ),
                )
            }
            // Credits run to the end of the file, which only the player knows — hence no
            // end, and hence nothing to place the start against until a duration arrives.
            // A lead longer than the whole file would make the entire item 片尾, which is
            // a mistyped digit rather than an instruction; leave it out.
            val creditsStartMs = durationMs - times.creditsLeadSeconds * 1000
            if (times.hasCredits && durationMs > 0L && creditsStartMs > 0L) {
                add(
                    PlaybackSegment(
                        type = PlaybackSegmentType.Credits,
                        startMs = creditsStartMs,
                        endMs = null,
                    ),
                )
            }
        }
    }

    private fun load(): Map<String, SkipTimes> {
        val raw = settings.getStringOrNull(KEY_SERIES) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(seriesSerializer, raw)
        }.onFailure {
            AppLog.warning(
                category = "player.skip",
                event = "stored_times_unreadable",
                message = "Stored skip times could not be read and were ignored",
                throwable = it,
            )
        }.getOrDefault(emptyMap())
    }

    private fun persist() {
        val current = _bySeries.value
        if (current.isEmpty()) {
            settings.remove(KEY_SERIES)
            return
        }
        settings.putString(KEY_SERIES, json.encodeToString(seriesSerializer, current))
    }
}
