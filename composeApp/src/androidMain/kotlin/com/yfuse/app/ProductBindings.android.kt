package com.yfuse.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.handoff.HandoffPlaybackRegistry
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.feature.handoff.AndroidHandoffReceiver
import com.yfuse.feature.handoff.HandoffIncomingPrompt
import com.yfuse.feature.player.ActivePlayback
import org.koin.core.context.GlobalContext

@Composable
actual fun BindProductServices(root: RootComponent) {
    val context = LocalContext.current
    val koin = remember { GlobalContext.get() }
    val personal = remember { koin.get<PersonalLibraryRepository>() }
    val session = remember { koin.get<ProductSession>() }
    val bridge = remember { koin.get<HandoffPlaybackRegistry>() }
    val controller = remember { koin.get<HandoffController>() }
    val sync = remember { koin.get<PlaybackSyncManager>() }
    remember { koin.get<com.yfuse.core.trakt.TraktRepository>() }
    val receiver =
        remember(root) {
            AndroidHandoffReceiver(
                context,
                koin.get(),
                koin.get(),
                personal,
                koin.get(),
                koin.get(),
                koin.get(),
                bridge,
            )
        }
    val owner by session.owner.collectAsState()
    val policy by personal.policy.collectAsState()
    var previousScope by remember { mutableStateOf(personal.storageNamespace) }
    var previousPolicy by remember { mutableStateOf(policy) }
    LaunchedEffect(owner, policy) {
        val current = personal.storageNamespace
        if (previousScope != current || previousPolicy != policy) {
            ActivePlayback.close()
            root.resetPersonalRoutes()
            previousScope = current
            previousPolicy = policy
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, receiver) {
        bridge.receiver = receiver
        controller.start()
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        session.foreground.value = true
                        sync.setAppForeground(true)
                    }
                    Lifecycle.Event.ON_STOP -> {
                        session.foreground.value = false
                        sync.setAppForeground(false)
                    }
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        session.foreground.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        sync.setAppForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            session.foreground.value = false
            sync.setAppForeground(false)
            if (bridge.receiver === receiver) {
                controller.close()
                bridge.receiver = null
            }
        }
    }
    HandoffIncomingPrompt(controller)
}
