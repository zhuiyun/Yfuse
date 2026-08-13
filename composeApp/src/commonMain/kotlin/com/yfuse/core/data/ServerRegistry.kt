package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.model.ServersData
import com.yfuse.core.model.normalizedRoutes
import com.yfuse.core.network.validateEmbyServerEndpoint
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.ServerMigrationCrypto
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.toBase64Url
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Enough history for stale routes and queued work without letting repeated address edits grow
 * the persisted registry forever. Iteration order is oldest to newest among the retained ids.
 */
internal const val MAX_SERVER_PREVIOUS_IDS = 8

@Serializable
private data class PersistedServerRegistry(
    @SerialName("v") val version: Int,
    @SerialName("d") val defaultServerId: String? = null,
    @SerialName("s") val servers: List<PersistedServer> = emptyList(),
)

/**
 * Deliberately contains only non-secret metadata. [secretRef] is random and carries no token.
 *
 * The route and icon fields were added after v2 shipped and all default, so a registry written
 * by an older build still decodes and a downgrade only loses the additions rather than the
 * session.
 */
@Serializable
private data class PersistedServer(
    @SerialName("i") val id: String,
    @SerialName("b") val baseUrl: String,
    @SerialName("n") val serverName: String,
    @SerialName("u") val userId: String,
    @SerialName("a") val userName: String,
    @SerialName("p") val previousIds: Set<String> = emptySet(),
    @SerialName("r") val secretRef: String,
    @SerialName("rt") val routes: List<ServerRoute> = emptyList(),
    @SerialName("ar") val activeRouteId: String? = null,
    @SerialName("ie") val iconEmoji: String? = null,
    @SerialName("it") val iconTint: Long? = null,
    @SerialName("lc") val localCleartextConfirmed: Boolean = false,
)

@Serializable
private data class PortableServerBackup(
    @SerialName("v") val version: Int = 2,
    @SerialName("d") val defaultServerId: String? = null,
    @SerialName("s") val servers: List<PortableServer> = emptyList(),
)

@Serializable
private data class PortableServer(
    @SerialName("b") val baseUrl: String,
    @SerialName("n") val serverName: String,
    @SerialName("u") val userId: String,
    @SerialName("a") val userName: String,
    @SerialName("t") val accessToken: String,
    // Optional so a v2 package written before multi-route still imports.
    @SerialName("rt") val routes: List<ServerRoute> = emptyList(),
    @SerialName("ie") val iconEmoji: String? = null,
    @SerialName("it") val iconTint: Long? = null,
)

private data class LoadedRegistry(
    val data: ServersData,
    val secretRefs: Map<String, String>,
)

private data class SecretWrite(
    val ref: String,
    val newToken: String,
    val previousToken: String?,
)

/**
 * Single source of truth for saved Emby sessions.
 *
 * Ordinary settings contain only [PersistedServerRegistry]. Bearer tokens are independently
 * encrypted by [secureStore], whose master key must be non-exportable platform-keystore material.
 * Missing or unauthenticatable secrets are never replaced with a plaintext fallback: that saved
 * session is removed and the user must log in again.
 */
class ServerRegistry(
    private val settings: Settings,
    private val secureStore: SecureStore,
    private val crypto: VaultCrypto = VaultCrypto(),
    /** Unit fixtures may opt into direct local-HTTP construction; production never does. */
    private val allowUnconfirmedLocalForTests: Boolean = false,
) {
    private companion object {
        const val KEY = "servers.data"
        const val PERSISTED_VERSION = 2
        const val PORTABLE_BACKUP_VERSION = 2
        const val MAX_SERVERS = 100
        const val MAX_TOKEN_CHARS = 4_096
        const val SECRET_KEY_PREFIX = "emby-token:"
        val VALID_SECRET_REF = Regex("[A-Za-z0-9_-]{43}")
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    private val migrationCrypto = ServerMigrationCrypto(crypto)
    private val loaded = load()
    private var secretRefs: Map<String, String> = loaded.secretRefs
    private val _data = MutableStateFlow(loaded.data)
    val data: StateFlow<ServersData> = _data.asStateFlow()

    val defaultServer: SavedServer? get() = _data.value.defaultServer

    fun serverById(id: String): SavedServer? =
        _data.value.servers.firstOrNull { it.id == id }
            ?: _data.value.servers.firstOrNull { id in it.previousIds }

    /** Adds a server (or updates it if the same id already exists). First one becomes default. */
    fun addOrUpdate(server: SavedServer) {
        val current = _data.value
        val existing = current.servers.firstOrNull { it.id == server.id }
        val replacing = existing != null
        val normalized =
            server
                .copy(
                    previousIds =
                        recentPreviousIds(
                            server.id,
                            server.previousIds,
                            existing?.previousIds.orEmpty(),
                        ),
                ).carryingUserSettingsFrom(existing)
                .requiringAllowedTransport("服务器", allowUnconfirmedLocalForTests)
        val servers =
            current.servers
                .filterNot { it.id == server.id }
                .map { it.copy(previousIds = it.previousIds - server.id) } + normalized
        val defaultId = current.defaultServerId ?: server.id
        commit(current.copy(servers = servers, defaultServerId = defaultId))
        AppLog.info(
            category = "server.registry",
            event = if (replacing) "server_updated" else "server_added",
            message = "Saved server registry changed",
            attributes =
                mapOf(
                    "serverId" to server.id,
                    "serverCount" to servers.size.toString(),
                ),
        )
    }

    fun setDefault(id: String) {
        if (_data.value.servers.any { it.id == id }) {
            commit(_data.value.copy(defaultServerId = id))
            AppLog.info(
                category = "server.registry",
                event = "default_changed",
                message = "Default server changed",
                attributes = mapOf("serverId" to id),
            )
        } else {
            AppLog.warning(
                category = "server.registry",
                event = "default_missing",
                message = "Ignored request to select an unknown server",
            )
        }
    }

    /** Updates the user-visible server name without touching its login session or identity. */
    fun rename(
        id: String,
        name: String,
    ): Boolean {
        val normalized =
            name
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim()
                .take(60)
        if (normalized.isBlank()) return false
        val current = _data.value
        val existing = current.servers.firstOrNull { it.id == id } ?: return false
        if (existing.serverName == normalized) return true
        commit(
            current.copy(
                servers =
                    current.servers.map {
                        if (it.id == id) it.copy(serverName = normalized) else it
                    },
            ),
        )
        AppLog.info(
            category = "server.registry",
            event = "server_renamed",
            message = "Saved server display name changed",
            attributes = mapOf("serverId" to id),
        )
        return true
    }

    /**
     * Replaces a server's route list.
     *
     * The primary route is the address the session was authenticated against, so it is taken
     * from the saved server rather than from [routes]: a caller may reorder or rename, but
     * cannot repoint the identity here — that is [replace]'s job, and it re-authenticates.
     */
    fun setRoutes(
        id: String,
        routes: List<ServerRoute>,
        localCleartextConfirmed: Boolean = false,
    ): Boolean {
        val current = _data.value
        val existing = current.servers.firstOrNull { it.id == id } ?: return false
        val cleartextConfirmed = existing.localCleartextConfirmed || localCleartextConfirmed
        val primary =
            ServerRoute(
                id = ServerRoute.PRIMARY_ID,
                name =
                    routes
                        .firstOrNull { it.id == ServerRoute.PRIMARY_ID }
                        ?.let { ServerRoute.sanitizeName(it.name) }
                        ?: ServerRoute.PRIMARY_NAME,
                url = existing.primaryUrl,
            )
        val normalized =
            (listOf(primary) + routes.filterNot { it.id == ServerRoute.PRIMARY_ID })
                .normalizedRoutes()
        if (normalized.any {
                !validateEmbyServerEndpoint(
                    it.url,
                    localCleartextConfirmed = cleartextConfirmed,
                ).allowed
            }
        ) {
            return false
        }
        if (normalized.isEmpty()) return false
        val updated =
            existing
                .copy(
                    routes = normalized,
                    activeRouteId = existing.activeRouteId,
                    localCleartextConfirmed = cleartextConfirmed,
                ).withNormalizedRoutes()
        if (updated == existing) return true
        commit(
            current.copy(
                servers = current.servers.map { if (it.id == id) updated else it },
            ),
        )
        AppLog.info(
            category = "server.registry",
            event = "server_routes_changed",
            message = "Saved server route list changed",
            attributes =
                mapOf(
                    "serverId" to id,
                    "routeCount" to normalized.size.toString(),
                ),
        )
        return true
    }

    /**
     * Points a server at one of its routes. Identity, session, and caches are untouched — only
     * the address subsequent requests are sent to changes.
     */
    fun activateRoute(
        id: String,
        routeId: String,
    ): Boolean {
        val current = _data.value
        val existing = current.servers.firstOrNull { it.id == id } ?: return false
        val route = existing.effectiveRoutes.firstOrNull { it.id == routeId } ?: return false
        if (!validateEmbyServerEndpoint(
                route.url,
                localCleartextConfirmed = existing.localCleartextConfirmed,
            ).allowed
        ) {
            return false
        }
        if (existing.activeRoute.id == route.id && existing.baseUrl == route.url) return true
        commit(
            current.copy(
                servers =
                    current.servers.map {
                        if (it.id == id) it.activating(route).withNormalizedRoutes() else it
                    },
            ),
        )
        AppLog.info(
            category = "server.registry",
            event = "server_route_activated",
            message = "Saved server switched to another route",
            attributes = mapOf("serverId" to id, "routeId" to route.id),
        )
        return true
    }

    /** Sets the card's emoji and tint. Both are cosmetic and neither affects identity. */
    fun setIcon(
        id: String,
        emoji: String?,
        tint: Long?,
    ): Boolean {
        val current = _data.value
        val existing = current.servers.firstOrNull { it.id == id } ?: return false
        val normalizedEmoji = sanitizeIconEmoji(emoji)
        if (existing.iconEmoji == normalizedEmoji && existing.iconTint == tint) return true
        commit(
            current.copy(
                servers =
                    current.servers.map {
                        if (it.id == id) it.copy(iconEmoji = normalizedEmoji, iconTint = tint) else it
                    },
            ),
        )
        AppLog.info(
            category = "server.registry",
            event = "server_icon_changed",
            message = "Saved server icon changed",
            attributes = mapOf("serverId" to id),
        )
        return true
    }

    /** Atomically replaces an edited server while preserving its list position and default. */
    fun replace(
        id: String,
        server: SavedServer,
    ): Boolean {
        val current = _data.value
        val oldIndex = current.servers.indexOfFirst { it.id == id }
        if (oldIndex < 0) return false
        val existing = current.servers[oldIndex]
        val colliding = current.servers.firstOrNull { it.id == server.id }
        val replacement =
            server
                .copy(
                    previousIds =
                        recentPreviousIds(
                            server.id,
                            server.previousIds,
                            existing.previousIds,
                            colliding?.previousIds.orEmpty(),
                            listOf(id),
                        ),
                ).carryingUserSettingsFrom(existing)
                .requiringAllowedTransport("服务器", allowUnconfirmedLocalForTests)
        val remaining =
            current.servers
                .filterNot { it.id == id || it.id == server.id }
                .map { it.copy(previousIds = it.previousIds - server.id) }
        val servers =
            remaining.toMutableList().apply {
                add(oldIndex.coerceAtMost(size), replacement)
            }
        val defaultId =
            when (current.defaultServerId) {
                id, server.id -> server.id
                else -> current.defaultServerId
            }
        commit(current.copy(servers = servers, defaultServerId = defaultId))
        AppLog.info(
            category = "server.registry",
            event = "server_replaced",
            message = "Saved server connection changed",
            attributes =
                mapOf(
                    "previousServerId" to id,
                    "serverId" to server.id,
                ),
        )
        return true
    }

    fun remove(id: String) {
        val current = _data.value
        val servers =
            current.servers
                .filterNot { it.id == id }
                .map { it.copy(previousIds = it.previousIds - id) }
        val defaultId = if (current.defaultServerId == id) servers.firstOrNull()?.id else current.defaultServerId
        commit(current.copy(servers = servers, defaultServerId = defaultId))
        AppLog.info(
            category = "server.registry",
            event = "server_removed",
            message = "Saved server removed",
            attributes =
                mapOf(
                    "serverId" to id,
                    "serverCount" to servers.size.toString(),
                ),
        )
    }

    /** Replaces the local registry with an already authenticated account-sync snapshot. */
    fun replaceFromSync(snapshot: ServersData): Result<Int> =
        runCatching {
            require(snapshot.servers.size <= MAX_SERVERS) { "同步的服务器数量过多" }
            val localServers = _data.value.servers
            val normalized =
                snapshot.servers.map { server ->
                    val normalizedPrimary = server.primaryUrl.trim().trimEnd('/')
                    val deviceConfirmation =
                        localServers
                            .firstOrNull {
                                it.primaryUrl == normalizedPrimary && it.userId == server.userId.trim()
                            }?.localCleartextConfirmed == true
                    normalizeImportedServer(
                        baseUrl = server.primaryUrl,
                        serverName = server.serverName,
                        userId = server.userId,
                        userName = server.userName,
                        accessToken = server.accessToken,
                        previousIds = server.previousIds,
                        invalidMessagePrefix = "同步的",
                        routes = server.routes,
                        iconEmoji = server.iconEmoji,
                        iconTint = server.iconTint,
                        localCleartextConfirmed = deviceConfirmation,
                    )
                }
            require(normalized.map { it.id }.distinct().size == normalized.size) {
                "同步数据中包含重复服务器"
            }
            val requestedDefault = snapshot.defaultServerId
            val defaultId =
                requestedDefault?.let { oldId ->
                    snapshot.servers
                        .indexOfFirst { it.id == oldId }
                        .takeIf { it >= 0 }
                        ?.let(normalized::get)
                        ?.id
                } ?: normalized.firstOrNull()?.id
            commit(ServersData(normalized, defaultId))
            normalized.size
        }

    /** Creates an AES-256-GCM package; no API returns the credential-bearing plaintext backup. */
    fun exportProtectedBackup(
        passphrase: CharArray,
        createdAtEpochSeconds: Long,
        ttlSeconds: Long = ServerMigrationCrypto.DEFAULT_TTL_SECONDS,
    ): Result<String> =
        runCatching {
            val current = _data.value
            require(current.servers.isNotEmpty()) { "暂无可迁移的服务器" }
            require(ttlSeconds in 60..ServerMigrationCrypto.MAX_TTL_SECONDS) { "迁移包有效期无效" }
            require(createdAtEpochSeconds <= Long.MAX_VALUE - ttlSeconds) { "迁移包时间无效" }
            val plaintext =
                json
                    .encodeToString(
                        PortableServerBackup.serializer(),
                        PortableServerBackup(
                            defaultServerId = current.defaultServerId,
                            servers =
                                current.servers.map {
                                    PortableServer(
                                        // The identity address, not the active one: a package restored on
                                        // another device must rebuild the same server ids, and the device it
                                        // was exported from may have been sitting on a backup route.
                                        baseUrl = it.primaryUrl,
                                        serverName = it.serverName,
                                        userId = it.userId,
                                        userName = it.userName,
                                        accessToken = it.accessToken,
                                        routes = it.routes,
                                        iconEmoji = it.iconEmoji,
                                        iconTint = it.iconTint,
                                    )
                                },
                        ),
                    ).encodeToByteArray()
            try {
                migrationCrypto.protect(
                    plaintext = plaintext,
                    passphrase = passphrase,
                    createdAtEpochSeconds = createdAtEpochSeconds,
                    expiresAtEpochSeconds = createdAtEpochSeconds + ttlSeconds,
                )
            } finally {
                plaintext.fill(0)
            }
        }.onSuccess {
            AppLog.info(
                category = "server.migration",
                event = "protected_backup_created",
                message = "Passphrase-protected server backup created",
                attributes =
                    mapOf(
                        "serverCount" to
                            _data.value.servers.size
                                .toString(),
                    ),
            )
        }.onFailure {
            AppLog.warning(
                category = "server.migration",
                event = "protected_backup_create_failed",
                message = "Passphrase-protected server backup could not be created",
                throwable = it,
            )
        }

    /** Decrypts, validates, and merges a v2 package. Plaintext/v1 packages are rejected. */
    fun importProtectedBackup(
        payload: String,
        passphrase: CharArray,
        nowEpochSeconds: Long,
    ): Result<Int> =
        runCatching {
            val plaintext = migrationCrypto.unprotect(payload, passphrase, nowEpochSeconds)
            try {
                val backup =
                    try {
                        json.decodeFromString(PortableServerBackup.serializer(), plaintext.decodeToString())
                    } catch (_: Exception) {
                        // Do not retain the parser exception: malformed plaintext may be echoed in its
                        // message and later diagnostics must never acquire a bearer-token fragment.
                        throw IllegalArgumentException("受保护迁移包中的服务器数据已损坏")
                    }
                require(backup.version == PORTABLE_BACKUP_VERSION) { "不支持的服务器数据版本" }
                require(backup.servers.isNotEmpty()) { "迁移包中没有服务器" }
                require(backup.servers.size <= MAX_SERVERS) { "迁移包中的服务器数量过多" }
                val current = _data.value
                val imported =
                    backup.servers.map { portable ->
                        val id = SavedServer.idOf(portable.baseUrl.trim().trimEnd('/'), portable.userId.trim())
                        normalizeImportedServer(
                            baseUrl = portable.baseUrl,
                            serverName = portable.serverName,
                            userId = portable.userId,
                            userName = portable.userName,
                            accessToken = portable.accessToken,
                            previousIds =
                                current.servers
                                    .firstOrNull { it.id == id }
                                    ?.previousIds
                                    .orEmpty(),
                            invalidMessagePrefix = "迁移包中的",
                            routes = portable.routes,
                            iconEmoji = portable.iconEmoji,
                            iconTint = portable.iconTint,
                        )
                    }
                require(imported.map { it.id }.distinct().size == imported.size) {
                    "迁移包中包含重复服务器"
                }
                val ids = imported.mapTo(hashSetOf()) { it.id }
                val merged =
                    current.servers
                        .filterNot { it.id in ids }
                        .map { it.copy(previousIds = it.previousIds - ids) } + imported
                val importedDefault =
                    backup.defaultServerId?.let { oldId ->
                        backup.servers
                            .firstOrNull {
                                SavedServer.idOf(it.baseUrl.trim().trimEnd('/'), it.userId.trim()) == oldId
                            }?.let { SavedServer.idOf(it.baseUrl.trim().trimEnd('/'), it.userId.trim()) }
                    }
                commit(
                    ServersData(
                        servers = merged,
                        defaultServerId = current.defaultServerId ?: importedDefault ?: imported.first().id,
                    ),
                )
                imported.size
            } finally {
                plaintext.fill(0)
            }
        }.onSuccess {
            AppLog.info(
                category = "server.migration",
                event = "protected_backup_imported",
                message = "Passphrase-protected server backup imported",
                attributes = mapOf("serverCount" to it.toString()),
            )
        }.onFailure {
            AppLog.warning(
                category = "server.migration",
                event = "protected_backup_import_failed",
                message = "Passphrase-protected server backup import failed",
                throwable = it,
            )
        }

    private fun commit(data: ServersData) {
        data.servers.forEach {
            it.requiringAllowedTransport("服务器", allowUnconfirmedLocalForTests)
        }
        val normalized = data.withBoundedPreviousIds()
        require(normalized.servers.size <= MAX_SERVERS) { "服务器数量过多" }
        require(
            normalized.servers
                .map { it.id }
                .distinct()
                .size == normalized.servers.size,
        ) {
            "服务器列表包含重复项"
        }
        normalized.servers.forEach { requireValidToken(it.accessToken) }

        val oldData = _data.value
        val oldRefs = secretRefs
        val newRefs = assignSecretRefs(normalized, oldRefs)
        val oldByRef =
            oldData.servers
                .mapNotNull { server ->
                    oldRefs[server.id]?.let { it to server }
                }.toMap()
        val writes =
            normalized.servers.mapNotNull { server ->
                val ref = requireNotNull(newRefs[server.id])
                val previous = oldByRef[ref]?.accessToken
                if (previous == server.accessToken) null else SecretWrite(ref, server.accessToken, previous)
            }

        try {
            writes.forEach { writeSecret(it.ref, it.newToken) }
            persistMetadata(normalized, newRefs)
        } catch (error: Exception) {
            rollbackSecretWrites(writes)
            throw error
        }

        secretRefs = newRefs
        _data.value = normalized
        val orphanedRefs = oldRefs.values.toSet() - newRefs.values.toSet()
        orphanedRefs.forEach(::removeSecretBestEffort)
        clearOrphanedLibraryCaches(normalized)
    }

    private fun load(): LoadedRegistry {
        val raw = settings.getStringOrNull(KEY) ?: return LoadedRegistry(ServersData(), emptyMap())
        val persisted =
            runCatching {
                json.decodeFromString(PersistedServerRegistry.serializer(), raw).also {
                    require(it.version == PERSISTED_VERSION) { "Unsupported persisted registry version" }
                }
            }.getOrNull()
        if (persisted != null) return hydratePersisted(persisted)

        // Legacy ServersData stored bearer tokens directly. This is the only plaintext read path,
        // and it is immediately replaced by token-free metadata or purged on any migration failure.
        return runCatching {
            val legacy = json.decodeFromString(ServersData.serializer(), raw).withBoundedPreviousIds()
            migrateLegacyRegistry(legacy)
        }.onFailure {
            AppLog.error(
                category = "server.registry",
                event = "legacy_secret_migration_failed",
                message = "Legacy plaintext sessions were purged; users must log in again",
            )
            purgeOrdinaryRegistryBestEffort()
        }.getOrDefault(LoadedRegistry(ServersData(), emptyMap()))
    }

    private fun migrateLegacyRegistry(legacy: ServersData): LoadedRegistry {
        require(legacy.servers.size <= MAX_SERVERS) { "Legacy registry contains too many servers" }
        require(
            legacy.servers
                .map { it.id }
                .distinct()
                .size == legacy.servers.size,
        ) {
            "Legacy registry contains duplicate servers"
        }
        legacy.servers.forEach { requireValidToken(it.accessToken) }
        // The legacy plaintext schema pre-dates device-local cleartext acknowledgement, so it
        // cannot prove consent for HTTP. Migrate only HTTPS sessions; HTTP users sign in again.
        val safeLegacy =
            legacy
                .copy(
                    servers =
                        legacy.servers.mapNotNull { server ->
                            if (!validateEmbyServerEndpoint(server.primaryUrl).allowed) {
                                null
                            } else {
                                val safeRoutes =
                                    server.effectiveRoutes.filter { route ->
                                        validateEmbyServerEndpoint(route.url).allowed
                                    }
                                if (server.routes.isEmpty()) {
                                    server.copy(localCleartextConfirmed = false)
                                } else {
                                    val primary =
                                        safeRoutes.firstOrNull {
                                            it.id == ServerRoute.PRIMARY_ID
                                        } ?: return@mapNotNull null
                                    server
                                        .copy(
                                            routes =
                                                listOf(primary) +
                                                    safeRoutes.filterNot {
                                                        it.id == ServerRoute.PRIMARY_ID
                                                    },
                                            activeRouteId =
                                                server.activeRouteId?.takeIf { activeId ->
                                                    safeRoutes.any { it.id == activeId }
                                                } ?: ServerRoute.PRIMARY_ID,
                                            localCleartextConfirmed = false,
                                        ).withNormalizedRoutes()
                                }
                            }
                        },
                ).let { data ->
                    data.copy(
                        defaultServerId =
                            data.defaultServerId?.takeIf { id ->
                                data.servers.any { it.id == id }
                            } ?: data.servers.firstOrNull()?.id,
                    )
                }
        val droppedLegacyIds =
            legacy.servers.mapTo(mutableSetOf()) { it.id } -
                safeLegacy.servers.mapTo(mutableSetOf()) { it.id }
        val refs = assignSecretRefs(safeLegacy, emptyMap())
        // Purge the credential-bearing JSON before touching the keystore. The decoded legacy
        // value is already in memory, so this ordering closes the process-crash window: a crash
        // from this point onward leaves either an empty v2 registry or complete v2 metadata,
        // never the old plaintext token document.
        purgeLegacyPlaintextOrThrow()
        val written = mutableListOf<String>()
        try {
            safeLegacy.servers.forEach { server ->
                val ref = requireNotNull(refs[server.id])
                writeSecret(ref, server.accessToken)
                written += ref
            }
            persistMetadata(safeLegacy, refs)
        } catch (error: Exception) {
            written.forEach(::removeSecretBestEffort)
            throw error
        }
        droppedLegacyIds.forEach { droppedId ->
            AppLog.warning(
                category = "server.registry",
                event = "legacy_cleartext_session_purged",
                message = "An unconfirmed legacy HTTP session was removed; login is required",
                attributes = mapOf("serverId" to droppedId),
            )
        }
        clearOrphanedLibraryCaches(safeLegacy)
        AppLog.info(
            category = "server.registry",
            event = "legacy_secret_migration_complete",
            message = "Legacy plaintext sessions moved to platform secure storage",
            attributes = mapOf("serverCount" to safeLegacy.servers.size.toString()),
        )
        return LoadedRegistry(safeLegacy, refs)
    }

    private fun hydratePersisted(persisted: PersistedServerRegistry): LoadedRegistry {
        require(persisted.servers.size <= MAX_SERVERS) { "Stored registry contains too many servers" }
        val servers = mutableListOf<SavedServer>()
        val refs = linkedMapOf<String, String>()
        val seenIds = hashSetOf<String>()
        val seenRefs = hashSetOf<String>()
        var changed = false

        persisted.servers.forEach { stored ->
            val result =
                runCatching {
                    require(stored.id.isNotBlank() && seenIds.add(stored.id)) { "Duplicate or blank server id" }
                    require(VALID_SECRET_REF.matches(stored.secretRef) && seenRefs.add(stored.secretRef)) {
                        "Invalid or duplicate secret reference"
                    }
                    val tokenBytes =
                        secureStore.get(secretKey(stored.secretRef))
                            ?: error("Saved session secret is missing")
                    val token =
                        try {
                            tokenBytes.decodeToString()
                        } finally {
                            tokenBytes.fill(0)
                        }
                    requireValidToken(token)
                    val raw =
                        SavedServer(
                            id = stored.id,
                            baseUrl = stored.baseUrl,
                            serverName = stored.serverName,
                            userId = stored.userId,
                            userName = stored.userName,
                            accessToken = token,
                            previousIds = recentPreviousIds(stored.id, stored.previousIds),
                            routes = stored.routes,
                            activeRouteId = stored.activeRouteId,
                            iconEmoji = sanitizeIconEmoji(stored.iconEmoji),
                            iconTint = stored.iconTint,
                            localCleartextConfirmed = stored.localCleartextConfirmed,
                        ).withNormalizedRoutes()
                    val primaryValidation =
                        validateEmbyServerEndpoint(
                            raw.primaryUrl,
                            raw.localCleartextConfirmed,
                        )
                    require(primaryValidation.allowed) {
                        primaryValidation.message ?: "Saved server transport is not allowed"
                    }
                    val allowedRoutes =
                        raw.effectiveRoutes.filter { route ->
                            validateEmbyServerEndpoint(
                                route.url,
                                raw.localCleartextConfirmed,
                            ).allowed
                        }
                    require(allowedRoutes.isNotEmpty()) { "Saved server has no allowed route" }
                    if (raw.routes.isEmpty()) {
                        raw
                    } else {
                        val primary =
                            allowedRoutes.firstOrNull { it.id == ServerRoute.PRIMARY_ID }
                                ?: error("Saved server primary route is not allowed")
                        raw
                            .copy(
                                routes =
                                    listOf(primary) +
                                        allowedRoutes.filterNot {
                                            it.id == ServerRoute.PRIMARY_ID
                                        },
                                activeRouteId =
                                    raw.activeRouteId?.takeIf { activeId ->
                                        allowedRoutes.any { it.id == activeId }
                                    } ?: ServerRoute.PRIMARY_ID,
                            ).withNormalizedRoutes()
                    }
                }
            result
                .onSuccess { server ->
                    servers += server
                    refs[server.id] = stored.secretRef
                    if (
                        server.previousIds != stored.previousIds ||
                        server.routes != stored.routes ||
                        server.activeRouteId != stored.activeRouteId ||
                        server.baseUrl != stored.baseUrl ||
                        server.localCleartextConfirmed != stored.localCleartextConfirmed
                    ) {
                        changed = true
                    }
                }.onFailure { error ->
                    changed = true
                    if (stored.secretRef !in refs.values && VALID_SECRET_REF.matches(stored.secretRef)) {
                        removeSecretBestEffort(stored.secretRef)
                    }
                    AppLog.warning(
                        category = "server.registry",
                        event = "saved_session_unavailable",
                        message = "A saved session was removed because its secure secret is unavailable; login is required",
                        throwable = error,
                        attributes = mapOf("serverId" to stored.id),
                    )
                }
        }

        val requestedDefault = persisted.defaultServerId
        val defaultId =
            requestedDefault?.takeIf { id -> servers.any { it.id == id } }
                ?: servers.firstOrNull()?.id
        if (defaultId != requestedDefault) changed = true
        val data = ServersData(servers, defaultId)
        if (changed) {
            runCatching { persistMetadata(data, refs) }.onFailure { error ->
                AppLog.warning(
                    category = "server.registry",
                    event = "invalid_session_metadata_cleanup_failed",
                    message = "Unavailable session metadata could not be compacted",
                    throwable = error,
                )
            }
        }
        clearOrphanedLibraryCaches(data)
        return LoadedRegistry(data, refs)
    }

    private fun assignSecretRefs(
        data: ServersData,
        previous: Map<String, String>,
    ): Map<String, String> {
        val used = hashSetOf<String>()
        return buildMap {
            data.servers.forEach { server ->
                val reusable =
                    sequenceOf(server.id)
                        .plus(
                            server.previousIds
                                .asSequence()
                                .toList()
                                .asReversed()
                                .asSequence(),
                        ).mapNotNull(previous::get)
                        .firstOrNull { it !in used }
                val ref = reusable ?: generateSecretRef()
                check(used.add(ref)) { "Secret reference collision" }
                put(server.id, ref)
            }
        }
    }

    private fun generateSecretRef(): String {
        val random = crypto.generateVaultKey()
        return try {
            random.toBase64Url()
        } finally {
            random.fill(0)
        }
    }

    private fun persistMetadata(
        data: ServersData,
        refs: Map<String, String>,
    ) {
        val persisted =
            PersistedServerRegistry(
                version = PERSISTED_VERSION,
                defaultServerId = data.defaultServerId,
                servers =
                    data.servers.map { server ->
                        PersistedServer(
                            id = server.id,
                            baseUrl = server.baseUrl,
                            serverName = server.serverName,
                            userId = server.userId,
                            userName = server.userName,
                            previousIds = server.previousIds,
                            secretRef = requireNotNull(refs[server.id]),
                            routes = server.routes,
                            activeRouteId = server.activeRouteId,
                            iconEmoji = server.iconEmoji,
                            iconTint = server.iconTint,
                            localCleartextConfirmed = server.localCleartextConfirmed,
                        )
                    },
            )
        settings.putString(KEY, json.encodeToString(PersistedServerRegistry.serializer(), persisted))
    }

    private fun writeSecret(
        ref: String,
        token: String,
    ) {
        val bytes = token.encodeToByteArray()
        try {
            secureStore.put(secretKey(ref), bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun rollbackSecretWrites(writes: List<SecretWrite>) {
        writes.asReversed().forEach { write ->
            runCatching {
                write.previousToken?.let { writeSecret(write.ref, it) }
                    ?: secureStore.remove(secretKey(write.ref))
            }.onFailure { error ->
                AppLog.error(
                    category = "server.registry",
                    event = "secret_write_rollback_failed",
                    message = "A failed registry commit could not fully roll back secure storage",
                    throwable = error,
                )
            }
        }
    }

    private fun removeSecretBestEffort(ref: String) {
        runCatching { secureStore.remove(secretKey(ref)) }.onFailure { error ->
            AppLog.warning(
                category = "server.registry",
                event = "orphan_secret_remove_failed",
                message = "An orphaned encrypted session could not be removed",
                throwable = error,
            )
        }
    }

    private fun purgeLegacyPlaintextOrThrow() {
        val empty = PersistedServerRegistry(version = PERSISTED_VERSION)
        val safeValue = json.encodeToString(PersistedServerRegistry.serializer(), empty)
        try {
            settings.putString(KEY, safeValue)
        } catch (_: Exception) {
            try {
                settings.remove(KEY)
            } catch (removeError: Exception) {
                throw IllegalStateException(
                    "Legacy plaintext registry could not be removed",
                    removeError,
                )
            }
        }
    }

    private fun purgeOrdinaryRegistryBestEffort() {
        runCatching(::purgeLegacyPlaintextOrThrow).onFailure { error ->
            AppLog.error(
                category = "server.registry",
                event = "legacy_plaintext_purge_failed",
                message = "Legacy plaintext registry could not be removed",
                throwable = error,
            )
        }
    }

    private fun secretKey(ref: String): String = SECRET_KEY_PREFIX + ref

    private fun requireValidToken(token: String) {
        require(token.isNotBlank() && token.length <= MAX_TOKEN_CHARS) { "服务器访问令牌无效" }
    }

    private fun normalizeImportedServer(
        baseUrl: String,
        serverName: String,
        userId: String,
        userName: String,
        accessToken: String,
        previousIds: Iterable<String>,
        invalidMessagePrefix: String,
        routes: List<ServerRoute> = emptyList(),
        iconEmoji: String? = null,
        iconTint: Long? = null,
        localCleartextConfirmed: Boolean = false,
    ): SavedServer {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val primaryValidation =
            validateEmbyServerEndpoint(
                normalizedBaseUrl,
                localCleartextConfirmed,
            )
        require(normalizedBaseUrl.length in 8..2_048 && primaryValidation.allowed) {
            primaryValidation.message ?: "${invalidMessagePrefix}服务器地址无效"
        }
        val normalizedUserId = userId.trim()
        val token = accessToken.trim()
        require(
            normalizedUserId.isNotEmpty() &&
                normalizedUserId.length <= 256 &&
                token.length in 1..MAX_TOKEN_CHARS,
        ) { "${invalidMessagePrefix}服务器凭据无效" }
        val id = SavedServer.idOf(normalizedBaseUrl, normalizedUserId)
        return SavedServer(
            id = id,
            baseUrl = normalizedBaseUrl,
            serverName =
                serverName
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .trim()
                    .take(60)
                    .ifBlank { "Emby" },
            userId = normalizedUserId,
            userName =
                userName
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .trim()
                    .take(128),
            accessToken = token,
            previousIds = recentPreviousIds(id, previousIds.take(MAX_SERVER_PREVIOUS_IDS)),
            // The imported list is untrusted input: rebuild the primary from the address the
            // id was just derived from and keep only the backups, so a package can never
            // point a server's primary route somewhere its identity does not match.
            routes =
                routes
                    .filterNot { it.id == ServerRoute.PRIMARY_ID }
                    .let { backups ->
                        if (backups.isEmpty()) {
                            emptyList()
                        } else {
                            val primaryName =
                                routes
                                    .firstOrNull { it.id == ServerRoute.PRIMARY_ID }
                                    ?.name
                                    ?: ServerRoute.PRIMARY_NAME
                            listOf(
                                ServerRoute(ServerRoute.PRIMARY_ID, primaryName, normalizedBaseUrl),
                            ) + backups
                        }
                    }.normalizedRoutes()
                    .also { normalizedRoutes ->
                        require(
                            normalizedRoutes.all { route ->
                                validateEmbyServerEndpoint(
                                    route.url,
                                    localCleartextConfirmed,
                                ).allowed
                            },
                        ) { "${invalidMessagePrefix}服务器线路不安全" }
                    },
            activeRouteId = ServerRoute.PRIMARY_ID,
            iconEmoji = sanitizeIconEmoji(iconEmoji),
            iconTint = iconTint,
            localCleartextConfirmed = localCleartextConfirmed,
        ).withNormalizedRoutes()
    }

    private fun clearOrphanedLibraryCaches(data: ServersData) {
        runCatching {
            LibraryCache(settings).clearOrphans(data.servers.mapTo(mutableSetOf()) { it.id })
        }.onFailure { error ->
            AppLog.warning(
                category = "server.registry",
                event = "orphan_library_cache_clear_failed",
                message = "Orphaned library caches could not be cleared",
                throwable = error,
            )
        }
    }
}

/**
 * Carries the cosmetic and route settings of a saved server across a re-login or an edit.
 *
 * A fresh authentication only knows the address it was performed against, so without this a
 * user who re-enters their password loses the icon they picked and every backup route they
 * configured. The freshly authenticated address becomes the primary and the active one — it
 * has just been proven to work, and it is what [SavedServer.id] was derived from — while the
 * old backups survive under their existing ids.
 */
private fun SavedServer.carryingUserSettingsFrom(existing: SavedServer?): SavedServer {
    if (existing == null) return this
    val backups =
        existing.effectiveRoutes
            .drop(1)
            .filterNot { it.url == baseUrl }
    val routes =
        if (backups.isEmpty()) {
            emptyList()
        } else {
            val primaryName = existing.effectiveRoutes.first().name
            listOf(ServerRoute(ServerRoute.PRIMARY_ID, primaryName, baseUrl)) + backups
        }
    return copy(
        routes = routes.normalizedRoutes(),
        activeRouteId = ServerRoute.PRIMARY_ID.takeIf { routes.isNotEmpty() },
        iconEmoji = iconEmoji ?: existing.iconEmoji,
        iconTint = iconTint ?: existing.iconTint,
        localCleartextConfirmed = localCleartextConfirmed || existing.localCleartextConfirmed,
    )
}

/** Enforces the registry invariant before any credential can be persisted or exposed. */
private fun SavedServer.requiringAllowedTransport(
    prefix: String,
    allowUnconfirmedLocalForTests: Boolean = false,
): SavedServer {
    val endpoints =
        buildList {
            add(primaryUrl)
            add(baseUrl)
            addAll(effectiveRoutes.map { it.url })
        }
    endpoints.forEach { endpoint ->
        val validation = validateEmbyServerEndpoint(endpoint, localCleartextConfirmed)
        require(validation.allowed || allowUnconfirmedLocalForTests && validation.requiresCleartextConfirmation) {
            validation.message ?: "${prefix}地址不安全"
        }
    }
    return this
}

/** Later occurrences win so reusing an older alias makes it recent again. */
private fun recentPreviousIds(
    currentId: String,
    vararg histories: Iterable<String>,
): Set<String> {
    val newestFirst =
        buildList {
            histories.forEach { addAll(it) }
        }.asReversed()
            .asSequence()
            .filter { it != currentId }
            .distinct()
            .take(MAX_SERVER_PREVIOUS_IDS)
            .toList()

    return linkedSetOf<String>().apply {
        newestFirst.asReversed().forEach { add(it) }
    }
}

private fun ServersData.withBoundedPreviousIds(): ServersData =
    copy(
        servers =
            servers.map { server ->
                server
                    .copy(previousIds = recentPreviousIds(server.id, server.previousIds))
                    .withNormalizedRoutes()
            },
    )

/**
 * One emoji at most. Stored icons are rendered as text, so an unbounded string would let a
 * long paste stretch the card's header row and push the name out of it.
 */
private fun sanitizeIconEmoji(value: String?): String? =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
        ?.take(ServerIconEmojiMaxChars)

/** A surrogate pair plus a variation selector or two — enough for one composed emoji. */
internal const val ServerIconEmojiMaxChars = 8
