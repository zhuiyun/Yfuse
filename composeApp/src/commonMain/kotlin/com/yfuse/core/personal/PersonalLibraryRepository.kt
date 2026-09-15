package com.yfuse.core.personal

import com.russhwolf.settings.Settings
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.base64UrlToBytes
import com.yfuse.core.security.toBase64Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Personal assets belong to an account/profile, independently of the available media servers. */
class PersonalLibraryRepository(
    private val settings: Settings,
    private val crypto: VaultCrypto = VaultCrypto(),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    internal val coordinationLock: Any get() = lock
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val deviceId =
        settings.getStringOrNull(DEVICE_KEY) ?: randomId().also { settings.putString(DEVICE_KEY, it) }
    private var accountId = settings.getStringOrNull(OWNER_KEY).orEmpty()
    private var snapshot = load()
    private var activeId = settings.getStringOrNull(activeKey()).orEmpty().ifBlank { DEFAULT_PERSONAL_PROFILE }
    private var generation = 0L
    private var publishedPin: PersonalPin? = null
    private val observers = mutableListOf<() -> Unit>()
    private val _policy = MutableStateFlow(PersonalAccessPolicy())
    val policy: StateFlow<PersonalAccessPolicy> = _policy.asStateFlow()
    private val _state = MutableStateFlow(PersonalLibraryState())
    val state: StateFlow<PersonalLibraryState> = _state.asStateFlow()

    init {
        publish()
    }

    val scopeToken: String get() = synchronized(lock) { "$accountId:$activeId:$generation" }
    val activeProfileId: String get() = synchronized(lock) { activeId }
    val storageNamespace: String get() = synchronized(lock) { "${accountId.ifEmpty { "anonymous" }}.$activeId" }

    fun canAccessServer(serverId: String): Boolean = policy.value.allowsServer(serverId)

    fun policyForProfile(profileId: String): PersonalAccessPolicy? =
        synchronized(lock) {
            snapshot.profiles
                .firstOrNull {
                    it.id == profileId && !it.deleted
                }?.let { PersonalAccessPolicy(it.id, it.child, it.serverIds) }
        }

    fun requireServerManagement() {
        check(policy.value.canManageServers) { "请先使用家长 PIN 切换到成人资料" }
    }

    /** Observers are application-owned and run synchronously, before a switch returns. */
    fun observeChanges(observer: () -> Unit) {
        synchronized(lock) {
            observers += observer
            observer()
        }
    }

    fun bindAccount(userId: String?) =
        synchronized(lock) {
            val next = userId.orEmpty()
            if (next == accountId) return@synchronized
            // Session expiry must not be a way out of an active child profile.
            if (next.isEmpty() && policy.value.child) return@synchronized
            val adopting = accountId.isEmpty() && next.isNotEmpty() && settings.getStringOrNull(dataKey(next)) == null
            accountId = next
            settings.putString(OWNER_KEY, accountId)
            snapshot = if (adopting) snapshot else load()
            activeId = settings.getStringOrNull(activeKey()).orEmpty().ifBlank { DEFAULT_PERSONAL_PROFILE }
            generation++
            _state.value = PersonalLibraryState()
            if (adopting) persist(snapshot)
            publish()
        }

    fun snapshot(): PersonalSnapshot = synchronized(lock) { json.decodeFromString(json.encodeToString(snapshot)) }

    fun mergeRemote(remote: PersonalSnapshot) =
        synchronized(lock) {
            val merged = mergePersonalSnapshots(snapshot, remote)
            persist(merged)
            snapshot = merged
            publish(pending = merged != remote)
        }

    fun beginSync() {
        _state.value = _state.value.copy(syncing = true, error = null)
    }

    fun finishSync(
        sent: PersonalSnapshot,
        epochMs: Long,
    ) = synchronized(lock) {
        val pending = snapshot != sent
        settings.putBoolean(pendingKey(), pending)
        settings.putLong(lastSyncKey(), epochMs)
        _state.value =
            _state.value.copy(syncing = false, pendingSync = pending, lastSyncedAtEpochMs = epochMs, error = null)
    }

    fun failSync(message: String) {
        _state.value = _state.value.copy(syncing = false, error = message)
    }

    suspend fun setGuardianPin(
        pin: CharArray,
        currentPin: CharArray = charArrayOf(),
    ): Result<Unit> =
        withContext(Dispatchers.Default) {
            runCatching {
                val token = scopeToken
                require(pin.size in 4..12 && pin.all(Char::isDigit)) { "PIN 需为 4–12 位数字" }
                verifyGuardian(currentPin, required = synchronized(lock) { snapshot.guardianPin != null })
                val salt = crypto.generateVaultKey().copyOf(16)
                val hash = crypto.deriveRecoveryKey(pin, salt, VaultCrypto.MIN_PBKDF2_ITERATIONS)
                try {
                    synchronized(lock) {
                        check(token == scopeToken) { "资料已切换，请重试" }
                        change(
                            snapshot.copy(
                                guardianPin = PersonalPin(salt.toBase64Url(), hash.toBase64Url(), nextStamp()),
                            ),
                        )
                    }
                } finally {
                    salt.fill(0)
                    hash.fill(0)
                }
            }.also {
                pin.fill('\u0000')
                currentPin.fill('\u0000')
            }
        }

    suspend fun saveProfile(
        id: String? = null,
        name: String,
        child: Boolean,
        serverIds: Set<String>,
        pin: CharArray = charArrayOf(),
    ): Result<Unit> =
        withContext(Dispatchers.Default) {
            runCatching {
                val token = scopeToken
                verifyGuardian(pin, required = policy.value.child)
                synchronized(lock) {
                    check(token == scopeToken) { "资料已切换，请重试" }
                    require(name.trim().isNotEmpty() && name.trim().length <= 40) { "资料名称需为 1–40 个字" }
                    require(!child || snapshot.guardianPin != null) { "请先设置家长 PIN" }
                    require(id != DEFAULT_PERSONAL_PROFILE || !child) { "主要资料必须是成人资料" }
                    require(id == null || snapshot.profiles.any { it.id == id && !it.deleted }) { "资料不存在" }
                    val profile = PersonalProfile(id ?: randomId(), name.trim(), child, serverIds.toSet(), nextStamp())
                    change(snapshot.copy(profiles = snapshot.profiles.filterNot { it.id == profile.id } + profile))
                }
            }.also { pin.fill('\u0000') }
        }

    suspend fun switchProfile(
        id: String,
        pin: CharArray = charArrayOf(),
    ): Result<Unit> =
        withContext(Dispatchers.Default) {
            runCatching {
                val token = scopeToken
                verifyGuardian(pin, required = policy.value.child && id != activeProfileId)
                synchronized(lock) {
                    check(token == scopeToken) { "资料已切换，请重试" }
                    require(snapshot.profiles.any { it.id == id && !it.deleted }) { "资料不存在" }
                    if (id != activeId) {
                        activeId = id
                        generation++
                        settings.putString(activeKey(), id)
                        publish()
                    }
                }
            }.also { pin.fill('\u0000') }
        }

    suspend fun deleteProfile(
        id: String,
        pin: CharArray = charArrayOf(),
    ): Result<Unit> =
        withContext(Dispatchers.Default) {
            runCatching {
                val token = scopeToken
                verifyGuardian(pin, required = policy.value.child)
                synchronized(lock) {
                    check(token == scopeToken) { "资料已切换，请重试" }
                    require(id != DEFAULT_PERSONAL_PROFILE && id != activeId) { "请先切换资料；主要资料不能删除" }
                    val old = snapshot.profiles.firstOrNull { it.id == id } ?: error("资料不存在")
                    change(
                        snapshot.copy(
                            profiles =
                                snapshot.profiles.filterNot { it.id == id } +
                                    old.copy(deleted = true, stamp = nextStamp()),
                        ),
                    )
                }
            }.also { pin.fill('\u0000') }
        }

    fun setFavorite(
        media: PersonalMediaRef,
        enabled: Boolean,
    ) = setEntry(PersonalCollection.Favorite, media, !enabled)

    fun setWatchLater(
        media: PersonalMediaRef,
        enabled: Boolean,
    ) = setEntry(PersonalCollection.WatchLater, media, !enabled)

    fun removeHistory(media: PersonalMediaRef) = setEntry(PersonalCollection.History, media, true)

    fun clearHistory() =
        synchronized(lock) {
            tryMutation {
                val stamp = nextStamp()
                change(
                    snapshot.copy(
                        entries =
                            snapshot.entries.map {
                                if (it.profileId == activeId &&
                                    it.collection == PersonalCollection.History &&
                                    !it.deleted
                                ) {
                                    it.copy(deleted = true, stamp = stamp)
                                } else {
                                    it
                                }
                            },
                    ),
                )
            }
        }

    fun recordHistory(
        media: PersonalMediaRef,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
        expectedScopeToken: String = scopeToken,
    ) = synchronized(lock) {
        if (expectedScopeToken != scopeToken ||
            media.serverId?.let { !canAccessServer(it) } == true
        ) {
            return@synchronized
        }
        val previous = findEntry(PersonalCollection.History, media)
        val richMedia = if (previous != null && media.title == media.mediaKey) previous.media else media
        val entry =
            PersonalEntry(
                activeId,
                PersonalCollection.History,
                richMedia,
                nextStamp(),
                watchedAtEpochMs = nowEpochMs(),
                positionMs = positionMs.coerceAtLeast(0),
                durationMs = durationMs.coerceAtLeast(0),
                completed = completed,
            )
        tryMutation { putEntry(entry) }
        Unit
    }

    /** Imports never overwrite a local value or tombstone, including an explicit unwatched decision. */
    fun importWatchLater(media: PersonalMediaRef): Boolean =
        synchronized(lock) {
            if (findEntry(PersonalCollection.WatchLater, media) != null) return@synchronized false
            setWatchLater(media, true)
        }

    fun importFavorite(media: PersonalMediaRef): Boolean =
        synchronized(lock) {
            if (findEntry(PersonalCollection.Favorite, media) != null) return@synchronized false
            setFavorite(media, true)
        }

    fun importWatched(
        media: PersonalMediaRef,
        watchedAtEpochMs: Long,
    ): Boolean =
        synchronized(lock) {
            if (findEntry(PersonalCollection.History, media) != null) return@synchronized false
            tryMutation {
                putEntry(
                    PersonalEntry(
                        activeId,
                        PersonalCollection.History,
                        media,
                        nextStamp(),
                        watchedAtEpochMs = watchedAtEpochMs,
                        completed = true,
                    ),
                )
            }
        }

    fun followedSeries(): List<FollowedSeries> =
        synchronized(lock) {
            snapshot.follows
                .filter { it.profileId == activeId && !it.deleted }
                .map { it.series }
                .sortedBy { it.title }
        }

    fun replaceFollowedSeries(
        series: List<FollowedSeries>,
        expectedScopeToken: String = scopeToken,
    ): Boolean =
        synchronized(lock) {
            if (expectedScopeToken != scopeToken) return@synchronized false
            tryMutation {
                val previous = snapshot.follows.filter { it.profileId == activeId }.associateBy { it.series.tmdbId }
                val incoming = series.associateBy { it.tmdbId }
                val changed =
                    (previous.keys + incoming.keys).mapNotNull { id ->
                        val old = previous[id]
                        val next = incoming[id]
                        when {
                            next == null && old?.deleted == false -> old.copy(deleted = true, stamp = nextStamp())
                            next != null && (old?.series != next || old?.deleted == true) ->
                                PersonalFollow(
                                    activeId,
                                    next,
                                    nextStamp(),
                                )
                            else -> old
                        }
                    }
                val value = snapshot.copy(follows = snapshot.follows.filterNot { it.profileId == activeId } + changed)
                if (value != snapshot) change(value)
            }
        }

    private fun setEntry(
        collection: PersonalCollection,
        media: PersonalMediaRef,
        deleted: Boolean,
    ): Boolean =
        synchronized(lock) {
            tryMutation {
                require(media.serverId == null || canAccessServer(media.serverId)) { "当前资料不能访问这个服务器用户" }
                val previous = findEntry(collection, media)
                if (previous != null && previous.deleted == deleted) return@tryMutation
                putEntry(
                    PersonalEntry(activeId, collection, media, nextStamp(), deleted, watchedAtEpochMs = nowEpochMs()),
                )
            }
        }

    private fun findEntry(
        collection: PersonalCollection,
        media: PersonalMediaRef,
    ): PersonalEntry? =
        snapshot.entries.firstOrNull {
            it.profileId == activeId &&
                it.collection == collection &&
                it.media.identity == media.identity
        }

    private fun putEntry(entry: PersonalEntry) =
        change(
            snapshot.copy(
                entries =
                    snapshot.entries.filterNot { it.identity == entry.identity } + entry,
            ),
        )

    private suspend fun verifyGuardian(
        pin: CharArray,
        required: Boolean,
    ) {
        if (!required) return
        val owner = synchronized(lock) { accountId }
        val key = "$PIN_ATTEMPTS_PREFIX$owner"
        check(nowEpochMs() >= settings.getLong("$key.blocked", 0)) { "PIN 尝试过多，请一分钟后再试" }
        val stored = synchronized(lock) { snapshot.guardianPin } ?: error("请先设置家长 PIN")
        require(pin.isNotEmpty()) { "请输入家长 PIN" }
        val actual = crypto.deriveRecoveryKey(pin, stored.salt.base64UrlToBytes(), stored.iterations)
        val expected = stored.hash.base64UrlToBytes()
        val matches =
            try {
                var difference = actual.size xor expected.size
                for (i in actual.indices) {
                    difference =
                        difference or (actual[i].toInt() xor expected.getOrElse(i) { 0 }.toInt())
                }
                difference == 0
            } finally {
                actual.fill(0)
                expected.fill(0)
            }
        if (!matches) {
            val count = settings.getInt(key, 0) + 1
            settings.putInt(key, count)
            if (count >= 5) {
                settings.putLong("$key.blocked", nowEpochMs() + 60_000)
                settings.putInt(key, 0)
            }
            error("PIN 不正确")
        }
        settings.remove(key)
        settings.remove("$key.blocked")
    }

    private fun nextStamp(): PersonalStamp =
        PersonalStamp(
            (
                snapshot.profiles.map { it.stamp.counter } + snapshot.entries.map { it.stamp.counter } +
                    snapshot.follows.map { it.stamp.counter } + (snapshot.guardianPin?.stamp?.counter ?: 0)
            ).maxOrNull()!!.plus(1),
            deviceId,
        )

    private fun change(value: PersonalSnapshot) {
        validatePersonalSnapshot(value)
        persist(value)
        snapshot = value
        _state.value = _state.value.copy(error = null)
        publish(pending = true)
    }

    /** Background playback and direct button callbacks keep the last valid snapshot on failure. */
    private fun tryMutation(action: () -> Unit): Boolean =
        try {
            action()
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _state.value = _state.value.copy(error = error.message ?: "个人数据保存失败，原有记录已保留")
            false
        }

    private fun publish(pending: Boolean = settings.getBoolean(pendingKey(), false)) {
        val profiles = snapshot.profiles.filterNot { it.deleted }
        // Deleting the active profile on another device must not silently unlock the adult profile.
        val active = profiles.firstOrNull { it.id == activeId } ?: PersonalProfile(activeId, "资料已停用", child = true)
        val entries = snapshot.entries.filter { it.profileId == activeId && !it.deleted }
        settings.putBoolean(pendingKey(), pending)
        val nextPolicy = PersonalAccessPolicy(active.id, active.child, active.serverIds)
        if (_policy.value != nextPolicy || publishedPin != snapshot.guardianPin) generation++
        publishedPin = snapshot.guardianPin
        _policy.value = nextPolicy
        _state.value =
            _state.value.copy(
                activeProfile = active,
                profiles = profiles,
                favorites =
                    entries
                        .filter {
                            it.collection == PersonalCollection.Favorite
                        }.sortedByDescending { it.stamp },
                watchLater =
                    entries
                        .filter {
                            it.collection == PersonalCollection.WatchLater
                        }.sortedByDescending { it.stamp },
                history =
                    entries
                        .filter {
                            it.collection == PersonalCollection.History
                        }.sortedByDescending { it.watchedAtEpochMs },
                hasGuardianPin = snapshot.guardianPin != null,
                pendingSync = pending,
                lastSyncedAtEpochMs = settings.getLongOrNull(lastSyncKey()),
            )
        observers.toList().forEach { it() }
    }

    private fun persist(value: PersonalSnapshot) {
        settings.putString(dataKey(), json.encodeToString(value))
    }

    private fun load(): PersonalSnapshot =
        settings.getStringOrNull(dataKey())?.let { raw ->
            runCatching { json.decodeFromString<PersonalSnapshot>(raw).also(::validatePersonalSnapshot) }.getOrNull()
        } ?: PersonalSnapshot()

    private fun dataKey(owner: String = accountId): String = "personal.library.v1.${owner.ifEmpty { "anonymous" }}"

    private fun activeKey(): String = "${dataKey()}.active"

    private fun pendingKey(): String = "${dataKey()}.pending"

    private fun lastSyncKey(): String = "${dataKey()}.last_synced"

    private fun randomId(): String = "p${Random.nextLong().toULong().toString(16)}"

    private companion object {
        const val DEVICE_KEY = "personal.device.v1"
        const val OWNER_KEY = "personal.owner.v1"
        const val PIN_ATTEMPTS_PREFIX = "personal.pin_attempts.v1."
    }
}
