package com.yfuse.watch.account

import com.yfuse.watch.protocol.HandoffDevice
import com.yfuse.watch.protocol.HandoffEnvelope
import com.yfuse.watch.protocol.HandoffHeartbeat
import com.yfuse.watch.protocol.HandoffInbox
import com.yfuse.watch.protocol.HandoffOffer
import com.yfuse.watch.protocol.HandoffRequest
import com.yfuse.watch.protocol.HandoffStatus
import com.yfuse.watch.protocol.HandoffTransition
import java.util.Base64

/** Ephemeral, bounded rendezvous. A server restart expires transfers instead of replaying them. */
internal class HandoffStore(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Presence(
        val userId: String,
        val device: HandoffDevice,
    )

    private data class Transfer(
        val userId: String,
        var request: HandoffRequest,
    )

    private val devices = mutableMapOf<String, Presence>()
    private val transfers = mutableMapOf<String, Transfer>()

    @Synchronized
    fun heartbeat(
        account: AuthenticatedAccount,
        heartbeat: HandoffHeartbeat,
    ): HandoffInbox {
        cleanup()
        checkInput(heartbeat.name.isNotBlank() && heartbeat.name.length <= 80 && heartbeat.platform.length in 1..32)
        checkInput(heartbeat.name.none(Char::isISOControl) && heartbeat.platform.none(Char::isISOControl))
        if (account.sessionId !in devices && devices.size >= 4096) busy()
        devices[account.sessionId] =
            Presence(
                account.userId,
                HandoffDevice(account.sessionId, heartbeat.name, heartbeat.platform, now(), heartbeat.canReceive),
            )
        return inbox(account)
    }

    @Synchronized
    fun inbox(account: AuthenticatedAccount): HandoffInbox {
        cleanup()
        return HandoffInbox(
            currentSessionId = account.sessionId,
            devices =
                devices.values
                    .filter { it.userId == account.userId && it.device.sessionId != account.sessionId }
                    .map { it.device }
                    .sortedBy { it.name }
                    .take(64),
            requests =
                transfers.values
                    .filter {
                        it.userId == account.userId &&
                            account.sessionId in setOf(it.request.sourceSessionId, it.request.targetSessionId)
                    }.map { it.request }
                    .sortedWith(
                        compareBy<HandoffRequest> { it.status.terminal }.thenByDescending { it.expiresAtEpochMs },
                    ).take(8),
            serverTimeEpochMs = now(),
        )
    }

    @Synchronized
    fun offer(
        account: AuthenticatedAccount,
        offer: HandoffOffer,
    ): HandoffRequest {
        cleanup()
        checkInput(offer.id.matches(Regex("[A-Za-z0-9-]{16,80}")) && offer.lifetimeSeconds in 15..120)
        validateEnvelope(offer.payload)
        transfers[offer.id]?.let {
            authorize(it.userId == account.userId && it.request.sourceSessionId == account.sessionId)
            checkInput(it.request.targetSessionId == offer.targetSessionId)
            return it.request
        }
        val source = devices[account.sessionId] ?: unavailable()
        val target = devices[offer.targetSessionId] ?: unavailable()
        authorize(source.userId == account.userId && target.userId == account.userId)
        checkInput(offer.targetSessionId != account.sessionId && target.device.canReceive)
        if (transfers.size >= 4096) busy()
        if (transfers.values.any {
                !it.request.status.terminal &&
                    (
                        account.sessionId in setOf(it.request.sourceSessionId, it.request.targetSessionId) ||
                            offer.targetSessionId in setOf(it.request.sourceSessionId, it.request.targetSessionId)
                    )
            }
        ) {
            busy()
        }
        val request =
            HandoffRequest(
                id = offer.id,
                sourceSessionId = account.sessionId,
                targetSessionId = offer.targetSessionId,
                sourceName = source.device.name,
                expiresAtEpochMs = now() + offer.lifetimeSeconds * 1000L,
                payload = offer.payload,
            )
        transfers[offer.id] = Transfer(account.userId, request)
        return request
    }

    @Synchronized
    fun transition(
        account: AuthenticatedAccount,
        id: String,
        change: HandoffTransition,
    ): HandoffRequest {
        cleanup()
        val transfer = transfers[id] ?: unavailable()
        val current = transfer.request
        authorize(transfer.userId == account.userId)
        val source = current.sourceSessionId == account.sessionId
        val target = current.targetSessionId == account.sessionId
        authorize(source || target)
        // Retry after a lost response is safe and never executes an action twice.
        if (current.status == change.status) return current
        if (current.status.terminal) conflict()
        val allowed =
            when (change.status) {
                HandoffStatus.Preparing -> target && current.status == HandoffStatus.Requested
                HandoffStatus.Ready -> target && current.status == HandoffStatus.Preparing
                HandoffStatus.Committed -> source && current.status == HandoffStatus.Ready
                HandoffStatus.Completed -> target && current.status == HandoffStatus.Committed
                HandoffStatus.Rejected -> target && current.status == HandoffStatus.Requested
                HandoffStatus.Failed -> target
                HandoffStatus.Cancelled -> source
                else -> false
            }
        authorize(allowed)
        if (change.status == HandoffStatus.Committed) {
            validateEnvelope(change.payload ?: invalid())
        } else {
            checkInput(change.payload == null)
        }
        return current.copy(status = change.status, payload = change.payload ?: current.payload).also {
            transfer.request = it
        }
    }

    private fun cleanup() {
        val time = now()
        devices.entries.removeAll { time - it.value.device.lastSeenAtEpochMs > 45_000 }
        transfers.values.forEach {
            if (!it.request.status.terminal && it.request.expiresAtEpochMs <= time) {
                it.request = it.request.copy(status = HandoffStatus.Expired)
            }
        }
        transfers.entries.removeAll { time - it.value.request.expiresAtEpochMs > 120_000 }
    }

    private fun validateEnvelope(value: HandoffEnvelope) {
        checkInput(value.nonce.length == 16 && value.ciphertext.length in 22..32_768)
        try {
            checkInput(Base64.getUrlDecoder().decode(value.nonce).size == 12)
            checkInput(Base64.getUrlDecoder().decode(value.ciphertext).size in 16..24_576)
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }

    private fun checkInput(condition: Boolean) {
        if (!condition) invalid()
    }

    private fun authorize(condition: Boolean) {
        if (!condition) throw AccountServiceException(AccountProblem.Forbidden, "handoff_forbidden", "没有权限操作此接力请求")
    }

    private fun invalid(): Nothing =
        throw AccountServiceException(AccountProblem.InvalidRequest, "handoff_invalid", "接力请求无效")

    private fun unavailable(): Nothing =
        throw AccountServiceException(AccountProblem.InvalidRequest, "handoff_unavailable", "设备已离线或接力请求已失效")

    private fun conflict(): Nothing =
        throw AccountServiceException(AccountProblem.VersionConflict, "handoff_finished", "接力请求已经结束")

    private fun busy(): Nothing =
        throw AccountServiceException(
            AccountProblem.RateLimited,
            "handoff_busy",
            "设备正在处理另一项接力，请稍后重试",
            retryAfterSeconds = 5,
        )
}
