package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

enum class DanmakuDisplayArea(
    val label: String,
    val fraction: Float,
) {
    Quarter("顶部 1/4", 0.25f),
    Half("顶部 1/2", 0.5f),
    ThreeQuarters("顶部 3/4", 0.75f),
    Full("全屏", 1f),
}

enum class DanmakuFontSize(
    val label: String,
    val scale: Float,
) {
    Small("小", 0.82f),
    Standard("标准", 1f),
    Large("大", 1.2f),
    ExtraLarge("特大", 1.4f),
}

enum class DanmakuSpeed(
    val label: String,
    val durationMs: Long,
) {
    Slow("慢", 12_000L),
    Standard("标准", 8_000L),
    Fast("快", 5_500L),
}

enum class DanmakuOpacity(
    val label: String,
    val alpha: Float,
) {
    Low("50%", 0.5f),
    Standard("75%", 0.75f),
    High("100%", 1f),
}

/** Persistent source and rendering preferences shared by Profile and the player activity. */
class DanmakuPreferences(private val settings: Settings) {

    private companion object {
        /** The single link this used to keep. Read once, to seed [KEY_SOURCES]. */
        const val KEY_LEGACY_URL = "danmaku.urlTemplate"
        const val KEY_SOURCES = "danmaku.sources"
        const val KEY_ACTIVE_SOURCE = "danmaku.activeSource"
        const val KEY_BINDINGS = "danmaku.bindings"
        const val KEY_ENABLED = "danmaku.enabled"
        const val KEY_DISPLAY_AREA = "danmaku.displayArea"
        const val KEY_FONT_SIZE = "danmaku.fontSize"
        const val KEY_SPEED = "danmaku.speed"
        const val KEY_OPACITY = "danmaku.opacity"

        /**
         * Hand-picked matches worth remembering. Each is a few dozen bytes and they are only
         * written when someone corrects a match, so this ceiling is years away; it exists so
         * a long-lived install can't grow the settings blob without bound.
         */
        const val MAX_BINDINGS = 300
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sourcesSerializer = ListSerializer(DanmakuSource.serializer())
    private val bindingsSerializer =
        MapSerializer(String.serializer(), DanmakuBinding.serializer())

    private val _sources = MutableStateFlow(loadSources())
    val sources: StateFlow<List<DanmakuSource>> = _sources.asStateFlow()

    private val _activeSourceId = MutableStateFlow(settings.getStringOrNull(KEY_ACTIVE_SOURCE))
    val activeSourceId: StateFlow<String?> = _activeSourceId.asStateFlow()

    private val _bindings = MutableStateFlow(loadBindings())
    val bindings: StateFlow<Map<String, DanmakuBinding>> = _bindings.asStateFlow()

    private val _enabled = MutableStateFlow(settings.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _displayArea = MutableStateFlow(
        load(KEY_DISPLAY_AREA, DanmakuDisplayArea.entries, DanmakuDisplayArea.Half),
    )
    val displayArea: StateFlow<DanmakuDisplayArea> = _displayArea.asStateFlow()

    private val _fontSize = MutableStateFlow(
        load(KEY_FONT_SIZE, DanmakuFontSize.entries, DanmakuFontSize.Standard),
    )
    val fontSize: StateFlow<DanmakuFontSize> = _fontSize.asStateFlow()

    private val _speed = MutableStateFlow(
        load(KEY_SPEED, DanmakuSpeed.entries, DanmakuSpeed.Standard),
    )
    val speed: StateFlow<DanmakuSpeed> = _speed.asStateFlow()

    private val _opacity = MutableStateFlow(
        load(KEY_OPACITY, DanmakuOpacity.entries, DanmakuOpacity.Standard),
    )
    val opacity: StateFlow<DanmakuOpacity> = _opacity.asStateFlow()

    /** The source a chip row shows as selected, resolved against deletions. */
    fun activeSource(): DanmakuSource? = _sources.value.activeOr(_activeSourceId.value)

    /** Returns the stored source, or null when the URL is unusable. */
    fun addSource(name: String, url: String): DanmakuSource? {
        val source = DanmakuSource(
            id = DanmakuSource.newId(),
            name = name.trim().take(24).ifBlank { defaultName() },
            url = url.trim(),
        )
        if (source.url.isBlank()) return null
        _sources.value = _sources.value + source
        // Selecting the first one is not a preference, it is the only possible answer.
        if (_sources.value.size == 1) selectSource(source.id)
        persistSources()
        return source
    }

    fun updateSource(id: String, name: String, url: String) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return
        _sources.value = _sources.value.map { source ->
            if (source.id == id) {
                source.copy(name = name.trim().take(24).ifBlank { source.name }, url = trimmedUrl)
            } else {
                source
            }
        }
        persistSources()
    }

    fun removeSource(id: String) {
        if (_sources.value.none { it.id == id }) return
        _sources.value = _sources.value.filterNot { it.id == id }
        // Bindings name the source they came from; the ones pointing at this link are now
        // matches against nothing and would silently load nothing.
        _bindings.value = _bindings.value.filterValues { it.sourceId != id }
        persistBindings()
        if (_activeSourceId.value == id) {
            selectSource(_sources.value.firstOrNull()?.id)
        }
        persistSources()
    }

    fun selectSource(id: String?) {
        _activeSourceId.value = id
        if (id == null) {
            settings.remove(KEY_ACTIVE_SOURCE)
        } else {
            settings.putString(KEY_ACTIVE_SOURCE, id)
        }
    }

    fun bind(itemId: String, binding: DanmakuBinding) {
        if (itemId.isBlank()) return
        // Re-inserting moves the entry to the end, so trimming from the front drops the
        // least recently corrected match rather than an arbitrary one.
        val trimmed = (_bindings.value - itemId) +
            (itemId to binding.copy(label = binding.label.take(120)))
        _bindings.value = if (trimmed.size > MAX_BINDINGS) {
            trimmed.entries.drop(trimmed.size - MAX_BINDINGS).associate { it.key to it.value }
        } else {
            trimmed
        }
        persistBindings()
    }

    fun unbind(itemId: String) {
        if (itemId !in _bindings.value) return
        _bindings.value = _bindings.value - itemId
        persistBindings()
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        settings.putBoolean(KEY_ENABLED, enabled)
    }

    fun setDisplayArea(area: DanmakuDisplayArea) {
        _displayArea.value = area
        settings.putString(KEY_DISPLAY_AREA, area.name)
    }

    fun setFontSize(size: DanmakuFontSize) {
        _fontSize.value = size
        settings.putString(KEY_FONT_SIZE, size.name)
    }

    fun setSpeed(speed: DanmakuSpeed) {
        _speed.value = speed
        settings.putString(KEY_SPEED, speed.name)
    }

    fun setOpacity(opacity: DanmakuOpacity) {
        _opacity.value = opacity
        settings.putString(KEY_OPACITY, opacity.name)
    }

    private fun <T : Enum<T>> load(key: String, values: List<T>, fallback: T): T {
        val stored = settings.getStringOrNull(key) ?: return fallback
        return values.firstOrNull { it.name == stored } ?: fallback
    }

    private fun defaultName(): String = "弹幕源 ${_sources.value.size + 1}"

    /**
     * Reads the list, seeding it from the single link older versions stored.
     *
     * The legacy key is left in place rather than deleted: once [KEY_SOURCES] exists it is
     * never read again, and leaving it means downgrading an install still finds its link.
     */
    private fun loadSources(): List<DanmakuSource> {
        val raw = settings.getStringOrNull(KEY_SOURCES)
        if (raw == null) {
            val legacy = settings.getStringOrNull(KEY_LEGACY_URL)?.trim().orEmpty()
            if (legacy.isEmpty()) return emptyList()
            val migrated = listOf(DanmakuSource(DanmakuSource.newId(), "弹幕源 1", legacy))
            settings.putString(KEY_SOURCES, json.encodeToString(sourcesSerializer, migrated))
            settings.putString(KEY_ACTIVE_SOURCE, migrated.first().id)
            return migrated
        }
        return runCatching { json.decodeFromString(sourcesSerializer, raw) }
            .onFailure {
                AppLog.warning(
                    category = "danmaku",
                    event = "stored_sources_unreadable",
                    message = "Stored danmaku sources could not be read and were ignored",
                    throwable = it,
                )
            }
            .getOrDefault(emptyList())
    }

    private fun loadBindings(): Map<String, DanmakuBinding> {
        val raw = settings.getStringOrNull(KEY_BINDINGS) ?: return emptyMap()
        return runCatching { json.decodeFromString(bindingsSerializer, raw) }
            .onFailure {
                AppLog.warning(
                    category = "danmaku",
                    event = "stored_bindings_unreadable",
                    message = "Stored danmaku episode matches could not be read and were ignored",
                    throwable = it,
                )
            }
            .getOrDefault(emptyMap())
    }

    /**
     * Always writes, empty list included. Removing the key instead would put the store back
     * into the state [loadSources] treats as "never migrated", and deleting the last source
     * would resurrect the legacy link on the next launch.
     */
    private fun persistSources() {
        settings.putString(KEY_SOURCES, json.encodeToString(sourcesSerializer, _sources.value))
    }

    private fun persistBindings() {
        val current = _bindings.value
        if (current.isEmpty()) {
            settings.remove(KEY_BINDINGS)
            return
        }
        settings.putString(KEY_BINDINGS, json.encodeToString(bindingsSerializer, current))
    }
}
