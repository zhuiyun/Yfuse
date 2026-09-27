package com.yfuse.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.handoff.HandoffPlaybackRegistry
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.core.sync.playback.PlaybackSyncManager
import com.yfuse.feature.handoff.AndroidHandoffReceiver
import com.yfuse.feature.handoff.HandoffBanner
import com.yfuse.feature.handoff.HandoffIncomingPrompt
import com.yfuse.feature.handoff.rememberHandoffBanner
import com.yfuse.feature.player.ActivePlayback
import com.yfuse.tv.player.isTelevisionDevice
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
    // A television keeps the dialog: a remote moves focus, and a banner that takes none could
    // never be reached from it.
    val television = remember(context) { isTelevisionDevice(context) }
    if (television) {
        HandoffIncomingPrompt(controller)
    } else {
        PhoneHandoffBanner(controller, personal)
    }
}

/**
 * 接力横幅 in a window of its own, so it floats over whichever page is up without taking it over:
 * it takes no focus and no touches outside itself. The window exists only while there is a banner
 * to show, and, like the shell's own dialogs, never over the launch splash.
 */
@Composable
private fun PhoneHandoffBanner(
    controller: HandoffController,
    personal: PersonalLibraryRepository,
) {
    // Another profile's title is not this device's to continue; its receiver would refuse it anyway.
    val banner = rememberHandoffBanner(controller) { it.profileId == personal.activeProfileId }
    if (!banner.present || !LocalRouteVisible.current) return
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        HandoffBanner(banner, Modifier.padding(top = top + Dimens.space.sm))
    }
}
