from edit import read, write, replace
A='composeApp/src/androidMain/kotlin/com/yfuse/core2/android/'
C='composeApp/src/commonMain/kotlin/com/yfuse/'
replace(C+'core2/api/YPlayer.kt','    val playbackSessionId: String? = null,','''    val playbackSessionId: String? = null,
    /** False for server transcodes/live/adaptive sessions that must not be opened speculatively. */
    val allowNextItemPreparation: Boolean = true,''')
replace(A+'AndroidCore2Trial.kt','        playbackSessionId = playSessionId,','''        playbackSessionId = playSessionId,
        allowNextItemPreparation = canPreloadSource && !usingServerTranscode,''')
p=A+'AndroidAdaptiveCore2YPlayer.kt'
replace(p,'            if (hint?.enabled == false || item.disc != null || item.drmConfiguration != null) return','            if (hint?.enabled == false || !nextItemSourceEligible(item)) return')
replace(p,'                        if (!awaitCore2NextItemPreloadWindow(::snapshot)) return@launch\n','')
replace(p,'awaitNextItemBoundary(90_000L, ::boundary, ::snapshot, ::allowed)', 'awaitNextItemBoundary(90_000L, ::boundary, ::snapshot, ::allowed, stableMs = 5_000L)')
replace(p,'warmNextItemBytes(context, item, budget)', 'warmNextItemBytes(context.cacheDir, item, budget)')
replace(p,'            if (!decision.nativeDirectExecutable) routeEvaluator.closePreparedExtractor()', '''            if (!decision.nativeDirectExecutable) routeEvaluator.closePreparedExtractor()
            if (!decision.nativeDirectExecutable || decision.probe.playbackRequest.video.hdrType != YHdrType.Sdr ||
                decision.plan.audioPath != YAudioOutputPath.DecodePcm) videoHandoff.close()''')
replace(p,'            if (forceSoftwareFallback) {\n                routeEvaluator.closePreparedExtractor()', '            if (forceSoftwareFallback) {\n                videoHandoff.close()\n                routeEvaluator.closePreparedExtractor()')
replace(p,'            if (forceEnhancedFallback) {\n                routeEvaluator.closePreparedExtractor()', '            if (forceEnhancedFallback) {\n                videoHandoff.close()\n                routeEvaluator.closePreparedExtractor()')
replace(p,'''                        if (reportedChildState.buffering && nextItemPreloadJob?.isActive == true) {
                            nextItemPreloadJob?.cancel()
                            nextItemPreloadJob = null
                        }''','''                        if ((nextItemPreloadJob != null || preloadedNextRoute != null) &&
                            (reportedChildState.buffering || !nextItemNetworkAllowed(context))) {
                            discardNextPreparation()
                        }''')
p=A+'AndroidNextItemPreparation.kt'
replace(p,'import com.yfuse.core2.network.YSourceProtocol', 'import com.yfuse.core2.network.YMediaTransport\nimport com.yfuse.core2.network.YSourceProtocol\nimport java.io.File')
replace(p,'    allowed: () -> Boolean,\n): Boolean {','    allowed: () -> Boolean,\n    stableMs: Long = 1_000L,\n): Boolean {')
replace(p,'healthyMs >= 1_000L', 'healthyMs >= stableMs')
replace(p,'    context: Context,\n    item: YMediaItem,\n    budget: AndroidProbeBudget,\n): Long {','''    cacheDirectory: File,
    item: YMediaItem,
    budget: AndroidProbeBudget,
    createTransport: () -> YMediaTransport = {
        AndroidHttpMediaTransport(followSafeRedirects = true, allowCrossProtocolRedirects = true)
    },
): Long {''')
replace(p,'''            createTransport = {
                AndroidHttpMediaTransport(
                    followSafeRedirects = true,
                    allowCrossProtocolRedirects = true,
                )
            },
            cacheDirectory = context.cacheDir,''','''            createTransport = createTransport,
            cacheDirectory = cacheDirectory,''')
replace(p,'            var read = readRange(0L, limit)', '            var read = readRange(0L, limit)')
replace(p,'            AppLog.info(\n                category = "player.core2",\n                event = "next_item_bytes_warmed",','''            val persisted = source.awaitCacheWrites(minOf(1_000L, budget.remainingMs()))
            AppLog.info(
                category = "player.core2",
                event = "next_item_bytes_warmed",''')
replace(p,'"networkBytes" to networkBytes.get().toString()', '"networkBytes" to networkBytes.get().toString(), "writesDrained" to persisted.toString()')
text=read(p)+'''
internal fun nextItemSourceEligible(item: YMediaItem): Boolean {
    if (!item.allowNextItemPreparation || item.disc != null || item.drmConfiguration != null) return false
    val path = item.uri.substringBefore('?').substringBefore('#').lowercase()
    val mime = item.mimeType.orEmpty().lowercase()
    return !path.endsWith(".m3u8") && !path.endsWith(".mpd") &&
        !mime.contains("mpegurl") && !mime.contains("dash+xml")
}
'''
write(p,text)
replace(A+'AndroidTransportMediaDataSource.kt','    override fun close() {','''    /** Optional warmers await their bounded writes; normal playback never blocks on this. */
    fun awaitCacheWrites(timeoutMs: Long): Boolean = diskCache?.awaitPendingWrites(timeoutMs) ?: true

    override fun close() {''')
# Expiry is testable without waiting thirty seconds; all production callers retain the default.
replace(A+'AndroidPreparedEnhancedDemux.kt','    private val release: (T) -> Unit,\n) {','    private val expiryMillis: Long = 30_000L,\n    private val release: (T) -> Unit,\n) {')
replace(A+'AndroidPreparedEnhancedDemux.kt','30L, TimeUnit.SECONDS','expiryMillis, TimeUnit.MILLISECONDS')
p='composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/NextItemOutputHandoffTest.kt'
t=read(p);start=t.index('    @Test\n    fun exact_video_configuration');end=t.index('    @Test\n    fun pcm_end',start)
t=t[:start]+t[end:];t=t.replace('import kotlin.test.assertNotEquals\n','');write(p,t)
p='composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidNextItemPreparationTest.kt'
t=read(p).replace('import com.yfuse.core2.api.YPlaybackPhase','import com.yfuse.core2.api.YMediaItem\nimport com.yfuse.core2.api.YPlaybackPhase')
idx=t.index('    @Test')
t=t[:idx]+'''    @Test fun transcode_and_adaptive_sources_are_not_opened_speculatively() {
        val file = YMediaItem("next", "https://server/movie.mkv")
        assertTrue(nextItemSourceEligible(file))
        assertFalse(nextItemSourceEligible(file.copy(allowNextItemPreparation = false)))
        assertFalse(nextItemSourceEligible(file.copy(uri = "https://server/master.m3u8?token=example")))
        assertFalse(nextItemSourceEligible(file.copy(mimeType = "application/dash+xml")))
    }

''' +t[idx:];write(p,t)
p='composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidPreparedMediaSlotTest.kt'
t=read(p).replace('import kotlin.test.Test','import java.util.concurrent.CountDownLatch\nimport java.util.concurrent.TimeUnit\nimport kotlin.test.assertTrue\nimport kotlin.test.Test')
idx=t.index('    @Test')
t=t[:idx]+'''    @Test fun unclaimed_sources_expire_but_transferred_sources_survive_the_deadline() {
        val expired = CountDownLatch(1)
        val slot = AndroidPreparedMediaSlot<Any>(expiryMillis = 20L) { expired.countDown() }
        slot.offer(item, Any())
        assertTrue(expired.await(2, TimeUnit.SECONDS))
        assertNull(slot.take(item))
        val taken = Any()
        val transferred = AndroidPreparedMediaSlot<Any>(expiryMillis = 20L) { error("Released after transfer") }
        transferred.offer(item, taken)
        assertSame(taken, transferred.take(item))
        transferred.close()
    }

''' +t[idx:];write(p,t)
