package com.yfuse.feature.servers

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlexCloudResource
import com.yfuse.core.data.PlexPinSession
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.dto.PublicUserDto
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.DiscoveredServer
import com.yfuse.core.network.LanDiscovery
import com.yfuse.core.network.LocalNetworkPermissionRequiredException
import com.yfuse.core.network.createLanDiscovery
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.network.validateEmbyServerEndpoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** 添加服务器 form: protocol segment + address + port, plus the credentials. */
data class LoginForm(
    /** Emby/Jellyfin share one login protocol; Plex uses a server-issued token. */
    val kind: MediaServerKind = MediaServerKind.Emby,
    /** Optional on first login; prefilled with the saved display name while editing. */
    val serverName: String = "",
    /** The prototype defaults the protocol segment to HTTPS. */
    val https: Boolean = true,
    val host: String = "",
    val port: String = "443",
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    /** Legacy persisted flag retained for backward-compatible server data. */
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
        get() =
            kind != MediaServerKind.Plex &&
                hasValidEndpoint &&
                !submitting

    private val hasValidEndpoint: Boolean
        get() =
            parseServerAddress(host)?.host?.isNotBlank() == true &&
                validServerPort(port) &&
                validateEmbyServerEndpoint(url, httpRiskAccepted).allowed

    val canSubmit: Boolean
        get() =
            hasValidEndpoint &&
                !submitting &&
                if (kind == MediaServerKind.Plex) password.isNotBlank() else username.isNotBlank()
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
    val authorityEnd =
        listOf(remainder.indexOf('/'), remainder.indexOf('?'), remainder.indexOf('#'))
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
        port =
            when {
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
    val path =
        remainder
            .substring(authorityEnd)
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
    val path =
        value
            .trim()
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
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

    data class AwaitingApproval(
        val code: String,
        val expiresAtEpochMs: Long,
    ) : QuickConnectUiState

    data class Unsupported(
        val reason: String,
    ) : QuickConnectUiState

    data object Expired : QuickConnectUiState

    data object Cancelled : QuickConnectUiState

    data class Error(
        val message: String,
    ) : QuickConnectUiState
}

data class PlexHomeUserOption(
    val id: String,
    val name: String,
    val pinProtected: Boolean,
    val admin: Boolean,
)

data class PlexServerOption(
    val id: String,
    val name: String,
    val owned: Boolean,
    val routeCount: Int,
)

sealed interface PlexAccountUiState {
    data object Idle : PlexAccountUiState
    data object Starting : PlexAccountUiState

    data class AwaitingAuthorization(
        val code: String,
        val authUrl: String,
        val expiresAtEpochMs: Long,
    ) : PlexAccountUiState

    data object LoadingAccount : PlexAccountUiState

    data class SelectingHomeUser(
        val users: List<PlexHomeUserOption>,
        val error: String? = null,
    ) : PlexAccountUiState

    data object LoadingServers : PlexAccountUiState

    data class SelectingServer(
        val servers: List<PlexServerOption>,
    ) : PlexAccountUiState

    data object Connecting : PlexAccountUiState
    data object Expired : PlexAccountUiState
    data object Cancelled : PlexAccountUiState

    data class Error(
        val message: String,
    ) : PlexAccountUiState
}

data class ServersState(
    val servers: List<SavedServer> = emptyList(),
    val defaultServerId: String? = null,
    val dialogVisible: Boolean = false,
    val form: LoginForm = LoginForm(),
    val scanning: Boolean = false,
    val scanError: String? = null,
    val discovered: List<DiscoveredServer> = emptyList(),
    val publicUsers: List<PublicUserDto> = emptyList(),
    val quickConnect: QuickConnectUiState = QuickConnectUiState.Idle,
    val plexAccount: PlexAccountUiState = PlexAccountUiState.Idle,
    val plexHomePin: String = "",
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
    data class EditServer(
        val server: SavedServer,
    ) : ServersIntent

    data class ServerNameChanged(
        val value: String,
    ) : ServersIntent

    data class ProviderChanged(
        val kind: MediaServerKind,
    ) : ServersIntent

    data class ProtocolChanged(
        val https: Boolean,
    ) : ServersIntent

    data class HostChanged(
        val value: String,
    ) : ServersIntent

    data class PortChanged(
        val value: String,
    ) : ServersIntent

    data class BasePathChanged(
        val value: String,
    ) : ServersIntent

    data class UsernameChanged(
        val value: String,
    ) : ServersIntent

    data class PasswordChanged(
        val value: String,
    ) : ServersIntent

    data class HttpRiskAcceptedChanged(
        val accepted: Boolean,
    ) : ServersIntent

    data object StartQuickConnect : ServersIntent

    data object CancelQuickConnect : ServersIntent

    data object StartPlexAccountSignIn : ServersIntent

    data object CancelPlexAccountSignIn : ServersIntent

    data class PlexHomePinChanged(
        val value: String,
    ) : ServersIntent

    data class SelectPlexHomeUser(
        val id: String,
    ) : ServersIntent

    data class SelectPlexCloudServer(
        val id: String,
    ) : ServersIntent

    data object Submit : ServersIntent

    data object Scan : ServersIntent

    data object LocalNetworkPermissionDenied : ServersIntent

    data class SelectDiscovered(
        val server: DiscoveredServer,
    ) : ServersIntent

    data class SelectPublicUser(
        val name: String,
    ) : ServersIntent

    data class SelectDefault(
        val id: String,
    ) : ServersIntent

    data class Remove(
        val id: String,
    ) : ServersIntent
}

sealed interface ServersLabel {
    /** A server was just added/logged in; the shell may jump to the library tab. */
    data object ServerAdded : ServersLabel
}

private sealed interface Action {
    data class Data(
        val servers: List<SavedServer>,
        val defaultId: String?,
    ) : Action
}

private sealed interface Msg {
    data class Data(
        val servers: List<SavedServer>,
        val defaultId: String?,
    ) : Msg

    data object DialogOpen : Msg

    data object DialogClose : Msg

    data class EditOpen(
        val server: SavedServer,
    ) : Msg

    data class ServerName(
        val v: String,
    ) : Msg

    data class Provider(
        val kind: MediaServerKind,
    ) : Msg

    data class Protocol(
        val https: Boolean,
    ) : Msg

    data class Host(
        val v: String,
    ) : Msg

    data class Port(
        val v: String,
    ) : Msg

    data class BasePath(
        val v: String,
    ) : Msg

    data class Username(
        val v: String,
    ) : Msg

    data class Password(
        val v: String,
    ) : Msg

    data class HttpRiskAccepted(
        val accepted: Boolean,
    ) : Msg

    data class QuickConnect(
        val state: QuickConnectUiState,
    ) : Msg

    data class PlexAccount(
        val state: PlexAccountUiState,
    ) : Msg

    data class PlexHomePin(
        val value: String,
    ) : Msg

    data object Submitting : Msg

    data object SubmitDone : Msg

    data class SubmitError(
        val m: String,
    ) : Msg

    data object ScanStarted : Msg

    data class ScanDone(
        val servers: List<DiscoveredServer>,
        val error: String? = null,
    ) : Msg

    data class PublicUsers(
        val users: List<PublicUserDto>,
    ) : Msg
}

class ServersStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val discovery: LanDiscovery = createLanDiscovery(),
    private val quickConnectGateway: QuickConnectGateway = UnsupportedQuickConnectGateway,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val onAuthenticated: (String) -> Unit = {},
) {
    fun create(): Store<ServersIntent, ServersState, ServersLabel> =
        storeFactory.create(
            name = "ServersStore",
            initialState = ServersState(),
            bootstrapper =
                coroutineBootstrapper<Action> {
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
        private var plexAccountJob: Job? = null
        private var activeQuickConnect: Pair<String, QuickConnectSession>? = null
        private var activePlexPin: PlexPinSession? = null
        private var plexAccountToken: String? = null
        private var plexOwnerToken: String? = null
        private var plexResources: Map<String, PlexCloudResource> = emptyMap()
        private var scanRequestId = 0
        private var publicUsersRequestId = 0
        private var quickConnectRequestId = 0
        private var plexAccountRequestId = 0

        private fun cancelDialogJobs() {
            scanRequestId++
            publicUsersRequestId++
            scanJob?.cancel()
            publicUsersJob?.cancel()
            stopQuickConnect(resetState = false, notifyGateway = true)
            stopPlexAccount(resetState = false)
            scanJob = null
            publicUsersJob = null
        }

        private fun stopPlexAccount(resetState: Boolean) {
            plexAccountRequestId++
            plexAccountJob?.cancel()
            plexAccountJob = null
            activePlexPin = null
            plexAccountToken = null
            plexOwnerToken = null
            plexResources = emptyMap()
            if (resetState) dispatch(Msg.PlexAccount(PlexAccountUiState.Idle))
        }

        private fun stopQuickConnect(
            resetState: Boolean,
            notifyGateway: Boolean,
        ) {
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

        override fun executeAction(action: Action) =
            when (action) {
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
                is ServersIntent.ProviderChanged -> {
                    stopQuickConnect(resetState = true, notifyGateway = true)
                    stopPlexAccount(resetState = true)
                    dispatch(Msg.Provider(intent.kind))
                }
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
                ServersIntent.StartPlexAccountSignIn -> startPlexAccountSignIn()
                ServersIntent.CancelPlexAccountSignIn -> {
                    stopPlexAccount(resetState = false)
                    dispatch(Msg.PlexAccount(PlexAccountUiState.Cancelled))
                }
                is ServersIntent.PlexHomePinChanged ->
                    dispatch(Msg.PlexHomePin(intent.value.filter(Char::isDigit).take(4)))
                is ServersIntent.SelectPlexHomeUser -> selectPlexHomeUser(intent.id)
                is ServersIntent.SelectPlexCloudServer -> selectPlexCloudServer(intent.id)
                ServersIntent.Submit -> submit()
                ServersIntent.Scan -> scan()
                ServersIntent.LocalNetworkPermissionDenied ->
                    dispatch(Msg.ScanDone(emptyList(), LocalNetworkPermissionRequiredException().message))
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
            scanJob =
                scope.launch {
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
                        }.onFailure {
                            AppLog.warning(
                                category = "server.discovery",
                                event = "scan_failed",
                                message = "Local server discovery failed",
                                throwable = it,
                            )
                        }
                    dispatch(
                        Msg.ScanDone(
                            servers = result.getOrDefault(emptyList()),
                            error = result.exceptionOrNull()?.toUserMessage("局域网扫描失败"),
                        ),
                    )
                    scanJob = null
                }
        }

        private fun selectDiscovered(server: DiscoveredServer) {
            publicUsersJob?.cancel()
            val requestId = ++publicUsersRequestId
            val parsed = parseServerAddress(server.address) ?: return
            val https = parsed.https ?: true
            dispatch(Msg.Provider(MediaServerKind.Emby))
            dispatch(Msg.Protocol(https))
            dispatch(Msg.Host(parsed.host))
            dispatch(Msg.Port(parsed.port ?: defaultServerPort(https)))
            dispatch(Msg.BasePath(parsed.basePath))
            dispatch(Msg.HttpRiskAccepted(https))
            dispatch(Msg.PublicUsers(emptyList()))
            // Discovery commonly returns HTTP. Populate the form, but do not transmit even a
            // public-users request until the user acknowledges this server's LAN risk.
            if (!validateEmbyServerEndpoint(server.address).allowed) return
            publicUsersJob =
                scope.launch {
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

        private fun startPlexAccountSignIn() {
            if (state().form.kind != MediaServerKind.Plex) return
            stopPlexAccount(resetState = false)
            val requestId = ++plexAccountRequestId
            dispatch(Msg.PlexAccount(PlexAccountUiState.Starting))
            plexAccountJob =
                scope.launch {
                    val session =
                        repo.startPlexCloudSignIn(nowEpochMs()).getOrElse {
                            if (requestId == plexAccountRequestId) {
                                dispatch(
                                    Msg.PlexAccount(
                                        PlexAccountUiState.Error(it.toUserMessage("Plex 登录启动失败")),
                                    ),
                                )
                            }
                            plexAccountJob = null
                            return@launch
                        }
                    if (requestId != plexAccountRequestId) return@launch
                    activePlexPin = session
                    dispatch(
                        Msg.PlexAccount(
                            PlexAccountUiState.AwaitingAuthorization(
                                code = session.code,
                                authUrl = session.authUrl,
                                expiresAtEpochMs = session.expiresAtEpochMs,
                            ),
                        ),
                    )
                    pollPlexAccount(requestId, session)
                }
        }

        private suspend fun pollPlexAccount(
            requestId: Int,
            session: PlexPinSession,
        ) {
            while (requestId == plexAccountRequestId) {
                val remainingMs = session.expiresAtEpochMs - nowEpochMs()
                if (remainingMs <= 0L) {
                    activePlexPin = null
                    plexAccountJob = null
                    dispatch(Msg.PlexAccount(PlexAccountUiState.Expired))
                    return
                }
                delay(minOf(PLEX_PIN_POLL_INTERVAL_MS, remainingMs))
                if (requestId != plexAccountRequestId) return
                val poll =
                    repo.pollPlexCloudSignIn(session, nowEpochMs()).getOrElse {
                        activePlexPin = null
                        plexAccountJob = null
                        dispatch(
                            Msg.PlexAccount(
                                PlexAccountUiState.Error(it.toUserMessage("Plex 授权检查失败")),
                            ),
                        )
                        return
                    }
                if (poll.expired) {
                    activePlexPin = null
                    plexAccountJob = null
                    dispatch(Msg.PlexAccount(PlexAccountUiState.Expired))
                    return
                }
                val token = poll.accessToken ?: continue
                activePlexPin = null
                dispatch(Msg.PlexAccount(PlexAccountUiState.LoadingAccount))
                loadPlexHome(requestId, token)
                return
            }
        }

        private suspend fun loadPlexHome(
            requestId: Int,
            accountToken: String,
        ) {
            plexAccountToken = accountToken
            if (plexOwnerToken == null) plexOwnerToken = accountToken
            val users = repo.plexHomeUsers(accountToken).getOrElse { emptyList() }
            if (requestId != plexAccountRequestId) return
            if (users.size > 1) {
                dispatch(
                    Msg.PlexAccount(
                        PlexAccountUiState.SelectingHomeUser(
                            users.map {
                                PlexHomeUserOption(
                                    id = it.id,
                                    name = it.name,
                                    pinProtected = it.pinProtected,
                                    admin = it.admin,
                                )
                            },
                        ),
                    ),
                )
                plexAccountJob = null
            } else {
                loadPlexResources(requestId, accountToken)
            }
        }

        private fun selectPlexHomeUser(userId: String) {
            val homeState = state().plexAccount as? PlexAccountUiState.SelectingHomeUser ?: return
            val user = homeState.users.firstOrNull { it.id == userId } ?: return
            val pin = state().plexHomePin
            if (user.pinProtected && pin.length != 4) {
                dispatch(Msg.PlexAccount(homeState.copy(error = "该 Plex Home 用户需要 4 位 PIN")))
                return
            }
            val accountToken = plexAccountToken ?: return
            val requestId = plexAccountRequestId
            dispatch(Msg.PlexAccount(PlexAccountUiState.LoadingServers))
            plexAccountJob =
                scope.launch {
                    val switched =
                        repo.switchPlexHomeUser(accountToken, userId, pin).getOrElse {
                            if (requestId == plexAccountRequestId) {
                                dispatch(Msg.PlexAccount(homeState.copy(error = it.toUserMessage("Plex Home 切换失败"))))
                            }
                            plexAccountJob = null
                            return@launch
                        }
                    if (requestId != plexAccountRequestId) return@launch
                    plexAccountToken = switched
                    loadPlexResources(requestId, switched)
                }
        }

        private suspend fun loadPlexResources(
            requestId: Int,
            accountToken: String,
        ) {
            dispatch(Msg.PlexAccount(PlexAccountUiState.LoadingServers))
            val resources =
                repo.plexCloudResources(accountToken).getOrElse {
                    if (requestId == plexAccountRequestId) {
                        dispatch(
                            Msg.PlexAccount(
                                PlexAccountUiState.Error(it.toUserMessage("读取 Plex 服务器失败")),
                            ),
                        )
                    }
                    plexAccountJob = null
                    return
                }
            if (requestId != plexAccountRequestId) return
            plexResources = resources.associateBy { it.id }
            if (resources.isEmpty()) {
                dispatch(Msg.PlexAccount(PlexAccountUiState.Error("此 Plex 账号没有可连接的媒体服务器")))
            } else {
                dispatch(
                    Msg.PlexAccount(
                        PlexAccountUiState.SelectingServer(
                            resources.map {
                                PlexServerOption(
                                    id = it.id,
                                    name = it.name,
                                    owned = it.owned,
                                    routeCount = it.connections.size,
                                )
                            },
                        ),
                    ),
                )
            }
            plexAccountJob = null
        }

        private fun selectPlexCloudServer(resourceId: String) {
            val resource = plexResources[resourceId] ?: return
            val accountToken = plexAccountToken ?: return
            val requestId = plexAccountRequestId
            val editingId = state().editingServerId
            val requestedName = sanitizeServerName(state().form.serverName)
            dispatch(Msg.PlexAccount(PlexAccountUiState.Connecting))
            plexAccountJob =
                scope.launch {
                    repo
                        .authenticatePlexCloudResource(
                            accountToken = accountToken,
                            resource = resource,
                            ownerAccountToken = plexOwnerToken ?: accountToken,
                        )
                        .onSuccess { authenticated ->
                            if (requestId != plexAccountRequestId) return@onSuccess
                            val existing = editingId?.let { registry.serverById(it) }
                            val savedServer =
                                authenticated.toSavedServer(
                                    serverName = requestedName.takeIf(String::isNotBlank) ?: existing?.serverName,
                                )
                            val saved =
                                if (editingId == null) {
                                    registry.addOrUpdate(savedServer)
                                    true
                                } else {
                                    registry.replace(editingId, savedServer)
                                }
                            if (!saved) {
                                dispatch(Msg.PlexAccount(PlexAccountUiState.Error("原服务器已不存在，请重新添加")))
                                return@onSuccess
                            }
                            AppLog.info(
                                category = "server.auth",
                                event = "plex_cloud_login_succeeded",
                                message = "Plex cloud account server login succeeded",
                                attributes = mapOf("serverId" to savedServer.id),
                            )
                            onAuthenticated(savedServer.id)
                            cancelDialogJobs()
                            dispatch(Msg.SubmitDone)
                            publish(ServersLabel.ServerAdded)
                        }.onFailure {
                            if (requestId == plexAccountRequestId) {
                                dispatch(
                                    Msg.PlexAccount(
                                        PlexAccountUiState.Error(it.toUserMessage("连接 Plex 服务器失败")),
                                    ),
                                )
                            }
                        }
                    plexAccountJob = null
                }
        }

        private fun startQuickConnect() {
            val form = state().form
            if (!form.canStartQuickConnect) {
                val endpoint = validateEmbyServerEndpoint(form.url, form.httpRiskAccepted)
                val message =
                    endpoint.message
                        ?: "请先填写有效的服务器地址、端口和基础路径"
                dispatch(Msg.QuickConnect(QuickConnectUiState.Error(message)))
                return
            }
            stopQuickConnect(resetState = false, notifyGateway = true)
            val requestId = ++quickConnectRequestId
            val baseUrl = form.url
            val editingId = state().editingServerId
            val requestedName = sanitizeServerName(form.serverName)
            dispatch(Msg.QuickConnect(QuickConnectUiState.CheckingSupport))
            quickConnectJob =
                scope.launch {
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
                delay(minOf(QUICK_CONNECT_POLL_INTERVAL_MS, remainingMs))
                if (requestId != quickConnectRequestId) return
                val polled = quickConnectGateway.poll(baseUrl, session.id)
                if (requestId != quickConnectRequestId) return
                val result =
                    polled.getOrElse {
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
            val savedServer =
                authResult.toSavedServer(
                    serverName = requestedName.takeIf { it.isNotBlank() } ?: existing?.serverName,
                    localCleartextConfirmed = state().form.httpRiskAccepted,
                )
            val saved =
                if (editingId == null) {
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
            onAuthenticated(savedServer.id)
            cancelDialogJobs()
            dispatch(Msg.SubmitDone)
            publish(ServersLabel.ServerAdded)
        }

        private fun submit() {
            val form = state().form
            if (!form.canSubmit) {
                val endpoint = validateEmbyServerEndpoint(form.url, form.httpRiskAccepted)
                if (!endpoint.allowed && endpoint.message != null) {
                    dispatch(Msg.SubmitError(endpoint.message))
                }
                return
            }
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
                repo
                    .authenticate(form.url, form.username.trim(), form.password, form.kind)
                    .onSuccess {
                        val authResult = it
                        // Preserve the user's chosen server name when editing — otherwise
                        // a fresh login would clobber it with whatever the server reports
                        // as the user's display name. New servers fall back to that name.
                        val savedServer =
                            authResult.toSavedServer(
                                serverName =
                                    requestedName.takeIf { it.isNotBlank() }
                                        ?: existing?.serverName,
                                localCleartextConfirmed = form.httpRiskAccepted,
                            )
                        val saved =
                            if (editingId == null) {
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
                        onAuthenticated(savedServer.id)
                        cancelDialogJobs()
                        dispatch(Msg.SubmitDone)
                        publish(ServersLabel.ServerAdded)
                    }.onFailure {
                        AppLog.warning(
                            category = "server.auth",
                            event = "login_failed",
                            message = "Server login failed",
                            throwable = it,
                            attributes =
                                mapOf(
                                    "scheme" to if (form.https) "https" else "http",
                                ),
                        )
                        dispatch(Msg.SubmitError(it.toUserMessage("登录失败")))
                    }
            }
        }
    }

    private object ReducerImpl : Reducer<ServersState, Msg> {
        override fun ServersState.reduce(msg: Msg): ServersState =
            when (msg) {
                is Msg.Data -> copy(servers = msg.servers, defaultServerId = msg.defaultId)
                Msg.DialogOpen ->
                    copy(
                        dialogVisible = true,
                        form = LoginForm(),
                        editingServerId = null,
                        scanning = false,
                        discovered = emptyList(),
                        publicUsers = emptyList(),
                        quickConnect = QuickConnectUiState.Idle,
                        plexAccount = PlexAccountUiState.Idle,
                        plexHomePin = "",
                        connectionEdited = false,
                    )
                Msg.DialogClose ->
                    copy(
                        dialogVisible = false,
                        form = LoginForm(),
                        editingServerId = null,
                        scanning = false,
                        discovered = emptyList(),
                        publicUsers = emptyList(),
                        quickConnect = QuickConnectUiState.Idle,
                        plexAccount = PlexAccountUiState.Idle,
                        plexHomePin = "",
                        connectionEdited = false,
                    )
                is Msg.EditOpen -> {
                    // Reuse the add dialog in-place by prefilling the form from the saved
                    // server. Password is deliberately left blank — the stored access token
                    // can't be reversed to a password, and any host/account change requires
                    // re-authenticating anyway. editingServerId tells submit() to treat the
                    // result as a replacement rather than a brand-new server.
                    // The identity address, not whichever route is live: editing the connection
                    // re-derives the server's id, and a failover to a backup must not turn into
                    // a permanent change of which machine this entry stands for.
                    val parsed = parseBaseUrl(msg.server.primaryUrl)
                    val https = parsed.https ?: true
                    copy(
                        dialogVisible = true,
                        editingServerId = msg.server.id,
                        scanning = false,
                        discovered = emptyList(),
                        publicUsers = emptyList(),
                        quickConnect = QuickConnectUiState.Idle,
                        plexAccount = PlexAccountUiState.Idle,
                        plexHomePin = "",
                        connectionEdited = false,
                        form =
                            LoginForm(
                                kind = msg.server.kind,
                                serverName = msg.server.serverName,
                                https = https,
                                host = parsed.host,
                                port = parsed.port ?: defaultServerPort(https),
                                basePath = parsed.basePath,
                                username = msg.server.userName,
                                password = "",
                                httpRiskAccepted = msg.server.localCleartextConfirmed,
                            ),
                    )
                }
                is Msg.ServerName ->
                    copy(
                        form = form.copy(serverName = msg.v.take(60), error = null),
                    )
                is Msg.Provider ->
                    copy(
                        form =
                            form.copy(
                                kind = msg.kind,
                                username = if (msg.kind == MediaServerKind.Plex) "" else form.username,
                                password = "",
                                port =
                                    when {
                                        msg.kind == MediaServerKind.Plex &&
                                            form.port in setOf("443", "8096") -> "32400"
                                        form.kind == MediaServerKind.Plex &&
                                            msg.kind != MediaServerKind.Plex &&
                                            form.port == "32400" -> defaultServerPort(form.https)
                                        else -> form.port
                                    },
                                error = null,
                            ),
                        publicUsers = emptyList(),
                        connectionEdited = true,
                    )
                is Msg.Protocol ->
                    copy(
                        form =
                            form.copy(
                                https = msg.https,
                                port =
                                    if (form.kind == MediaServerKind.Plex) {
                                        "32400"
                                    } else {
                                        defaultServerPort(msg.https)
                                    },
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
                            form =
                                form.copy(
                                    https = resolvedHttps,
                                    host = parsed.host,
                                    port =
                                        parsed.port
                                            ?: if (parsed.https != null) {
                                                defaultServerPort(resolvedHttps)
                                            } else {
                                                form.port
                                            },
                                    basePath =
                                        if (explicitAbsoluteUrl || parsed.basePath.isNotEmpty()) {
                                            parsed.basePath
                                        } else {
                                            form.basePath
                                        },
                                    httpRiskAccepted =
                                        if (parsed.https == false) {
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
                is Msg.Port ->
                    copy(
                        form = form.copy(port = msg.v, error = null),
                        connectionEdited = true,
                    )
                is Msg.BasePath ->
                    copy(
                        form = form.copy(basePath = msg.v, error = null),
                        connectionEdited = true,
                    )
                is Msg.Username ->
                    copy(
                        form = form.copy(username = msg.v, error = null),
                        connectionEdited = true,
                    )
                is Msg.Password -> copy(form = form.copy(password = msg.v, error = null))
                is Msg.HttpRiskAccepted ->
                    copy(
                        form = form.copy(httpRiskAccepted = msg.accepted, error = null),
                    )
                is Msg.QuickConnect -> copy(quickConnect = msg.state)
                is Msg.PlexAccount -> copy(plexAccount = msg.state)
                is Msg.PlexHomePin -> copy(plexHomePin = msg.value)
                Msg.Submitting -> copy(form = form.copy(submitting = true, error = null))
                Msg.SubmitDone ->
                    copy(
                        dialogVisible = false,
                        form = LoginForm(),
                        editingServerId = null,
                        scanning = false,
                        discovered = emptyList(),
                        publicUsers = emptyList(),
                        quickConnect = QuickConnectUiState.Idle,
                        plexAccount = PlexAccountUiState.Idle,
                        plexHomePin = "",
                        connectionEdited = false,
                    )
                is Msg.SubmitError -> copy(form = form.copy(submitting = false, error = msg.m))
                Msg.ScanStarted -> copy(scanning = true, discovered = emptyList(), scanError = null)
                is Msg.ScanDone ->
                    if (dialogVisible) {
                        copy(scanning = false, discovered = msg.servers, scanError = msg.error)
                    } else {
                        this
                    }
                is Msg.PublicUsers -> if (dialogVisible) copy(publicUsers = msg.users) else this
            }
    }
}

private const val QUICK_CONNECT_POLL_INTERVAL_MS = 2_000L
private const val PLEX_PIN_POLL_INTERVAL_MS = 2_000L

private fun sanitizeServerName(value: String): String =
    value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
        .take(60)
