from edit import *

p='composeApp/src/commonMain/kotlin/com/yfuse/app/RootComponent.kt'
replace(p, '''    init {
        syncManager.start(scope)
        dependencies.serverHealthMonitor.start(scope)
    }''', '''    private var backgroundServicesStarted = false

    /** Called by the visible shell after its first frame; network monitors do not block construction. */
    fun startBackgroundServices() {
        if (backgroundServicesStarted) return
        backgroundServicesStarted = true
        syncManager.start(scope)
        dependencies.serverHealthMonitor.start(scope)
    }''')
write('composeApp/src/commonMain/kotlin/com/yfuse/app/BackgroundServiceBinding.kt', '''package com.yfuse.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos

/** Two frame boundaries allow the initial composition to be displayed before synchronization starts. */
@Composable
fun BindBackgroundServices(root: RootComponent) {
    LaunchedEffect(root) {
        withFrameNanos { }
        withFrameNanos { }
        root.startBackgroundServices()
    }
}
''')
p='composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt'
replace(p, 'fun App(root: RootComponent) {', 'fun App(root: RootComponent) {\n    BindBackgroundServices(root)')
p='tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvApp.kt'
replace(p, 'import com.yfuse.app.RootComponent', 'import com.yfuse.app.RootComponent\nimport com.yfuse.app.BindBackgroundServices')
replace(p, 'fun TvRoot(component: RootComponent) {', 'fun TvRoot(component: RootComponent) {\n    BindBackgroundServices(component)')
paths=read('audit/runtime-optimization-20260910/format-files.txt').splitlines()
paths+=['composeApp/src/commonMain/kotlin/com/yfuse/app/RootComponent.kt','composeApp/src/commonMain/kotlin/com/yfuse/app/BackgroundServiceBinding.kt']
write('audit/runtime-optimization-20260910/format-files.txt','\n'.join(sorted(set(paths)))+'\n')
