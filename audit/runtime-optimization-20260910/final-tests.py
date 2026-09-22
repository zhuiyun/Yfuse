from edit import *

p='composeApp/src/commonTest/kotlin/com/yfuse/feature/home/HomeStoreTest.kt'
replace(p,'class HomeStoreTest {', '''class HomeStoreTest {
    @Test
    fun quick_home_revisits_reuse_refresh_but_explicit_refresh_still_fetches() = runTest(scheduler) {
        var requests = 0
        val registry = testRegistry().apply { addOrUpdate(SavedServer("one", "http://one", "One", "u", "User", "token")) }
        val store = HomeStoreFactory(
            storeFactory = DefaultStoreFactory(),
            tmdb = unavailableTmdb(),
            emby = testRepo(dispatcher = UnconfinedTestDispatcher(testScheduler)) { request ->
                requests++
                homeRoutes(request)
            },
            registry = registry,
            cache = TmdbHomeCache(MapSettings()),
            cacheDispatcher = UnconfinedTestDispatcher(testScheduler),
        ).create()
        try {
            advanceUntilIdle()
            store.accept(HomeIntent.RefreshLibrary)
            advanceUntilIdle()
            val afterReturn = requests
            assertTrue(afterReturn > 0)
            repeat(3) { store.accept(HomeIntent.RefreshLibrary) }
            advanceUntilIdle()
            assertEquals(afterReturn, requests)
            store.accept(HomeIntent.Refresh)
            advanceUntilIdle()
            assertTrue(requests > afterReturn)
        } finally {
            store.dispose()
        }
    }
''')
p='composeApp/src/commonTest/kotlin/com/yfuse/feature/library/LibraryStoreTest.kt'
replace(p,'class LibraryStoreTest {', '''class LibraryStoreTest {
    @Test
    fun unavailable_cache_storage_does_not_block_live_library() = runTest {
        val registry = testRegistry().apply { addOrUpdate(SavedServer("one", "http://one", "One", "u", "User", "token")) }
        val store = LibraryStoreFactory(
            DefaultStoreFactory(),
            testRepo(dispatcher = UnconfinedTestDispatcher(testScheduler)) { homeRoutes(it) },
            registry,
            LibraryCache(MapSettings(), storage = { error("cache unavailable") }),
            mainContext = UnconfinedTestDispatcher(testScheduler),
            workContext = UnconfinedTestDispatcher(testScheduler),
        ).create()
        try {
            advanceUntilIdle()
            assertEquals(LibraryContentSource.Live, store.state.contentSource)
            assertTrue(!store.state.content.isEmpty)
        } finally {
            store.dispose()
        }
    }
''')
for p in ('composeApp/src/androidMain/kotlin/com/yfuse/YfuseApp.kt','tvApp/src/androidMain/kotlin/com/yfuse/tv/TvApplication.kt'):
    replace(p,'import com.yfuse.core.data.ServerRegistry', 'import com.yfuse.core.data.ServerRegistry\nimport com.yfuse.core.data.observeFeedCacheCleanup\nimport com.yfuse.core.data.LibraryCache')
    if 'YfuseApp' in p:
        replace(p, '                ServerSessionRecovery.awaitReady()', '                ServerSessionRecovery.awaitReady()\n                observeFeedCacheCleanup(this, koinApplication.koin.get<ServerRegistry>(), koinApplication.koin.get<LibraryCache>())')
    else:
        replace(p, '                graph = TvApplicationGraph(koinApplication.koin)', '                graph = TvApplicationGraph(koinApplication.koin)\n                observeFeedCacheCleanup(applicationScope, koinApplication.koin.get<ServerRegistry>(), koinApplication.koin.get<LibraryCache>())')

paths=read('audit/runtime-optimization-20260910/format-files.txt').splitlines()
paths+=['composeApp/src/commonTest/kotlin/com/yfuse/feature/home/HomeStoreTest.kt','composeApp/src/commonMain/kotlin/com/yfuse/core/data/FeedCacheCleanup.kt']
write('audit/runtime-optimization-20260910/format-files.txt','\n'.join(sorted(set(paths)))+'\n')
