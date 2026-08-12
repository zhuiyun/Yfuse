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
import kotlinx.coroutines.delay
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
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    /** HTTP credentials and tokens are readable in transit; require a deliberate opt-in. */
    val httpRiskAccepted: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val url: String
        get() {
            val parsed = parseServerAddress(host)
            val resolvedHttps = parsed?.https ?: https
            val resolvedHost = parsed?.host?.takeIf(String::isNotBlank) ?: host.trim()
            val resolvedPort = parsed?.port ?: port.trim()
            val resolvedBasePath = parsed?.basePath?.takeIf(String::isNotBlank) ?: basePath
            return buildString {
                append(if (resolvedHttps) "https://" else "http://")
                append(resolvedHost)
                val p = resolvedPort.trim()
                if (p.isNotEmpty()) {
                    append(':')
                    append(p)
                }
                append(normalizeBasePath(resolvedBasePath))
            }
        }

    val canStartQuickConnect: Boolean
        get() = parseServerAddress(host)?.host?.isNotBlank() == true &&
            validServerPort(port) &&
            (https || httpRiskAccepted) &&
            !submitting

    val canSubmit: Boolean
        get() = canStartQuickConnect && username.isNotBlank()
}

internal data class ParsedServerAddress(
    val https: Boolean?,
    val host: String,
    val port: String?,
    val basePath: String,
)

internal fun defaultServerPort(https: Boolean): String = if (https) "443" else "8096"

/**
 * Accepts `host`, `host:port`, and complete HTTP(S) URLs with an optional base path.
 * A partial scheme such as `http://` is deliberately left untouched while typing.
 */
internal fun parseServerAddress(value: String): ParsedServerAddress? {
    val trimmed = value.trim()
    if (trimmed.isBlank() || trimmed.any(Char::isWhitespace)) return null

    val schemeEnd = trimmed.indexOf("://")
    val scheme = if (schemeEnd >= 0) trimmed.substring(0, schemeEnd) else null
    if (scheme != null && !scheme.equals("http", true) && !scheme.equals("https", true)) {
        return null
    }
    val remainder = if (schemeEnd >= 0) trimmed.substring(schemeEnd + 3) else trimmed
    val authorityEnd = listOf(remainder.indexOf('/'), remainder.indexOf('?'), remainder.indexOf('#'))
        .filter { it >= 0 }
        .minOrNull()
        ?: remainder.length
    val authority = remainder.substring(0, authorityEnd)
    if (authority.isBlank() || '@' in authority) return null

    val host: String
    val port: String?
    if (authority.startsWith('[')) {
        val closing = authority.indexOf(']')
        if (closing <= 1) return null
        host = authority.substring(0, closing + 1)
        val suffix = authority.substring(closing + 1)
        port = when {
            suffix.isBlank() -> null
            suffix.startsWith(':') && suffix.drop(1).all(Char::isDigit) -> suffix.drop(1)
            else -> return null
        }
    } else {
        val colon = authority.lastIndexOf(':')
        if (colon >= 0) {
            val candidatePort = authority.substring(colon + 1)
            if (candidatePort.isBlank() || !candidatePort.all(Char::isDigit)) return null
            host = authority.substring(0, colon)
            port = candidatePort
        } else {
            host = authority
            port = null
        }
    }
    if (host.isBlank()) return null
    val path = remainder.substring(authorityEnd)
        .substringBefore('?')
        .substringBefore('#')
    return ParsedServerAddress(
        https = scheme?.equals("https", ignoreCase = true),
        host = host,
        port = port,
        basePath = normalizeBasePath(path),
    )
}

internal fun normalizeBasePath(value: String): String {
    val path = value.trim().substringBefore('?').substringBefore('#').trim('/')
    return if (path.isBlank()) "" else "/$path"
}

internal fun validServerPort(value: String): Boolean =
    value.isBlank() || value.toIntOrNull()?.let { it in 1..65_535 } == true

/**
 * Splits a saved server's absolute baseUrl (e.g. `https://demo.example.com:8096`) back into
 * the (https, host, port) triple the add-server form expects. Falls back to the form's
 * defaults when the URL is missing components, so editing never throws.
 */
internal fun parseBaseUrl(baseUrl: String): ParsedServerAddress {
    val parsed = parseServerAddress(baseUrl)
    val https = parsed?.https ?: true
    return parsed?.copy(
        https = https,
        port = parsed.port ?: defaultServerPort(https),
    ) ?: ParsedServerAddress(
        https = true,
        host = baseUrl.trim(),
        port = defaultServerPort(true),
        basePath = "",
    )
}

sealed interface QuickConnectUiState {
    data object Idle : QuickConnectUiState
    data object CheckingSupport : QuickConnectUiState
    data class AwaitingApproval(val code: String, val expiresAtEpochMs: Long) : QuickConnectUiState
    data class Unsupported(val reason: String) : QuickConnectUiState
    data object Expired : QuickConnectUiState
    data object Cancelled : QuickConnectUiState
    data class Error(val message: String) : QuickConnectUiState
}

data class ServersState(
    val servers: List<SavedServer> = emptyList(),
    val defaultServerId: String? = null,
    val dialogVisible: Boolean = false,
    val form: LoginForm = LoginForm(),
    val scanning: Boolean = false,
    val discovered: List<DiscoveredServer> = emptyList(),
    val publicUsers: List<PublicUserDto> = emptyList(),
    val quickConnect: QuickConnectUiState = QuickConnectUiState.Idle,
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
    data class BasePathChanged(val value: String) : ServersIntent
    data class UsernameChanged(val value: String) : ServersIntent
    data class PasswordChanged(val value: String) : ServersIntent
    data class HttpRiskAcceptedChanged(val accepted: Boolean) : ServersIntent
    data object StartQuickConnect : ServersIntent
    data object CancelQuickConnect : ServersIntent
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
    data class BasePath(val v: String) : Msg
    data class Username(val v: String) : Msg
    data class Password(val v: String) : Msg
    data class HttpRiskAccepted(val accepted: Boolean) : Msg
    data class QuickConnect(val state: QuickConnectUiState) : Msg
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
    private val quickConnectGateway: QuickConnectGateway = UnsupportedQuickConnectGateway,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
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
        private var quickConnectJob: Job? = null
        private var activeQuickConnect: Pair<String, QuickConnectSession>? = null
        private var scanRequestId = 0
        private var publicUsersRequestId = 0
        private var quickConnectRequestId = 0

        private fun cancelDialogJobs() {
            scanRequestId++
            publicUsersRequestId++
            scanJob?.cancel()
            publicUsersJob?.cancel()
            stopQuickConnect(resetState = false, notifyGateway = true)
            scanJob = null
            publicUsersJob = null
        }

        private fun stopQuickConnect(resetState: Boolean, notifyGateway: Boolean) {
            quickConnectRequestId++
            quickConnectJob?.cancel()
            quickConnectJob = null
            val active = activeQuickConnect
            activeQuickConnect = null
            if (notifyGateway && active != null) {
                scope.launch {
                    quickConnectGateway.cancel(active.first, active.second.id)
                }
            }
            if (resetState) dispatch(Msg.QuickConnect(QuickConnectUiState.Idle))
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
                is ServersIntent.ProtocolChanged -> {
                    stopQuickConnect(resetState = true, notifyGateway = true)
                    dispatch(Msg.Protocol(intent.https))
                }
                is ServersIntent.HostChanged -> {
                    stopQuickConnect(resetState = true, notifyGateway = true)
                    dispatch(Msg.Host(intent.value))
                }
                is ServersIntent.PortChanged -> {
                    stopQuickConnect(resetState = true, notifyGateway = true)
                    dispatch(Msg.Port(intent.value))
                }
                is ServersIntent.BasePathChanged -> {
                    stopQuickConnect(resetState = true, notifyGateway = true)
                    dispatch(Msg.BasePath(intent.value))
                }
                is ServersIntent.UsernameChanged -> dispatch(Msg.Username(intent.value))
                is ServersIntent.PasswordChanged -> dispatch(Msg.Password(intent.value))
                is ServersIntent.HttpRiskAcceptedChanged ->
                    dispatch(Msg.HttpRiskAccepted(intent.accepted))
                ServersIntent.StartQuickConnect -> startQuickConnect()
                ServersIntent.CancelQuickConnect -> {
                    stopQuickConnect(resetState = false, notifyGateway = true)
                    dispatch(Msg.QuickConnect(QuickConnectUiState.Cancelled))
                }
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
            val parsed = parseServerAddress(server.address) ?: return
            val https = parsed.https ?: true
            dispatch(Msg.Protocol(https))
            dispatch(Msg.Host(parsed.host))
            dispatch(Msg.Port(parsed.port ?: defaultServerPort(https)))
            dispatch(Msg.BasePath(parsed.basePath))
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

        private fun startQuickConnect() {
            val form = state().form
            if (!form.canStartQuickConnect) {
                val message = if (!form.https && !form.httpRiskAccepted) {
                    "请先确认 HTTP 未加密连接风险"
                } else {
                    "请先填写有效的服务器地址、端口和基础路径"
                }
                dispatch(Msg.QuickConnect(QuickConnectUiState.Error(message)))
                return
            }
            stopQuickConnect(resetState = false, notifyGateway = true)
            val requestId = ++quickConnectRequestId
            val baseUrl = form.url
            val editingId = state().editingServerId
            val requestedName = sanitizeServerName(form.serverName)
            dispatch(Msg.QuickConnect(QuickConnectUiState.CheckingSupport))
            quickConnectJob = scope.launch {
                val started = quickConnectGateway.start(baseUrl)
                if (requestId != quickConnectRequestId) return@launch
                started.fold(
                    onSuccess = { result ->
                        when (result) {
                            is QuickConnectStartResult.Unsupported -> {
                                dispatch(Msg.QuickConnect(QuickConnectUiState.Unsupported(result.reason)))
                                quickConnectJob = null
                            }
                            is QuickConnectStartResult.AwaitingApproval -> {
                                val session = result.session
                                activeQuickConnect = baseUrl to session
                                dispatch(
                                    Msg.QuickConnect(
                                        QuickConnectUiState.AwaitingApproval(
                                            code = session.code,
                                            expiresAtEpochMs = session.expiresAtEpochMs,
                                        ),
                                    ),
                                )
                                pollQuickConnect(
                                    requestId = requestId,
                                    baseUrl = baseUrl,
                                    session = session,
                                    requestedName = requestedName,
                                    editingId = editingId,
                                )
                            }
                        }
                    },
                    onFailure = {
                        dispatch(
                            Msg.QuickConnect(
                                QuickConnectUiState.Error(
                                    it.toUserMessage("Quick Connect 启动失败"),
                                ),
                            ),
                        )
                        quickConnectJob = null
                    },
                )
            }
        }

        private suspend fun pollQuickConnect(
            requestId: Int,
            baseUrl: String,
            session: QuickConnectSession,
            requestedName: String,
            editingId: String?,
        ) {
            while (requestId == quickConnectRequestId) {
                val remainingMs = session.expiresAtEpochMs - nowEpochMs()
                if (remainingMs <= 0L) {
                    activeQuickConnect = null
                    quickConnectJob = null
                    dispatch(Msg.QuickConnect(QuickConnectUiState.Expired))
                    return
                }
                delay(minOf(QuickConnectPollIntervalMs, remainingMs))
                if (requestId != quickConnectRequestId) return
                val polled = quickConnectGateway.poll(baseUrl, session.id)
                if (requestId != quickConnectRequestId) return
                val result = polled.getOrElse {
                    activeQuickConnect = null
                    quickConnectJob = null
                    dispatch(
                        Msg.QuickConnect(
                            QuickConnectUiState.Error(it.toUserMessage("Quick Connect 检查失败")),
                        ),
                    )
                    return
                }
                when (result) {
                    QuickConnectPollResult.Pending -> Unit
                    QuickConnectPollResult.Expired -> {
                        activeQuickConnect = null
                        quickConnectJob = null
                        dispatch(Msg.QuickConnect(QuickConnectUiState.Expired))
                        return
                    }
                    is QuickConnectPollResult.Rejected -> {
                        activeQuickConnect = null
                        quickConnectJob = null
                        dispatch(Msg.QuickConnect(QuickConnectUiState.Error(result.reason)))
                        return
                    }
                    is QuickConnectPollResult.Authenticated -> {
                        activeQuickConnect = null
                        quickConnectJob = null
                        if (result.server.baseUrl.trimEnd('/') != baseUrl.trimEnd('/')) {
                            dispatch(
                                Msg.QuickConnect(
                                    QuickConnectUiState.Error("服务器返回的认证地址不一致，已拒绝保存"),
                                ),
                            )
                            return
                        }
                        persistAuthenticated(
                            authResult = result.server,
                            requestedName = requestedName,
                            editingId = editingId,
                        )
                        return
                    }
                }
            }
        }

        private fun persistAuthenticated(
            authResult: com.yfuse.core.data.AuthedServer,
            requestedName: String,
            editingId: String?,
        ) {
            val existing = editingId?.let { id -> state().servers.firstOrNull { it.id == id } }
            val savedServer = authResult.toSavedServer(
                serverName = requestedName.takeIf { it.isNotBlank() } ?: existing?.serverName,
            )
            val saved = if (editingId == null) {
                registry.addOrUpdate(savedServer)
                true
            } else {
                registry.replace(editingId, savedServer)
            }
            if (!saved) {
                dispatch(Msg.QuickConnect(QuickConnectUiState.Error("原服务器已不存在，请重新添加")))
                return
            }
            AppLog.info(
                category = "server.auth",
                event = "quick_connect_succeeded",
                message = "Server Quick Connect succeeded",
                attributes = mapOf("serverId" to savedServer.id),
            )
            cancelDialogJobs()
            dispatch(Msg.SubmitDone)
            publish(ServersLabel.ServerAdded)
        }

        private fun submit() {
            val form = state().form
            if (!form.canSubmit) return
            val editingId = state().editingServerId
            val existing = editingId?.let { id -> state().servers.firstOrNull { it.id == id } }
            val requestedName = sanitizeServerName(form.serverName)
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
                quickConnect = QuickConnectUiState.Idle,
                connectionEdited = false,
            )
            Msg.DialogClose -> copy(
                dialogVisible = false,
                form = LoginForm(),
                editingServerId = null,
                scanning = false,
                discovered = emptyList(),
                publicUsers = emptyList(),
                quickConnect = QuickConnectUiState.Idle,
                connectionEdited = false,
            )
            is Msg.EditOpen -> {
                // Reuse the add dialog in-place by prefilling the form from the saved
                // server. Password is deliberately left blank — the stored access token
                // can't be reversed to a password, and any host/account change requires
                // re-authenticating anyway. editingServerId tells submit() to treat the
                // result as a replacement rather than a brand-new server.
                val parsed = parseBaseUrl(msg.server.baseUrl)
                val https = parsed.https ?: true
                copy(
                    dialogVisible = true,
                    editingServerId = msg.server.id,
                    scanning = false,
                    discovered = emptyList(),
                    publicUsers = emptyList(),
                    quickConnect = QuickConnectUiState.Idle,
                    connectionEdited = false,
                    form = LoginForm(
                        serverName = msg.server.serverName,
                        https = https,
                        host = parsed.host,
                        port = parsed.port ?: defaultServerPort(https),
                        basePath = parsed.basePath,
                        username = msg.server.userName,
                        password = "",
                        httpRiskAccepted = !https,
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
                    httpRiskAccepted = false,
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
                    val explicitAbsoluteUrl = "://" in msg.v
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
                            basePath = if (explicitAbsoluteUrl || parsed.basePath.isNotEmpty()) {
                                parsed.basePath
                            } else {
                                form.basePath
                            },
                            httpRiskAccepted = if (parsed.https == false) {
                                false
                            } else {
                                form.httpRiskAccepted
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
            is Msg.BasePath -> copy(
                form = form.copy(basePath = msg.v, error = null),
                connectionEdited = true,
            )
            is Msg.Username -> copy(
                form = form.copy(username = msg.v, error = null),
                connectionEdited = true,
            )
            is Msg.Password -> copy(form = form.copy(password = msg.v, error = null))
            is Msg.HttpRiskAccepted -> copy(
                form = form.copy(httpRiskAccepted = msg.accepted, error = null),
            )
            is Msg.QuickConnect -> copy(quickConnect = msg.state)
            Msg.Submitting -> copy(form = form.copy(submitting = true, error = null))
            Msg.SubmitDone -> copy(
                dialogVisible = false,
                form = LoginForm(),
                editingServerId = null,
                scanning = false,
                discovered = emptyList(),
                publicUsers = emptyList(),
                quickConnect = QuickConnectUiState.Idle,
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

private const val QuickConnectPollIntervalMs = 2_000L

private fun sanitizeServerName(value: String): String = value
    .replace('\r', ' ')
    .replace('\n', ' ')
    .trim()
    .take(60)
