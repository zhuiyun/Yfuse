package com.yfuse.feature.servers

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.dto.PublicUserDto
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.DiscoveredServer
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.network.createLanDiscovery
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 添加服务器 form: protocol segment + address + port, plus the credentials. */
data class LoginForm(
    /** Optional on first login; prefilled with the saved display name while editing. */
    val serverName: String = "",
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

/**
 * Splits a saved server's absolute baseUrl (e.g. `https://demo.example.com:8096`) back into
 * the (https, host, port) triple the add-server form expects. Falls back to the form's
 * defaults when the URL is missing components, so editing never throws.
 */
internal fun parseBaseUrl(baseUrl: String): Triple<Boolean, String, String> {
    val https = baseUrl.startsWith("https://", ignoreCase = true)
    val withoutScheme = baseUrl.substringAfter("://", baseUrl).trimEnd('/')
    val host = withoutScheme.substringBefore(':')
    val port = withoutScheme.substringAfter(':', "").ifBlank { defaultServerPort(https).toString() }
    return Triple(https, host, port)
}

data class ServersState(
    val servers: List<SavedServer> = emptyList(),
    val defaultServerId: String? = null,
    val dialogVisible: Boolean = false,
    val form: LoginForm = LoginForm(),
    val scanning: Boolean = false,
    val discovered: List<DiscoveredServer> = emptyList(),
    val publicUsers: List<PublicUserDto> = emptyList(),
    /** True once an edit touches address/account fields; name changes do not set it. */
    val connectionEdited: Boolean = false,
    /** Non-null when the dialog is open in "edit existing server" mode; the saved server's
     *  id is preserved so [ServersStore] can replace it on submit (the user may change the
     *  host or account, which would otherwise create a new entry). */
    val editingServerId: String? = null,
)

sealed interface ServersIntent {
    data object OpenAddDialog : ServersIntent
    data object DismissDialog : ServersIntent
    /** Open the dialog in edit mode, prefilled from the saved server. Renaming is local;
     *  changing the host or account still requires re-authentication. */
    data class EditServer(val server: SavedServer) : ServersIntent
    data class ServerNameChanged(val value: String) : ServersIntent
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
    data class EditOpen(val server: SavedServer) : Msg
    data class ServerName(val v: String) : Msg
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

        private var scanJob: Job? = null
        private var publicUsersJob: Job? = null
        private var scanRequestId = 0
        private var publicUsersRequestId = 0

        private fun cancelDialogJobs() {
            scanRequestId++
            publicUsersRequestId++
            scanJob?.cancel()
            publicUsersJob?.cancel()
            scanJob = null
            publicUsersJob = null
        }

        override fun executeAction(action: Action) = when (action) {
            is Action.Data -> dispatch(Msg.Data(action.servers, action.defaultId))
        }

        override fun executeIntent(intent: ServersIntent) {
            when (intent) {
                ServersIntent.OpenAddDialog -> {
                    cancelDialogJobs()
                    dispatch(Msg.DialogOpen)
                }
                ServersIntent.DismissDialog -> {
                    cancelDialogJobs()
                    dispatch(Msg.DialogClose)
                }
                is ServersIntent.EditServer -> {
                    cancelDialogJobs()
                    dispatch(Msg.EditOpen(intent.server))
                }
                is ServersIntent.ServerNameChanged -> dispatch(Msg.ServerName(intent.value))
                is ServersIntent.ProtocolChanged -> dispatch(Msg.Protocol(intent.https))
                is ServersIntent.HostChanged -> {
                    val parsed = parseServerAddress(intent.value)
                    if (parsed != null && (parsed.https != null || parsed.port != null)) {
                        parsed.https?.let { scheme ->
                            dispatch(Msg.Protocol(scheme))
                            if (parsed.port == null) dispatch(Msg.Port(defaultServerPort(scheme)))
                        }
                        parsed.port?.let { dispatch(Msg.Port(it)) }
                        dispatch(Msg.Host(parsed.host))
                    } else {
                        dispatch(Msg.Host(intent.value))
                    }
                }
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
            scanJob?.cancel()
            val requestId = ++scanRequestId
            dispatch(Msg.ScanStarted)
            scanJob = scope.launch {
                val result = runCatching { discovery.discover() }
                if (requestId != scanRequestId) return@launch
                result
                    .onSuccess {
                        AppLog.info(
                            category = "server.discovery",
                            event = "scan_completed",
                            message = "Local server discovery completed",
                            attributes = mapOf("serverCount" to it.size.toString()),
                        )
                    }
                    .onFailure {
                        AppLog.warning(
                            category = "server.discovery",
                            event = "scan_failed",
                            message = "Local server discovery failed",
                            throwable = it,
                        )
                    }
                dispatch(Msg.ScanDone(result.getOrDefault(emptyList())))
                scanJob = null
            }
        }

        private fun selectDiscovered(server: DiscoveredServer) {
            publicUsersJob?.cancel()
            val requestId = ++publicUsersRequestId
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
            dispatch(Msg.PublicUsers(emptyList()))
            publicUsersJob = scope.launch {
                val result = repo.publicUsers(server.address)
                if (requestId != publicUsersRequestId) return@launch
                result.onFailure {
                    AppLog.warning(
                        category = "server.auth",
                        event = "public_users_failed",
                        message = "Failed to load public users from discovered server",
                        throwable = it,
                    )
                }
                dispatch(Msg.PublicUsers(result.getOrDefault(emptyList())))
                publicUsersJob = null
            }
        }

        private fun submit() {
            val form = state().form
            if (!form.canSubmit) return
            val editingId = state().editingServerId
            val existing = editingId?.let { id -> state().servers.firstOrNull { it.id == id } }
            val requestedName = form.serverName
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .take(60)
            if (existing != null && requestedName.isBlank()) {
                dispatch(Msg.SubmitError("服务器名称不能为空"))
                return
            }

            // A display-name-only edit is local metadata. Keep the token, server id and
            // default selection intact instead of asking the user to enter their password.
            if (
                existing != null &&
                form.password.isBlank() &&
                !state().connectionEdited
            ) {
                if (!registry.rename(existing.id, requestedName)) {
                    dispatch(Msg.SubmitError("服务器已不存在，请重新打开编辑页面"))
                    return
                }
                cancelDialogJobs()
                dispatch(Msg.SubmitDone)
                return
            }
            dispatch(Msg.Submitting)
            scope.launch {
                repo.authenticate(form.url, form.username.trim(), form.password)
                    .onSuccess {
                        val authResult = it
                        // Preserve the user's chosen server name when editing — otherwise
                        // a fresh login would clobber it with whatever the server reports
                        // as the user's display name. New servers fall back to that name.
                        val savedServer = authResult.toSavedServer(
                            serverName = requestedName.takeIf { it.isNotBlank() }
                                ?: existing?.serverName,
                        )
                        val saved = if (editingId == null) {
                            registry.addOrUpdate(savedServer)
                            true
                        } else {
                            registry.replace(editingId, savedServer)
                        }
                        if (!saved) {
                            dispatch(Msg.SubmitError("原服务器已不存在，请重新添加"))
                            return@onSuccess
                        }
                        AppLog.info(
                            category = "server.auth",
                            event = "login_succeeded",
                            message = "Server login succeeded",
                            attributes = mapOf("serverId" to savedServer.id),
                        )
                        cancelDialogJobs()
                        dispatch(Msg.SubmitDone)
                        publish(ServersLabel.ServerAdded)
                    }
                    .onFailure {
                        AppLog.warning(
                            category = "server.auth",
                            event = "login_failed",
                            message = "Server login failed",
                            throwable = it,
                            attributes = mapOf(
                                "scheme" to if (form.https) "https" else "http",
                            ),
                        )
                        dispatch(Msg.SubmitError(it.toUserMessage("登录失败")))
                    }
            }
        }
    }

    private object ReducerImpl : Reducer<ServersState, Msg> {
        override fun ServersState.reduce(msg: Msg): ServersState = when (msg) {
            is Msg.Data -> copy(servers = msg.servers, defaultServerId = msg.defaultId)
            Msg.DialogOpen -> copy(
                dialogVisible = true,
                form = LoginForm(),
                editingServerId = null,
                scanning = false,
                discovered = emptyList(),
                publicUsers = emptyList(),
                connectionEdited = false,
            )
            Msg.DialogClose -> copy(
                dialogVisible = false,
                form = LoginForm(),
                editingServerId = null,
                scanning = false,
                discovered = emptyList(),
                publicUsers = emptyList(),
                connectionEdited = false,
            )
            is Msg.EditOpen -> {
                // Reuse the add dialog in-place by prefilling the form from the saved
                // server. Password is deliberately left blank — the stored access token
                // can't be reversed to a password, and any host/account change requires
                // re-authenticating anyway. editingServerId tells submit() to treat the
                // result as a replacement rather than a brand-new server.
                val (https, host, port) = parseBaseUrl(msg.server.baseUrl)
                copy(
                    dialogVisible = true,
                    editingServerId = msg.server.id,
                    scanning = false,
                    discovered = emptyList(),
                    publicUsers = emptyList(),
                    connectionEdited = false,
                    form = LoginForm(
                        serverName = msg.server.serverName,
                        https = https,
                        host = host,
                        port = port,
                        username = msg.server.userName,
                        password = "",
                    ),
                )
            }
            is Msg.ServerName -> copy(
                form = form.copy(serverName = msg.v.take(60), error = null),
            )
            is Msg.Protocol -> copy(
                form = form.copy(
                    https = msg.https,
                    port = defaultServerPort(msg.https),
                    error = null,
                ),
                connectionEdited = true,
            )
            is Msg.Host -> {
                val parsed = parseServerAddress(msg.v)
                if (parsed == null) {
                    copy(
                        form = form.copy(host = msg.v, error = null),
                        connectionEdited = true,
                    )
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
                        connectionEdited = true,
                    )
                }
            }
            is Msg.Port -> copy(
                form = form.copy(port = msg.v, error = null),
                connectionEdited = true,
            )
            is Msg.Username -> copy(
                form = form.copy(username = msg.v, error = null),
                connectionEdited = true,
            )
            is Msg.Password -> copy(form = form.copy(password = msg.v, error = null))
            Msg.Submitting -> copy(form = form.copy(submitting = true, error = null))
            Msg.SubmitDone -> copy(
                dialogVisible = false,
                form = LoginForm(),
                editingServerId = null,
                scanning = false,
                discovered = emptyList(),
                publicUsers = emptyList(),
                connectionEdited = false,
            )
            is Msg.SubmitError -> copy(form = form.copy(submitting = false, error = msg.m))
            Msg.ScanStarted -> copy(scanning = true, discovered = emptyList())
            is Msg.ScanDone -> if (dialogVisible) {
                copy(scanning = false, discovered = msg.servers)
            } else {
                this
            }
            is Msg.PublicUsers -> if (dialogVisible) copy(publicUsers = msg.users) else this
        }
    }
}
