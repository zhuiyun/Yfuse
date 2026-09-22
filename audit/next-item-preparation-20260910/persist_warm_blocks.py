from edit import read, write, replace
A='composeApp/src/androidMain/kotlin/com/yfuse/core2/android/'
replace(A+'AndroidTransportMediaDataSource.kt','    private val rangeReadBudgetMs: Long = 30_000L,','''    private val rangeReadBudgetMs: Long = 30_000L,
    /** Only bounded background warmers opt in; playback keeps non-blocking cache admission. */
    private val persistReadBlocks: Boolean = false,''')
replace(A+'AndroidTransportMediaDataSource.kt','                    diskCache?.enqueueWriteBlock(blockIndex, loaded.bytes, knownSize.takeIf { it >= 0L })','''                    diskCache?.let { persistent ->
                        if (persistReadBlocks) {
                            persistent.writeBlock(blockIndex, loaded.bytes, knownSize.takeIf { it >= 0L })
                        } else {
                            persistent.enqueueWriteBlock(blockIndex, loaded.bytes, knownSize.takeIf { it >= 0L })
                        }
                    }''')
p=A+'AndroidNextItemPreparation.kt'
replace(p,'PlaybackBufferKind.Preload, 2L * 1024 * 1024', 'PlaybackBufferKind.Preload, 8L * 1024 * 1024')
replace(p,'memory.limitBytes < 512L * 1024', 'memory.limitBytes < 8L * 1024 * 1024')
replace(p,'            rangeReadBudgetMs = 10_000L,','            rangeReadBudgetMs = 10_000L,\n            persistReadBlocks = true,')
# Far from the boundary, there is no need for four coroutine wakeups per second.
replace(p,'''        AndroidPlaybackMemoryBudget.refreshPressure()
        val ready = allowed() && nextItemPlaybackHealthy(current)''','''        val remaining = nextItemRemainingMs(current, boundary())
        if (remaining == null || remaining > windowMs + 5_000L) {
            healthyMs = 0L
            delay(5_000L)
            continue
        }
        AndroidPlaybackMemoryBudget.refreshPressure()
        val ready = allowed() && nextItemPlaybackHealthy(current)''')
# Virtual-time test must cross the next far-window poll after its boundary changes.
p='composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidNextItemPreparationTest.kt'
t=read(p)
t=t.replace('''            boundary = 1_160_000
            advanceTimeBy(1_000)''','''            boundary = 1_160_000
            advanceTimeBy(6_000)''')
write(p,t)
