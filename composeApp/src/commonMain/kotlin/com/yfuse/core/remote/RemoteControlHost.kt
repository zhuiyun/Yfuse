package com.yfuse.core.remote

import com.yfuse.core.sync.AccountRequiredForWatchException
import com.yfuse.core.sync.backoffDelayMs
import com.yfuse.core.sync.isWatchAuthenticationFailure
import com.yfuse.watch.protocol.RemoteControlKey
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** What a phone the television has let in did on 手机遥控. */
sealed interface RemoteControlEvent {
    data class Key(
        val key: RemoteControlKey,
    ) : RemoteControlEvent

    /** The phone's whole field; empty clears it. */
    data class Text(
        val text: String,
    ) : RemoteControlEvent
}

/**
 * A phone on this television's 手机遥控. [deviceId] is how the relay names it: the phone's own id,
 * the same every time it connects, or a stand-in that lasts one connection for a phone or a relay
 * from before [WatchProtocol.CAPABILITY_REMOTE_PAIRING]. [name] is what the phone calls itself.
 */
data class RemoteControlPhone(
    val deviceId: String,
    val name: String?,
    /** What it sends reaches the television: let in for now, or trusted for good. */
    val allowed: Boolean,
) {
    /** Whether 始终允许此设备 can hold for it: only a phone's own id is the same next time. */
    val rememberable: Boolean
        get() = WatchProtocol.isStableRemoteDeviceId(deviceId)
}

/**
 * The television's side of 手机遥控. While [setActive] holds and an account is signed in, it keeps
 * one relay socket hosting a remote session under this device's account session and reconnects
 * when that drops. [hosting] is what the handoff heartbeat advertises. A relay that does not offer
 * [WatchProtocol.CAPABILITY_REMOTE_CONTROL] is not asked again until the next activation, and a
 * lapsed account is left to the account screens.
 *
 * Signing in to the same account is not enough to press keys on this television. [phones] lists
 * who is on, and only what a phone the viewer let in ([allow]), or one [trusted] for good, reaches
 * [events]. Anything else is dropped rather than held, so nothing pressed while a phone waited
 * lands later. The relay hears of each phone let in and tells that phone, which says 等待电视确认
 * until then. [release] lets a phone go — refusing it, if it was still waiting. A relay without
 * [WatchProtocol.CAPABILITY_REMOTE_PAIRING] cannot tell phones apart or let one go: each newcomer
 * waits, and letting them go means hosting afresh, which every phone hears as its television
 * leaving.
 */
class RemoteControlHost(
    private val signedIn: StateFlow<Boolean>,
    private val accessToken: suspend () -> String?,
    private val refreshAccessToken: suspend () -> String?,
    /** Whether the phone of this id was let in with 始终允许此设备. */
    private val trusted: (String) -> Boolean = { false },
    private val url: String? = REMOTE_RELAY_URL,
    private val connector: RemoteRelayConnector = KtorRemoteRelayConnector,
    private val retryDelayMs: (Int) -> Long = ::backoffDelayMs,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val active = MutableStateFlow(false)
    private val _hosting = MutableStateFlow(false)
    val hosting: StateFlow<Boolean> = _hosting.asStateFlow()
    private val _events = MutableSharedFlow<RemoteControlEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<RemoteControlEvent> = _events.asSharedFlow()
    private val pairing = MutableStateFlow(RemotePairing())

    /** Phones on this television now, in the order they connected. */
    val phones: StateFlow<List<RemoteControlPhone>> =
        pairing.map { it.phones }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The viewer's 拒绝 and 断开, for whichever socket is hosting to pass on. */
    private val releases = Channel<Release>(Channel.UNLIMITED)

    /** Who was let in, for whichever socket is hosting to tell the relay, so that phone stops waiting. */
    private val signals = Channel<Signal>(Channel.UNLIMITED)

    init {
        scope.launch {
            combine(active, signedIn) { foreground, account -> foreground && account }
                .distinctUntilChanged()
                .collectLatest { run -> if (run) host() }
        }
    }

    /** The app is in the foreground and 手机遥控 is on: host while it is, and stop once it is not. */
    fun setActive(value: Boolean) {
        active.value = value
    }

    /** 允许一次, or 始终允许此设备 once the caller has remembered it: what [deviceId] sends counts. */
    fun allow(deviceId: String) {
        changePairing { it.allow(deviceId) }
    }

    /** Lets [deviceId] go: 拒绝 for a phone still waiting, 断开 for one already in. */
    fun release(deviceId: String) {
        var refused = true
        pairing.update { current ->
            refused = !current.admits(deviceId)
            current.release(deviceId)
        }
        releases.trySend(Release(deviceId, refused))
    }

    /** 断开 for every phone on this television. */
    fun releaseAll() {
        pairing.value.phones.forEach { release(it.deviceId) }
    }

    private suspend fun host() {
        var failures = 0
        var refreshed = false
        try {
            while (true) {
                val failure =
                    runCatching {
                        hostOnce {
                            failures = 0
                            refreshed = false
                        }
                    }.exceptionOrNull()
                _hosting.value = false
                if (failure is CancellationException) throw failure
                if (failure is RemoteControlRefusedException && !failure.supported) return
                if (failure is PhonesLetGoException) {
                    // Long enough for the relay to see the old socket go before this one hosts:
                    // hosting first would hand the same phones straight back.
                    delay(retryDelayMs(1))
                    continue
                }
                if (failure != null && failure.isWatchAuthenticationFailure()) {
                    if (refreshed || refreshAccessToken() == null) return
                    refreshed = true
                    continue
                }
                failures++
                delay(retryDelayMs(failures))
            }
        } finally {
            _hosting.value = false
            // Every phone hears its television leave; each one is asked about again next time.
            pairing.value = RemotePairing()
            drainReleases()
            while (signals.tryReceive().isSuccess) Unit
        }
    }

    private suspend fun hostOnce(onHosting: () -> Unit) {
        val relay = url ?: throw RemoteControlRefusedException("手机遥控服务地址无效", supported = false)
        val token = accessToken() ?: throw AccountRequiredForWatchException()
        connector.connect(relay, token) { channel ->
            // This television asks before a phone may press anything: a relay that knows to tells
            // each phone to wait, and passes on who is let in.
            channel.send(
                WatchWireMessage(type = "remoteHost", capabilities = listOf(WatchProtocol.CAPABILITY_REMOTE_PAIRING)),
            )
            coroutineScope {
                var answering: Job? = null
                try {
                    while (true) {
                        val message = channel.receive() ?: break
                        when (message.type) {
                            "remoteHosting" -> {
                                val offered = message.capabilities.orEmpty()
                                if (WatchProtocol.CAPABILITY_REMOTE_CONTROL !in offered) {
                                    throw RemoteControlRefusedException("服务器暂不支持手机遥控", supported = false)
                                }
                                onHosting()
                                val staying = message.participantCount ?: 0
                                // No phone stayed on through the reconnect: none is left to let go.
                                if (staying == 0) drainReleases()
                                pairing.update { it.hosted(staying) }
                                _hosting.value = true
                                if (answering == null) answering = launch { answer(channel, offered) }
                            }
                            "remoteConnected" ->
                                changePairing { it.connected(message.phone(), message.phoneName(), trusted) }
                            "remoteDisconnected" ->
                                pairing.update { it.disconnected(message.phone(), message.participantCount) }
                            "remoteKey" ->
                                RemoteControlKey.fromWireName(message.remoteKey)?.let { key ->
                                    if (admits(message)) _events.tryEmit(RemoteControlEvent.Key(key))
                                }
                            "remoteText" ->
                                message.text?.takeIf(WatchProtocol::isValidRemoteText)?.let { text ->
                                    if (admits(message)) _events.tryEmit(RemoteControlEvent.Text(text))
                                }
                            // Before the relay has taken the session an error is its answer; after, it is noise.
                            "error" -> if (!_hosting.value) throw message.remoteRefusal("电视未能开启手机遥控")
                        }
                    }
                } finally {
                    answering?.cancel()
                }
            }
        }
    }

    /** Input counts only from a phone let in; from one not heard of before, it starts the asking. */
    private fun admits(message: WatchWireMessage): Boolean {
        val phone = message.phone()
        return changePairing { it.heard(phone, trusted) }.admits(phone)
    }

    /** Applies [change], and has the relay tell each phone it let in that it may press keys now. */
    private fun changePairing(change: (RemotePairing) -> RemotePairing): RemotePairing {
        while (true) {
            val previous = pairing.value
            val next = change(previous)
            if (pairing.compareAndSet(previous, next)) {
                next.admittedSince(previous).forEach { signals.trySend(Signal.Admit(it)) }
                return next
            }
        }
    }

    /**
     * Passes the viewer's answers to the relay as they come: 拒绝 and 断开, and who was let in. A relay
     * that cannot let one phone go is left instead, and hosted again after a pause, which ends every
     * phone's session; one that does not name phones cannot be told who was let in, and never makes
     * a phone wait for it.
     */
    private suspend fun answer(
        channel: RemoteRelayChannel,
        offered: List<String>,
    ) {
        val namesPhones = WatchProtocol.CAPABILITY_REMOTE_PAIRING in offered
        while (true) {
            select {
                releases.onReceive { release ->
                    if (!namesPhones) throw PhonesLetGoException()
                    channel.send(
                        WatchWireMessage(
                            type = "remoteRelease",
                            remoteDeviceId = release.deviceId,
                            errorCode = WatchProtocol.REMOTE_REFUSED_CODE.takeIf { release.refused },
                        ),
                    )
                }
                signals.onReceive { signal ->
                    when (signal) {
                        is Signal.Admit ->
                            if (namesPhones) {
                                channel.send(WatchWireMessage(type = "remoteAdmit", remoteDeviceId = signal.deviceId))
                            }
                    }
                }
            }
        }
    }

    private fun drainReleases() {
        while (releases.tryReceive().isSuccess) Unit
    }

    private data class Release(
        val deviceId: String,
        val refused: Boolean,
    )

    /** What this television tells the relay of its own accord. */
    private sealed interface Signal {
        /** The viewer, or trust, let the phone of [deviceId] in. */
        data class Admit(
            val deviceId: String,
        ) : Signal
    }

    /** Ends a session on a relay that cannot let one phone go, so that all of them go. */
    private class PhonesLetGoException : Exception()

    private companion object {
        const val EVENT_BUFFER = 64
    }
}

/** The phone a relay message is about, or null from a relay that does not name phones. */
private fun WatchWireMessage.phone(): String? = remoteDeviceId?.takeIf(WatchProtocol::isValidRemoteDeviceId)

/** What the phone calls itself; shown on the television, so only a name the relay would pass. */
private fun WatchWireMessage.phoneName(): String? =
    name?.takeIf { it.isNotBlank() && WatchProtocol.isValidOptionalName(it) }
