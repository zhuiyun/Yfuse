package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.PlaybackSegment
import com.yfuse.core.model.PlaybackSegmentType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
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
     * name. [legacyCreditsStartSeconds] retains it until a player duration is available,
     * then [SkipSegmentPreferences.migrateLegacyCredits] converts it without dropping the
     * user's boundary.
     */
    val creditsLeadSeconds: Long = 0L,
    /** Compatibility field for snapshots written before credits became end-relative. */
    @SerialName("creditsStartSeconds")
    val legacyCreditsStartSeconds: Long = 0L,
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
        get() =
            introStartSeconds > 0L ||
                introEndSeconds > 0L ||
                creditsLeadSeconds > 0L ||
                legacyCreditsStartSeconds > 0L

    /** True once the intro describes a real interval, rather than half of one. */
    val hasIntro: Boolean
        get() = introEndSeconds > introStartSeconds

    val hasCredits: Boolean
        get() = creditsLeadSeconds > 0L || legacyCreditsStartSeconds > 0L

    /** End-relative value used by current UI, including an old absolute value when convertible. */
    fun effectiveCreditsLeadSeconds(durationMs: Long): Long {
        if (creditsLeadSeconds > 0L) return creditsLeadSeconds
        val durationSeconds = durationMs / 1_000L
        return (durationSeconds - legacyCreditsStartSeconds)
            .takeIf { legacyCreditsStartSeconds > 0L && it > 0L }
            ?: 0L
    }
}

/**
 * What the player does when playback reaches a configured 片头 / 片尾.
 *
 * Three states rather than a switch, because a switch conflates two different answers. Off
 * used to be reachable only by deleting the times, which throws away the work; and a switch
 * labelled 自动跳过 says nothing about what happens when it is off — the pill was still
 * there, unmentioned.
 */
enum class SkipMode(
    val label: String,
) {
    /** Offer 跳过片头 and wait to be asked. The default: moving the playhead unbidden is a
     *  surprising thing for a player to do, and the pill costs one tap. */
    Button("跳过按钮"),
    Auto("自动跳过"),

    /** Times stay stored; nothing is offered and nothing moves. */
    Off("关闭"),
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
class SkipSegmentPreferences(
    private val settings: Settings,
) {
    private companion object {
        const val KEY_SERIES = "player.skip.bySeries"

        /** The old two-state switch. Read once, to seed [KEY_MODE]. */
        const val KEY_AUTO_SKIP = "player.skip.auto"
        const val KEY_MODE = "player.skip.mode"

        /** Longer than any plausible runtime, and a guard against a mistyped extra digit. */
        const val MAX_SECONDS = 10 * 60 * 60L
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val seriesSerializer = MapSerializer(String.serializer(), SkipTimes.serializer())

    private val _bySeries = MutableStateFlow(load())
    val bySeries: StateFlow<Map<String, SkipTimes>> = _bySeries.asStateFlow()

    private val _skipMode = MutableStateFlow(loadMode())
    val skipMode: StateFlow<SkipMode> = _skipMode.asStateFlow()

    fun setSkipMode(mode: SkipMode) {
        _skipMode.value = mode
        settings.putString(KEY_MODE, mode.name)
    }

    /**
     * Reads the mode, seeding it from the switch older versions stored.
     *
     * An install that had 自动跳过 on comes back as [SkipMode.Auto]; one that had it off
     * comes back as [SkipMode.Button], which is what "off" meant then — the pill was still
     * being offered. Nobody's behaviour changes on upgrade.
     */
    private fun loadMode(): SkipMode {
        settings
            .getStringOrNull(KEY_MODE)
            ?.let { stored -> SkipMode.entries.firstOrNull { it.name == stored } }
            ?.let { return it }
        return if (settings.getBoolean(KEY_AUTO_SKIP, false)) SkipMode.Auto else SkipMode.Button
    }

    fun timesFor(seriesId: String?): SkipTimes? = seriesId?.takeIf { it.isNotBlank() }?.let { _bySeries.value[it] }

    fun set(
        seriesId: String,
        times: SkipTimes,
    ) {
        if (seriesId.isBlank()) return
        val clamped =
            times.copy(
                introStartSeconds = times.introStartSeconds.coerceIn(0L, MAX_SECONDS),
                introEndSeconds = times.introEndSeconds.coerceIn(0L, MAX_SECONDS),
                creditsLeadSeconds = times.creditsLeadSeconds.coerceIn(0L, MAX_SECONDS),
                legacyCreditsStartSeconds = times.legacyCreditsStartSeconds.coerceIn(0L, MAX_SECONDS),
                seriesName = times.seriesName.trim().take(80),
            )
        // An entry that configures nothing is indistinguishable from having no entry, and
        // keeping it would leave a permanently empty row in 我的.
        _bySeries.value =
            if (clamped.configured) {
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

    /** Converts the pre-end-relative credits field once an actual episode duration is known. */
    fun migrateLegacyCredits(
        seriesId: String,
        durationMs: Long,
    ) {
        val current = _bySeries.value[seriesId] ?: return
        if (current.legacyCreditsStartSeconds <= 0L || current.creditsLeadSeconds > 0L) return
        val leadSeconds = current.effectiveCreditsLeadSeconds(durationMs)
        if (leadSeconds <= 0L) return
        set(
            seriesId,
            current.copy(
                creditsLeadSeconds = leadSeconds,
                legacyCreditsStartSeconds = 0L,
            ),
        )
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

    private fun customSegments(
        times: SkipTimes?,
        durationMs: Long,
    ): List<PlaybackSegment> {
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
            val effectiveLeadSeconds = times.effectiveCreditsLeadSeconds(durationMs)
            val creditsStartMs =
                when {
                    effectiveLeadSeconds > 0L && durationMs > 0L ->
                        durationMs - effectiveLeadSeconds * 1_000L
                    times.legacyCreditsStartSeconds > 0L ->
                        times.legacyCreditsStartSeconds * 1_000L
                    else -> 0L
                }
            if (
                times.hasCredits &&
                creditsStartMs > 0L &&
                (durationMs <= 0L || creditsStartMs < durationMs)
            ) {
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
