from edit import *

p='composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryStore.kt'
replace(p, 'import kotlinx.coroutines.launch', 'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext')
replace(p, '    private val favoriteWriter: LibraryFavoriteWriter = { _, _, _, _ -> Result.success(Unit) },', '    private val favoriteWriter: LibraryFavoriteWriter = { _, _, _, _ -> Result.success(Unit) },\n    private val workContext: CoroutineContext = Dispatchers.Default,')
t=read(p);start=t.index('                        // Paint whatever this server');end=t.index('                        load(server)',start);t=t[:start]+t[end:];write(p,t)
replace(p, '''                scope.launch {
                    val started = TimeSource''', '''                scope.launch {
                    if (state().content.isEmpty) {
                        val snapshot = withContext(workContext) { cache.readSnapshot(server.id) }
                        if (!ownsLoad(generation, connection)) return@launch
                        snapshot?.let { dispatch(Msg.Cached(it.content, it.updatedAtEpochMs)) }
                    }
                    val initialContent = state().content
                    val started = TimeSource''')
replace(p, '''                        repo
                            .homeContent(server, initialContent = state().content) { content ->
                                if (ownsLoad(generation, connection)) {''', '''                        withContext(workContext) {
                            repo.homeContent(server, initialContent = initialContent) { content ->
                                withContext(mainContext) {
                                if (ownsLoad(generation, connection)) {''')
replace(p, '''                            }.onSuccess { content ->''', '''                                }
                            }
                        }.onSuccess { content ->''')
replace(p, '''                                cache.write(server.id, content, updatedAtEpochMs)
                                dispatch''', '''                                withContext(workContext) { cache.write(server.id, content, updatedAtEpochMs) }
                                if (!ownsLoad(generation, connection)) return@onSuccess
                                dispatch''')
p='composeApp/src/commonTest/kotlin/com/yfuse/feature/library/LibraryStoreTest.kt'
replace(p, 'mainContext = UnconfinedTestDispatcher(testScheduler),', 'mainContext = UnconfinedTestDispatcher(testScheduler),\n                    workContext = UnconfinedTestDispatcher(testScheduler),')

# Keep explicit refresh forceful; navigation observes repository freshness rather than refetching all episodes.
p='composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeScreen.kt'
replace(p, '            component.refreshCalendar(forceRefresh = true)', '            component.refreshCalendar()')
p='composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeComponent.kt'
replace(p, '    private var calendarJob: Job? = null', '    private var calendarJob: Job? = null\n    private var calendarGeneration = 0L')
replace(p, '.onEach { refreshCalendar() }', '.onEach { refreshCalendar(forceRefresh = true) }')
replace(p, '''    fun refreshCalendar(forceRefresh: Boolean = false) {
        calendarJob?.cancel()''', '''    fun refreshCalendar(forceRefresh: Boolean = false) {
        if (!forceRefresh && calendarJob?.isActive == true) return
        val generation = ++calendarGeneration
        calendarJob?.cancel()''')
replace(p, 'if (preview.isNotEmpty()) {', 'if (generation == calendarGeneration && preview.isNotEmpty()) {')
replace(p, '''                }.onSuccess { _calendar.value = HomeCalendarState(days = it, loading = false) }
                    .onFailure { error ->''', '''                }.onSuccess {
                    if (generation == calendarGeneration) _calendar.value = HomeCalendarState(days = it, loading = false)
                }.onFailure { error ->
                        if (generation != calendarGeneration) return@onFailure''')

# Cache constructors remain source compatible. Opening/migrating the independent file is deferred to first use.
for filename in ('LibraryCache', 'TmdbHomeCache'):
    p=f'composeApp/src/commonMain/kotlin/com/yfuse/core/data/{filename}.kt'
    t=read(p).replace('    private val settings: Settings,', '    settings: Settings,')
    marker=') {\n    private companion object'
    assert marker in t
    t=t.replace(marker, '    storage: () -> Settings = { settings },\n) {\n    private val settings by lazy(storage)\n\n    private companion object',1)
    write(p,t)
p='composeApp/src/commonMain/kotlin/com/yfuse/di/AppModule.kt'
replace(p, '    calendarLocalStore: CalendarLocalStore = NoOpCalendarLocalStore,', '    calendarLocalStore: CalendarLocalStore = NoOpCalendarLocalStore,\n    feedCacheSettings: () -> Settings = { settings },')
replace(p, 'LibraryCache(get())', 'LibraryCache(get(), storage = feedCacheSettings)')
replace(p, 'TmdbHomeCache(get())', 'TmdbHomeCache(get(), storage = feedCacheSettings)')
write('composeApp/src/androidMain/kotlin/com/yfuse/core/data/AndroidFeedCacheSettings.kt', '''package com.yfuse.core.data

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/** Invoked lazily on the cache worker. Old cache keys are removed only after a durable copy. */
fun androidFeedCacheSettings(context: Context): () -> Settings {
    val app = context.applicationContext
    val storage by lazy {
        val destination = app.getSharedPreferences("yfuse_feed_cache_v1", Context.MODE_PRIVATE)
        val legacy = app.getSharedPreferences("yfuse", Context.MODE_PRIVATE)
        val entries = legacy.all.filterKeys { it.startsWith("library.cache.") || it == "tmdb.home.cache.v1" }
        if (entries.isNotEmpty()) {
            val editor = destination.edit()
            entries.forEach { (key, value) ->
                if (!destination.contains(key) && value is String) editor.putString(key, value)
            }
            check(editor.commit()) { "Feed cache migration could not be persisted" }
            val cleanup = legacy.edit()
            entries.keys.forEach(cleanup::remove)
            cleanup.apply()
        }
        SharedPreferencesSettings(destination)
    }
    return { storage }
}
''')
for p in ('composeApp/src/androidMain/kotlin/com/yfuse/YfuseApp.kt','tvApp/src/androidMain/kotlin/com/yfuse/tv/TvApplication.kt'):
    replace(p, 'import com.yfuse.core.data.DiagnosticPreferences', 'import com.yfuse.core.data.DiagnosticPreferences\nimport com.yfuse.core.data.androidFeedCacheSettings')
    replace(p, '                        settings = settings,', '                        settings = settings,\n                        feedCacheSettings = androidFeedCacheSettings(this),')
