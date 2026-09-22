from edit import read, write, replace
p='composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSkipCoordinator.kt'
replace(p,'    var creditsEnteredFromPlayback by remember(currentItem?.id) { mutableStateOf(false) }','''    var creditsEnteredFromPlayback by remember(currentItem?.id) { mutableStateOf(false) }
    var creditsPreloadCancelled by remember(currentItem?.id) { mutableStateOf(false) }''')
replace(p,'            cancelled = settled.value == (currentItem?.id to PlaybackSegmentType.Credits),','            cancelled = creditsPreloadCancelled,')
replace(p,'                onCancelAuto = { settled.value = occurrence },','''                onCancelAuto = {
                    settled.value = occurrence
                    if (occurrence?.second == PlaybackSegmentType.Credits) creditsPreloadCancelled = true
                },''')
p='composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidPreparedMediaSlotTest.kt'
t=read(p).replace('import kotlin.test.assertEquals','import kotlin.test.assertFalse\nimport kotlin.test.assertEquals')
t=t.replace('''        val transferred = AndroidPreparedMediaSlot<Any>(expiryMillis = 20L) { error("Released after transfer") }''','''        val incorrectlyReleased = CountDownLatch(1)
        val transferred = AndroidPreparedMediaSlot<Any>(expiryMillis = 100L) { incorrectlyReleased.countDown() }''')
t=t.replace('''        transferred.close()
    }''','''        assertFalse(incorrectlyReleased.await(200L, TimeUnit.MILLISECONDS))
        transferred.close()
    }''');write(p,t)
