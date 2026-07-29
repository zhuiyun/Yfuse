package com.yfuse.feature.servers

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.dto.PublicUserDto
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.DiscoveredServer
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.network.createLanDiscovery
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 添加服务器 form: protocol segment + address + port, plus the credentials. */
data class LoginForm(
    /** The prototype defaults the protocol segment to HTTPS. */
    val https: Boolean = true,
    val host: String = "",
    val port: String = "443",
    val username: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val url: String
        get() {
            val parsed = parseServerAddress(host)
            val resolvedHttps = parsed?.https ?: https
            val resolvedHost = parsed?.host?.takeIf(String::isNotBlank) ?: host.trim()
            val resolvedPort = parsed?.port ?: port.trim()
            return buildString {
                append(if (resolvedHttps) "https://" else "http://")
                append(resolvedHost)
                val p = resolvedPort.trim()
                if (p.isNotEmpty()) {
                    append(':')
                    append(p)
                }
            }
        }

    val canSubmit: Boolean
        get() = (parseServerAddress(host)?.host ?: host.trim()).isNotBlank() &&
            username.isNotBlank() && !submitting
}

internal data class ParsedServerAddress(
    val https: Boolean?,
    val host: String,
    val port: String?,
)

internal fun defaultServerPort(https: Boolean): String = if (https) "443" else "8096"

/**
 * Accepts `host`, `host:port`, `http://host` and `https://host:port`.
 * A partial scheme such as `http://` is deliberately left untouched while typing.
 */
internal fun parseServerAddress(value: String): ParsedServerAddress? {
    val trimmed = value.trim().trimEnd('/')
    val match = Regex("""^(?:(https?)://)?([^/:?#]+)(?::(\d+))?$""")
        .matchEntire(trimmed)
        ?: return null
    val scheme = match.groupValues[1].takeIf(String::isNotBlank)
    val host = match.groupValues[2]
    val port = match.groupValues[3].takeIf(String::isNotBlank)
    return ParsedServerAddress(
        https = scheme?.equals("https", ignoreCase = true),
        host = host,
        port = port,
    )
}

data class ServersState(
    val servers: List<SavedServer> = emptyList(),
    val defaultServerId: String? = null,
    val dialogVisible: Boolean = false,
    val form: LoginForm = LoginForm(),
    val scanning: Boolean = false,
    val discovered: List<DiscoveredServer> = emptyList(),
    val publicUsers: List<PublicUserDto> = emptyList(),
)

sealed interface ServersIntent {
    data object OpenAddDialog : ServersIntent
    data object DismissDialog : ServersIntent
    data class ProtocolChanged(val https: Boolean) : ServersIntent
    data class HostChanged(val value: String) : ServersIntent
    data class PortChanged(val value: String) : ServersIntent
    data class UsernameChanged(val value: String) : ServersIntent
    data class PasswordChanged(val value: String) : ServersIntent
    data object Submit : ServersIntent
    data object Scan : ServersIntent
    data class SelectDiscovered(val server: DiscoveredServer) : ServersIntent
    data class SelectPublicUser(val name: String) : ServersIntent
    data class SelectDefault(val id: String) : ServersIntent
    data class Remove(val id: String) : ServersIntent
}

sealed interface ServersLabel {
    /** A server was just added/logged in; the shell may jump to the library tab. */
    data object ServerAdded : ServersLabel
}

private sealed interface Action {
    data class Data(val servers: List<SavedServer>, val defaultId: String?) : Action
}

private sealed interface Msg {
    data class Data(val servers: List<SavedServer>, val defaultId: String?) : Msg
    data object DialogOpen : Msg
    data object DialogClose : Msg
    data class Protocol(val https: Boolean) : Msg
    data class Host(val v: String) : Msg
    data class Port(val v: String) : Msg
    data class Username(val v: String) : Msg
    data class Password(val v: String) : Msg
    data object Submitting : Msg
    data object SubmitDone : Msg
    data class SubmitError(val m: String) : Msg
    data object ScanStarted : Msg
    data class ScanDone(val servers: List<DiscoveredServer>) : Msg
    data class PublicUsers(val users: List<PublicUserDto>) : Msg
}

class ServersStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val discovery: LanDiscovery = createLanDiscovery(),
) {
    fun create(): Store<ServersIntent, ServersState, ServersLabel> =
        storeFactory.create(
            name = "ServersStore",
            initialState = ServersState(),
            bootstrapper = coroutineBootstrapper<Action> {
                registry.data
                    .onEach { dispatch(Action.Data(it.servers, it.defaultServerId)) }
                    .launchIn(this)
            },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<ServersIntent, Action, ServersState, Msg, ServersLabel>() {

        override fun executeAction(action: Action) = when (action) {
            is Action.Data -> dispatch(Msg.Data(action.servers, action.defaultId))
        }

        override fun executeIntent(intent: ServersIntent) {
            when (intent) {
                ServersIntent.OpenAddDialog -> dispatch(Msg.DialogOpen)
                ServersIntent.DismissDialog -> dispatch(Msg.DialogClose)
                is ServersIntent.ProtocolChanged -> dispatch(Msg.Protocol(intent.https))
                is ServersIntent.HostChanged -> dispatch(Msg.Host(intent.value))
                is ServersIntent.PortChanged -> dispatch(Msg.Port(intent.value))
                is ServersIntent.UsernameChanged -> dispatch(Msg.Username(intent.value))
                is ServersIntent.PasswordChanged -> dispatch(Msg.Password(intent.value))
                ServersIntent.Submit -> submit()
                ServersIntent.Scan -> scan()
                is ServersIntent.SelectDiscovered -> selectDiscovered(intent.server)
                is ServersIntent.SelectPublicUser ->
                    dispatch(Msg.Username(intent.name))
                is ServersIntent.SelectDefault -> registry.setDefault(intent.id)
                is ServersIntent.Remove -> registry.remove(intent.id)
            }
        }

        private fun scan() {
            dispatch(Msg.ScanStarted)
            scope.launch {
                dispatch(Msg.ScanDone(runCatching { discovery.discover() }.getOrDefault(emptyList())))
            }
        }

        private fun selectDiscovered(server: DiscoveredServer) {
            val match = Regex("""^(https?)://([^/:]+)(?::(\d+))?""")
                .find(server.address.trim())
            val https = match?.groupValues?.getOrNull(1).equals("https", true)
            val host = match?.groupValues?.getOrNull(2).orEmpty()
            val port = match?.groupValues?.getOrNull(3)
                ?.takeIf(String::isNotBlank)
                ?: if (https) "443" else "8096"
            dispatch(Msg.Protocol(https))
            dispatch(Msg.Host(host))
            dispatch(Msg.Port(port))
            scope.launch {
                dispatch(Msg.PublicUsers(repo.publicUsers(server.address).getOrDefault(emptyList())))
            }
        }

        private fun submit() {
            val form = state().form
            if (!form.canSubmit) return
            dispatch(Msg.Submitting)
            scope.launch {
                repo.authenticate(form.url, form.username.trim(), form.password)
                    .onSuccess {
                        registry.addOrUpdate(it.toSavedServer())
                        dispatch(Msg.SubmitDone)
                        publish(ServersLabel.ServerAdded)
                    }
                    .onFailure { dispatch(Msg.SubmitError(it.toUserMessage("登录失败"))) }
            }
        }
    }

    private object ReducerImpl : Reducer<ServersState, Msg> {
        override fun ServersState.reduce(msg: Msg): ServersState = when (msg) {
            is Msg.Data -> copy(servers = msg.servers, defaultServerId = msg.defaultId)
            Msg.DialogOpen -> copy(dialogVisible = true, form = LoginForm())
            Msg.DialogClose -> copy(dialogVisible = false, form = LoginForm())
            is Msg.Protocol -> copy(
                form = form.copy(
                    https = msg.https,
                    port = defaultServerPort(msg.https),
                    error = null,
                ),
            )
            is Msg.Host -> {
                val parsed = parseServerAddress(msg.v)
                if (parsed == null) {
                    copy(form = form.copy(host = msg.v, error = null))
                } else {
                    val resolvedHttps = parsed.https ?: form.https
                    copy(
                        form = form.copy(
                            https = resolvedHttps,
                            host = parsed.host,
                            port = parsed.port
                                ?: if (parsed.https != null) {
                                    defaultServerPort(resolvedHttps)
                                } else {
                                    form.port
                                },
                            error = null,
                        ),
                    )
                }
            }
            is Msg.Port -> copy(form = form.copy(port = msg.v, error = null))
            is Msg.Username -> copy(form = form.copy(username = msg.v, error = null))
            is Msg.Password -> copy(form = form.copy(password = msg.v, error = null))
            Msg.Submitting -> copy(form = form.copy(submitting = true, error = null))
            Msg.SubmitDone -> copy(dialogVisible = false, form = LoginForm())
            is Msg.SubmitError -> copy(form = form.copy(submitting = false, error = msg.m))
            Msg.ScanStarted -> copy(scanning = true, discovered = emptyList())
            is Msg.ScanDone -> copy(scanning = false, discovered = msg.servers)
            is Msg.PublicUsers -> copy(publicUsers = msg.users)
        }
    }
}
