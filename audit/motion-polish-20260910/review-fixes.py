from edit import read,write,replace
C='composeApp/src/commonMain/kotlin/com/yfuse/'
A='composeApp/src/androidMain/kotlin/com/yfuse/'
def imports(p,*names):
    s=read(p)
    for n in names:
        line='import '+n+'\n'
        if line not in s:s=s.replace('\n\nimport ','\n\n'+line+'import ',1)
    write(p,s)
p=C+'core/designsystem/MotionPolish.kt'
s=read(p).replace('    return onSizeChanged { size ->','    val handoff = windowClass?.let { Modifier.contentHandoff(it) } ?: Modifier\n    return onSizeChanged { size ->').replace('}.contentHandoff(windowClass ?: "initial-window")','}.then(handoff)');write(p,s)

# Compact sheets need compact skeletons, not a four-row page overflowing their 180dp slot.
p=C+'core/designsystem/SkeletonHandoff.kt'
replace(p,'internal fun PageLoadingSkeleton(modifier: Modifier = Modifier)', 'internal fun PageLoadingSkeleton(modifier: Modifier = Modifier, rows: Int = 4)')
replace(p,'repeat(4) { index ->','repeat(rows.coerceIn(1, 4)) { index ->')
for rel in ['feature/watch/WatchInviteSheet.kt','feature/player/WatchTogetherDialogs.kt']:
    p=C+rel;replace(p,'skeleton = { PageLoadingSkeleton() }','skeleton = { PageLoadingSkeleton(rows = 1) }')

# Testable bounded queue: a stale dismissal must never clear a later notice.
p=C+'core/designsystem/Toast.kt';s=read(p)
s=s.replace('private class ToastEntry','internal class ToastEntry')
pos=s.index('/** Bounded, independently')
s=s[:pos]+'''internal class ToastQueue {
    val entries = mutableStateListOf<ToastEntry>()
    private var latest: ToastEntry? = null

    fun post(message: String?, accent: Color? = null) {
        if (message == null) {
            entries.forEach { it.visible = false }
            latest = null
            return
        }
        entries.removeAll { it.message == message }
        while (entries.size >= MAX_TOASTS) entries.removeAt(0)
        val entry = ToastEntry(message, accent)
        latest = entry
        entries.add(entry)
    }

    fun dismiss(entry: ToastEntry): Boolean {
        entry.visible = false
        return latest === entry && entry in entries
    }
}

'''+s[pos:]
s=s.replace('val entries = remember { mutableStateListOf<ToastEntry>() }','val queue = remember { ToastQueue() }\n    val entries = queue.entries').replace('    val latestEntry = remember { arrayOfNulls<ToastEntry>(1) }\n','')
start=s.index('    LaunchedEffect(message) {');end=s.index('    Column(',start)
s=s[:start]+'    LaunchedEffect(message) { queue.post(message, accent) }\n'+s[end:]
s=s.replace('''                    entry.visible = false
                    if (latestEntry[0] === entry && latestMessage == entry.message) latestDismiss()''','''                    if (queue.dismiss(entry) && latestMessage == entry.message) latestDismiss()''')
# Formatter may have expanded the lambda, preserve exact lookup if necessary.
s=s.replace('entry.visible = false\n                        if (latestEntry[0] === entry && latestMessage == entry.message) latestDismiss()', 'if (queue.dismiss(entry) && latestMessage == entry.message) latestDismiss()')
assert 'latestEntry' not in s
write(p,s)

write('composeApp/src/commonTest/kotlin/com/yfuse/core/designsystem/ToastQueueTest.kt','''package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToastQueueTest {
    @Test fun old_timeout_cannot_clear_a_newer_notice() {
        val queue = ToastQueue()
        queue.post("first")
        val first = queue.entries.single()
        queue.post("second")
        assertFalse(queue.dismiss(first))
        assertTrue(queue.dismiss(queue.entries.last()))
    }

    @Test fun bursts_are_bounded_and_duplicates_get_a_new_identity() {
        val queue = ToastQueue()
        repeat(10) { queue.post("notice-$it") }
        assertEquals(listOf("notice-7", "notice-8", "notice-9"), queue.entries.map { it.message })
        val old = queue.entries.last()
        queue.post("notice-9")
        assertEquals(3, queue.entries.size)
        assertFalse(queue.dismiss(old))
        assertTrue(queue.dismiss(queue.entries.last()))
    }

    @Test fun external_clear_disarms_retained_exiting_entries() {
        val queue = ToastQueue()
        queue.post("one")
        val entry = queue.entries.single()
        entry.visible = true
        queue.post(null)
        assertFalse(entry.visible)
        assertFalse(queue.dismiss(entry))
    }
}
''')

# Hide chrome at Android 15's transition-start signal, restore only after PiP has exited.
p=A+'feature/player/PlayerActivity.kt'
imports(p,'android.app.PictureInPictureUiState','androidx.annotation.RequiresApi')
s=read(p);marker='    override fun onPictureInPictureModeChanged('
s=s.replace(marker,'''    @RequiresApi(Build.VERSION_CODES.S)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && pipState.isTransitioningToPip) {
            pictureInPicture.value = true
        }
    }

'''+marker,1)
s=s.replace('if (hasFocus && !isInPictureInPictureMode) pipWasVisible = false', '''if (hasFocus && !isInPictureInPictureMode) {
            pipWasVisible = false
            pictureInPicture.value = false
        }''')
write(p,s)
p=A+'feature/player/PlayerRoot.kt'
imports(p,'androidx.compose.animation.AnimatedVisibility','androidx.compose.animation.ExitTransition','androidx.compose.animation.fadeIn','androidx.compose.animation.core.tween','com.yfuse.core.designsystem.LocalAccessibilityOptions','com.yfuse.core.designsystem.Motion')
s=read(p).replace('''            if (!inPictureInPicture) {
                PlayerControls(''','''            AnimatedVisibility(
                visible = !inPictureInPicture,
                enter = fadeIn(tween(if (LocalAccessibilityOptions.current.reduceMotion) 0 else Motion.QUICK)),
                exit = ExitTransition.None,
            ) {
                PlayerControls(''',1);write(p,s)

# Keep long expressions reviewable after the formatter's first pass.
p=C+'feature/servers/ServersTabScreen.kt'
s=read(p).replace("// A new request also cancels the previous toast's timeout; it must not dismiss this generation.","// A new request cancels the previous toast's timer; it cannot dismiss this generation.")
write(p,s)
