from edit import read, write, replace
A='composeApp/src/androidMain/kotlin/com/yfuse/core2/android/'
p=A+'AndroidAudioTrackRenderNode.kt'
replace(p,'    private var writtenBytes = 0L','    private var writtenBytes = 0L\n    private val pcmTail = PcmTailTracker()')
replace(p,'            writtenBytes += written\n', '            writtenBytes += written\n            pcmTail.record(written)\n')
replace(p,'        if (written == 0) zeroWriteCount++ else writtenBytes += written','''        if (written == 0) zeroWriteCount++ else {
            writtenBytes += written
            pcmTail.record(written)
        }''')
replace(p,'        if (writtenBytes <= 0L || sampleRate <= 0) return false','        if (!pcmTail.hasSamples || sampleRate <= 0) return false')
replace(p,'        return pcmTailPending(writtenBytes, frameBytes, sampleRate, played)','        return pcmTail.pending(frameBytes, sampleRate, played)')
replace(p,'        audioTrack.flush()\n        basePresentationTimeUs = null','        audioTrack.flush()\n        pcmTail.reset()\n        basePresentationTimeUs = null')
replace(p,'        configuredFormat = null\n        requestedPlay = false','        configuredFormat = null\n        pcmTail.reset()\n        requestedPlay = false')
t=read(p)+'''
/** Drain accounting follows AudioTrack flushes, independently of lifetime diagnostic byte totals. */
internal class PcmTailTracker {
    private var bytes = 0L
    val hasSamples: Boolean get() = bytes > 0L
    fun record(written: Int) { if (written > 0) bytes += written }
    fun reset() { bytes = 0L }
    fun pending(frameBytes: Int, sampleRate: Int, playedUs: Long): Boolean =
        pcmTailPending(bytes, frameBytes, sampleRate, playedUs)
}
''';write(p,t)
p='composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/NextItemOutputHandoffTest.kt'
t=read(p);idx=t.index('    @Test')
t=t[:idx]+'''    @Test fun seeking_discards_old_pcm_tail_before_counting_newly_submitted_samples() {
        val tail = PcmTailTracker()
        tail.record(192_000)
        assertTrue(tail.pending(4, 48_000, 900_000))
        tail.reset()
        assertFalse(tail.hasSamples)
        assertFalse(tail.pending(4, 48_000, 0))
        tail.record(96_000)
        assertTrue(tail.pending(4, 48_000, 400_000))
        assertFalse(tail.pending(4, 48_000, 500_000))
    }

''' +t[idx:];write(p,t)
p=A+'AndroidAdaptiveCore2YPlayer.kt'
replace(p,'        var handoffStartedNs: Long? = null','        var handoffStartedNs: Long? = null\n        var handoffItemId: String? = null')
replace(p,'                        handoffStartedNs?.let { started ->','                        handoffStartedNs?.takeIf { handoffItemId == childItemId }?.let { started ->')
replace(p,'                            handoffStartedNs = System.nanoTime()', '                            handoffItemId = command.itemId\n                            handoffStartedNs = System.nanoTime()')
