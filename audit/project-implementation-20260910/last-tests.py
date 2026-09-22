from pathlib import Path
p=Path('macrobenchmark/src/main/kotlin/com/yfuse/macrobenchmark/NavigationJourneyBenchmark.kt');s=p.read_text(encoding='utf-8');s=s.replace('device.findObject(By.desc("搜索"))','device.wait(Until.findObject(By.desc("搜索")), 10_000)').replace('device.findObject(By.desc("我的"))','device.wait(Until.findObject(By.desc("我的")), 10_000)').replace('device.findObject(By.desc("首页"))','device.wait(Until.findObject(By.desc("首页")), 10_000)');p.write_text(s,encoding='utf-8')
p=Path('composeApp/src/androidUnitTest/kotlin/com/yfuse/core/offline/OfflineWakeWorkTest.kt');s=p.read_text(encoding='utf-8');i=s.rfind('}');s=s[:i]+'''    @Test
    fun charging_constraint_only_applies_to_automatic_download_work() {
        val automatic = offlineAutoSyncRequest(wifiOnly = true, chargingOnly = true)
        assertTrue(automatic.workSpec.constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, automatic.workSpec.constraints.requiredNetworkType)
        assertEquals(false, offlineWakeRequest(wifiOnly = true).workSpec.constraints.requiresCharging())
    }
'''+s[i:];p.write_text(s,encoding='utf-8')
p=Path('composeApp/src/androidUnitTest/kotlin/com/yfuse/core/offline/OfflineMediaSecurityTest.kt');s=p.read_text(encoding='utf-8');i=s.rfind('}');s=s[:i]+'''    @Test
    fun download_budget_and_window_survive_policy_reload() {
        val settings = MapSettings()
        val policy = OfflineDownloadPolicy(storageBudgetBytes = 5L * 1024 * 1024 * 1024,
            autoDownloadChargingOnly = true, windowStartMinute = 1320, windowEndMinute = 420)
        persistOfflineDownloadPolicy(settings, policy)
        assertEquals(policy, loadOfflineDownloadPolicy(settings))
    }
'''+s[i:];p.write_text(s,encoding='utf-8')
p=Path('composeApp/src/commonTest/kotlin/com/yfuse/core/data/PlaybackBookmarksTest.kt');s=p.read_text(encoding='utf-8');i=s.rfind('}');s=s[:i]+'''    @Test
    fun invalid_record_identity_is_preserved_instead_of_silently_replaced() {
        val settings = MapSettings()
        val document = """[{"id":1,"media":{"serverId":"a","itemId":"film"},"positionMs":-1,"title":"invalid"}]"""
        settings.putString("playback.bookmarks.v1", document)
        val store = PlaybackBookmarks(settings)
        assertNotNull(store.loadError)
        assertFailsWith<IllegalStateException> { store.remove(PlaybackBookmarkKey("a", "film"), 1) }
        assertEquals(document, settings.getString("playback.bookmarks.v1", ""))
    }
'''+s[i:];p.write_text(s,encoding='utf-8')
p=Path('audit/project-implementation-20260910/format-files.txt');s=p.read_text(encoding='utf-8');s+='\ncomposeApp/src/androidUnitTest/kotlin/com/yfuse/core/offline/OfflineWakeWorkTest.kt\ncomposeApp/src/androidUnitTest/kotlin/com/yfuse/core/offline/OfflineMediaSecurityTest.kt\nmacrobenchmark/build.gradle.kts\n';s+='\n'.join(str(p) for p in Path('macrobenchmark/src/main/kotlin/com/yfuse/macrobenchmark').glob('*.kt'));p.write_text(s,encoding='utf-8')
p=Path('audit/project-implementation-20260910/test.ps1');s=p.read_text(encoding='utf-8').replace("':watchTogetherProtocol:jvmTest', ':watchTogetherServer:test',","':watchTogetherProtocol:jvmTest', ':watchTogetherServer:test', ':macrobenchmark:compileBenchmarkKotlin',");p.write_text(s,encoding='utf-8')
