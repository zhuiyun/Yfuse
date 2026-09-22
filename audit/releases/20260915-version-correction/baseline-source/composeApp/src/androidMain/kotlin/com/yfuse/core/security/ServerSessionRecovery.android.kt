package com.yfuse.core.security

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.AsyncServerSessionStartup
import com.yfuse.core.data.SessionStartupPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

/** Application installs this before any account or server-dependent work starts. Main-thread owned. */
object ServerSessionRecovery {
    private var startup: AsyncServerSessionStartup? = null
    private var startupScope: CoroutineScope? = null

    fun initialize(
        restore: () -> Unit,
        startServices: () -> Unit,
    ) {
        startupScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        startupScope = scope
        startup = AsyncServerSessionStartup(scope, restore, startServices, Dispatchers.IO).also { it.start() }
    }

    suspend fun awaitReady() {
        startup?.phase?.first { it == SessionStartupPhase.Ready }
    }

    fun showIfNeeded(activity: ComponentActivity): Boolean {
        val pending = startup?.takeUnless { it.phase.value == SessionStartupPhase.Ready } ?: return false
        activity.setContent {
            val phase by pending.phase.collectAsState()
            val restoring = phase == SessionStartupPhase.Restoring
            val retryFocus = remember { FocusRequester() }
            LaunchedEffect(phase) {
                when (phase) {
                    SessionStartupPhase.Ready -> activity.recreate()
                    SessionStartupPhase.NeedsRetry -> retryFocus.requestFocus()
                    SessionStartupPhase.Restoring -> Unit
                }
            }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("恢复登录信息", style = MaterialTheme.typography.headlineSmall)
                        Text("服务器和缓存均已保留。若暂时无法读取，请解锁设备后重试。")
                        if (restoring) {
                            CircularProgressIndicator()
                        } else {
                            Button(onClick = pending::start, modifier = Modifier.focusRequester(retryFocus)) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
        }
        return true
    }
}
