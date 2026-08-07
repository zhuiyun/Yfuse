package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
enum class DanmakuDisplayArea(
    val label: String,
    val fraction: Float,
) {
    Quarter("顶部 1/4", 0.25f),
    Half("顶部 1/2", 0.5f),
    ThreeQuarters("顶部 3/4", 0.75f),
    Full("全屏", 1f),
}

@Serializable
enum class DanmakuFontSize(
    val label: String,
    val scale: Float,
) {
    Small("小", 0.82f),
    Standard("标准", 1f),
    Large("大", 1.2f),
    ExtraLarge("特大", 1.4f),
}

@Serializable
enum class DanmakuSpeed(
    val label: String,
    val durationMs: Long,
) {
    Slow("慢", 12_000L),
    Standard("标准", 8_000L),
    Fast("快", 5_500L),
}

@Serializable
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
        const val KEY_MERGE = "danmaku.mergeDuplicates"
        const val KEY_BLOCKED = "danmaku.blockedWords"
        const val KEY_RECENT = "danmaku.recentSearches"

        /** Enough to get back to last night's show; more is a list nobody reads. */
        const val MAX_RECENT = 8
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

    /**
     * On by default. A popular episode is mostly the same six sentences, and the first thing
     * anyone does with a wall of them is look for this switch.
     */
    private val _mergeDuplicates = MutableStateFlow(settings.getBoolean(KEY_MERGE, true))
    val mergeDuplicates: StateFlow<Boolean> = _mergeDuplicates.asStateFlow()

    private val _blockedWords = MutableStateFlow(loadList(KEY_BLOCKED))
    val blockedWords: StateFlow<List<String>> = _blockedWords.asStateFlow()

    private val _recentSearches = MutableStateFlow(loadList(KEY_RECENT))
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()

    /**
     * The account-owned part of the danmaku configuration.
     *
     * Search history and all player-runtime state deliberately stay out: neither is needed to
     * reproduce the user's configuration on another device.
     */
    fun snapshot(): DanmakuSyncSnapshot = DanmakuSyncSnapshot(
        sources = _sources.value.toList(),
        activeSourceId = _activeSourceId.value,
        bindings = _bindings.value.toMap(),
        enabled = _enabled.value,
        displayArea = _displayArea.value,
        fontSize = _fontSize.value,
        speed = _speed.value,
        opacity = _opacity.value,
        mergeDuplicates = _mergeDuplicates.value,
        blockedWords = _blockedWords.value.toList(),
    )

    /** Validates and normalizes an untrusted snapshot without changing Settings or StateFlow. */
    fun validateSnapshot(snapshot: DanmakuSyncSnapshot): Result<DanmakuSyncSnapshot> =
        runCatching { snapshot.normalizedForApply() }

    /**
     * Replaces the account-owned configuration after validating the complete snapshot.
     *
     * Validation happens before either Settings or StateFlow is touched. Orphaned bindings and
     * a stale active-source pointer are repairable merge artifacts, so they are removed/fixed
     * rather than making an otherwise usable snapshot fail.
     */
    fun applySnapshot(snapshot: DanmakuSyncSnapshot): Result<Unit> = runCatching {
        val normalized = validateSnapshot(snapshot).getOrThrow()

        // Persist first. Observers only see the new in-memory state after every value has been
        // accepted by the platform Settings implementation.
        settings.putString(KEY_SOURCES, json.encodeToString(sourcesSerializer, normalized.sources))
        normalized.activeSourceId?.let { settings.putString(KEY_ACTIVE_SOURCE, it) }
            ?: settings.remove(KEY_ACTIVE_SOURCE)
        if (normalized.bindings.isEmpty()) {
            settings.remove(KEY_BINDINGS)
        } else {
            settings.putString(
                KEY_BINDINGS,
                json.encodeToString(bindingsSerializer, normalized.bindings),
            )
        }
        settings.putBoolean(KEY_ENABLED, normalized.enabled)
        settings.putString(KEY_DISPLAY_AREA, normalized.displayArea.name)
        settings.putString(KEY_FONT_SIZE, normalized.fontSize.name)
        settings.putString(KEY_SPEED, normalized.speed.name)
        settings.putString(KEY_OPACITY, normalized.opacity.name)
        settings.putBoolean(KEY_MERGE, normalized.mergeDuplicates)
        persistList(KEY_BLOCKED, normalized.blockedWords)

        _sources.value = normalized.sources
        _activeSourceId.value = normalized.activeSourceId
        _bindings.value = normalized.bindings
        _enabled.value = normalized.enabled
        _displayArea.value = normalized.displayArea
        _fontSize.value = normalized.fontSize
        _speed.value = normalized.speed
        _opacity.value = normalized.opacity
        _mergeDuplicates.value = normalized.mergeDuplicates
        _blockedWords.value = normalized.blockedWords
    }

    fun setMergeDuplicates(enabled: Boolean) {
        _mergeDuplicates.value = enabled
        settings.putBoolean(KEY_MERGE, enabled)
    }

    fun addBlockedWord(word: String) {
        val normalized = word.trim().take(MAX_DANMAKU_SYNC_BLOCKED_WORD_CHARS)
        if (normalized.isEmpty() || _blockedWords.value.any { it.equals(normalized, true) }) return
        if (_blockedWords.value.size >= MAX_DANMAKU_SYNC_BLOCKED_WORDS) return
        _blockedWords.value = _blockedWords.value + normalized
        persistList(KEY_BLOCKED, _blockedWords.value)
    }

    fun removeBlockedWord(word: String) {
        if (word !in _blockedWords.value) return
        _blockedWords.value = _blockedWords.value - word
        persistList(KEY_BLOCKED, _blockedWords.value)
    }

    /** Newest first, deduplicated, capped — the shape every recent-search list has. */
    fun rememberSearch(keyword: String) {
        val normalized = keyword.trim().take(40)
        if (normalized.isEmpty()) return
        _recentSearches.value = (
            listOf(normalized) + _recentSearches.value.filterNot { it.equals(normalized, true) }
            ).take(MAX_RECENT)
        persistList(KEY_RECENT, _recentSearches.value)
    }

    /** The source a chip row shows as selected, resolved against deletions. */
    fun activeSource(): DanmakuSource? = _sources.value.activeOr(_activeSourceId.value)

    /** Returns the stored source, or null when the URL is unusable. */
    fun addSource(name: String, url: String): DanmakuSource? {
        if (_sources.value.size >= MAX_DANMAKU_SYNC_SOURCES) return null
        val source = DanmakuSource(
            id = DanmakuSource.newId(),
            name = name.trim().take(MAX_DANMAKU_SYNC_SOURCE_NAME_CHARS).ifBlank { defaultName() },
            url = url.trim(),
        )
        if (!source.url.isValidDanmakuSourceUrl()) return null
        _sources.value = _sources.value + source
        // Selecting the first one is not a preference, it is the only possible answer.
        if (_sources.value.size == 1) selectSource(source.id)
        persistSources()
        return source
    }

    fun updateSource(id: String, name: String, url: String) {
        val trimmedUrl = url.trim()
        if (!trimmedUrl.isValidDanmakuSourceUrl()) return
        _sources.value = _sources.value.map { source ->
            if (source.id == id) {
                source.copy(
                    name = name.trim().take(MAX_DANMAKU_SYNC_SOURCE_NAME_CHARS).ifBlank { source.name },
                    url = trimmedUrl,
                )
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

    /**
     * A hand-picked match for this entry, under its own key or the item id an older build
     * wrote it under.
     *
     * The fallback is one line and it saves everyone who has already corrected a match from
     * having to do it again after upgrading.
     */
    fun binding(key: String, legacyItemId: String?): DanmakuBinding? =
        _bindings.value[key] ?: legacyItemId?.let { _bindings.value[it] }

    fun bind(itemId: String, binding: DanmakuBinding) {
        if (itemId.isBlank()) return
        // Re-inserting moves the entry to the end, so trimming from the front drops the
        // least recently corrected match rather than an arbitrary one.
        val trimmed = (_bindings.value - itemId) +
            (itemId to binding.copy(label = binding.label.take(MAX_DANMAKU_SYNC_BINDING_LABEL_CHARS)))
        _bindings.value = if (trimmed.size > MAX_DANMAKU_SYNC_BINDINGS) {
            trimmed.entries.drop(trimmed.size - MAX_DANMAKU_SYNC_BINDINGS).associate { it.key to it.value }
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

    /**
     * Stored newline-separated rather than as JSON.
     *
     * These are single words a person typed; a newline cannot occur in one, so the
     * separator is unambiguous and the stored value stays something a human could read in
     * a settings dump.
     */
    private fun loadList(key: String): List<String> =
        settings.getStringOrNull(key)
            ?.split('\n')
            ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .orEmpty()

    private fun persistList(key: String, values: List<String>) {
        if (values.isEmpty()) {
            settings.remove(key)
        } else {
            settings.putString(key, values.joinToString("\n"))
        }
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

    private fun DanmakuSyncSnapshot.normalizedForApply(): DanmakuSyncSnapshot {
        require(version == DanmakuSyncSnapshot.CURRENT_VERSION) { "不支持的弹幕同步数据版本" }
        require(sources.size <= MAX_DANMAKU_SYNC_SOURCES) { "弹幕源数量超过限制" }
        require(bindings.size <= MAX_DANMAKU_SYNC_BINDINGS) { "弹幕匹配数量超过限制" }
        require(blockedWords.size <= MAX_DANMAKU_SYNC_BLOCKED_WORDS) { "弹幕屏蔽词数量超过限制" }

        val normalizedSources = sources.mapIndexed { index, source ->
            require(source.id.isNotBlank()) { "弹幕源 ID 不能为空" }
            require(source.id.length <= MAX_DANMAKU_SYNC_SOURCE_ID_CHARS) { "弹幕源 ID 过长" }
            val normalizedUrl = source.url.trim()
            require(normalizedUrl.isValidDanmakuSourceUrl()) { "弹幕源地址无效" }
            source.copy(
                name = source.name.trim()
                    .take(MAX_DANMAKU_SYNC_SOURCE_NAME_CHARS)
                    .ifBlank { "弹幕源 ${index + 1}" },
                url = normalizedUrl,
            )
        }
        val sourceIds = normalizedSources.mapTo(linkedSetOf()) { it.id }
        require(sourceIds.size == normalizedSources.size) { "弹幕源 ID 重复" }

        val normalizedBindings = buildMap {
            bindings.forEach { (key, binding) ->
                require(key.isNotBlank()) { "弹幕匹配键不能为空" }
                require(key.length <= MAX_DANMAKU_SYNC_BINDING_KEY_CHARS) { "弹幕匹配键过长" }
                require(binding.sourceId.isNotBlank()) { "弹幕匹配缺少来源" }
                require(binding.sourceId.length <= MAX_DANMAKU_SYNC_SOURCE_ID_CHARS) {
                    "弹幕匹配来源 ID 过长"
                }
                require(binding.episodeId.isNotBlank()) { "弹幕匹配缺少剧集 ID" }
                require(binding.episodeId.length <= MAX_DANMAKU_SYNC_EPISODE_ID_CHARS) {
                    "弹幕剧集 ID 过长"
                }
                if (binding.sourceId in sourceIds) {
                    put(
                        key,
                        binding.copy(label = binding.label.take(MAX_DANMAKU_SYNC_BINDING_LABEL_CHARS)),
                    )
                }
            }
        }

        val normalizedBlockedWords = blockedWords
            .mapNotNull { word ->
                word.trim()
                    .take(MAX_DANMAKU_SYNC_BLOCKED_WORD_CHARS)
                    .takeIf(String::isNotEmpty)
            }
            .distinctBy { it.lowercase() }

        return copy(
            version = DanmakuSyncSnapshot.CURRENT_VERSION,
            sources = normalizedSources,
            activeSourceId = activeSourceId?.takeIf(sourceIds::contains)
                ?: normalizedSources.firstOrNull()?.id,
            bindings = normalizedBindings,
            blockedWords = normalizedBlockedWords,
        )
    }

    private fun String.isValidDanmakuSourceUrl(): Boolean =
        length <= MAX_DANMAKU_SYNC_SOURCE_URL_CHARS &&
            (startsWith("http://") || startsWith("https://"))
}
