from edit import *

p='composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerChrome.kt'
t=read(p)
# Remove only the obsolete entry points and their private-only helpers, preserving the shared refined controls.
start=t.index('/** `padding:14px 22px`,');end=t.index('/**',t.index('internal fun TransportRow')) if False else t.rfind('/**',start,t.index('internal fun TransportRow'))
t=t[:start]+t[end:]
start=t.index('/** `padding:14px 22px 16px`,');end=t.index('@Composable\ninternal fun TrickplayPreview', start)
t=t[:start]+t[end:]
start=t.index('@Composable\ninternal fun SeekBar(');end=t.index('/** `rgba(255,255,255,.16)` circle',start)
t=t[:start]+t[end:]
for declaration in ('/** A forgiving touch target around the visually slim playback track and its boundary labels. */\nprivate val SeekBarTouchHeight = 48.dp\n\n', 'private val LiquidProgressBlue = Color(0xFF4F8DFF)\n\n','private val LiquidProgressViolet = Color(0xFF7D5FF6)\n\n'):
    t=t.replace(declaration,'')
start=t.index('                AsyncImage(', t.index('internal fun TrickplayPreview'));end=t.index('\n            }\n            Text(',start)
t=t[:start]+'''                TrickplayImage(
                    storyboard = storyboard,
                    frame = frame,
                    description = "${formatTime(positionMs)} 预览",
                    modifier = Modifier.fillMaxSize(),
                )'''+t[end:]
write(p,t)

p='composeApp/build.gradle.kts'
write(p,read(p)+'''
// Successful verification is reusable only for exactly the same inputs and validation code.
// --rerun-tasks still forces every check; failure never writes a success marker.
listOf(
    verifyCustomMpvArtifact,
    verifyStandaloneYCoreArtifact,
    verifyYCoreGpuCompanionArtifact,
    verifyMdkArtifact,
    verifyMediaTestManifest,
    verifyBehavioralTestBoundaries,
).forEach { verification ->
    verification.configure {
        inputs.file(layout.projectDirectory.file("build.gradle.kts"))
        val marker = layout.buildDirectory.file("verification/$name.success")
        outputs.file(marker)
        doLast {
            marker.get().asFile.apply {
                parentFile.mkdirs()
                writeText("verified\\n")
            }
        }
    }
}
''')
p='composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryStore.kt'
replace(p,'val snapshot = withContext(workContext) { cache.readSnapshot(server.id) }', '''val snapshot = withContext(workContext) {
                            runCatching { cache.readSnapshot(server.id) }
                                .onFailure { AppLog.warning("feature.library", "cache_read_failed", "Library cache is unavailable; continuing online", throwable = it) }
                                .getOrNull()
                        }''')
replace(p, 'withContext(workContext) { cache.write(server.id, content, updatedAtEpochMs) }', '''withContext(workContext) {
                                    runCatching { cache.write(server.id, content, updatedAtEpochMs) }
                                        .onFailure { AppLog.warning("feature.library", "cache_write_failed", "Loaded library could not be cached", throwable = it) }
                                }''')

write('audit/runtime-optimization-20260910/test.ps1',read('audit/project-implementation-20260910/test.ps1'))
