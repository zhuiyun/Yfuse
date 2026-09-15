package com.yfuse.watch.account

import com.yfuse.watch.protocol.HandoffEnvelope
import com.yfuse.watch.protocol.HandoffHeartbeat
import com.yfuse.watch.protocol.HandoffOffer
import com.yfuse.watch.protocol.HandoffStatus
import com.yfuse.watch.protocol.HandoffTransition
import kotlinx.serialization.encodeToString
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HandoffStoreTest {
    private var clock = 1_000_000L
    private val store = HandoffStore { clock }
    private val source = account("user", "source")
    private val target = account("user", "target")
    private val stranger = account("other-user", "stranger")
    private val envelope = HandoffEnvelope(encode(12), encode(32))
    private val id = "request-0000000001"

    @Test
    fun presenceAndOffersRemainInsideAccount() {
        online(source)
        online(target)
        online(stranger)
        assertEquals(listOf("target"), store.inbox(source).devices.map { it.sessionId })
        assertFailsWith<AccountServiceException> { store.offer(source, HandoffOffer(id, stranger.sessionId, envelope)) }
        store.offer(source, HandoffOffer(id, target.sessionId, envelope))
        assertTrue(store.inbox(stranger).requests.isEmpty())
        assertFailsWith<AccountServiceException> {
            store.transition(
                stranger,
                id,
                HandoffTransition(HandoffStatus.Cancelled),
            )
        }
    }

    @Test
    fun commitNeedsReceiverReadyAndFreshPayloadAndCompletionNeedsTarget() {
        online(source)
        online(target)
        store.offer(source, HandoffOffer(id, target.sessionId, envelope))
        assertFailsWith<AccountServiceException> {
            store.transition(source, id, HandoffTransition(HandoffStatus.Committed, envelope))
        }
        assertFailsWith<AccountServiceException> {
            store.transition(
                source,
                id,
                HandoffTransition(HandoffStatus.Preparing),
            )
        }
        store.transition(target, id, HandoffTransition(HandoffStatus.Preparing))
        store.transition(target, id, HandoffTransition(HandoffStatus.Ready))
        assertFailsWith<AccountServiceException> {
            store.transition(
                source,
                id,
                HandoffTransition(HandoffStatus.Committed),
            )
        }
        val updated = HandoffEnvelope(encode(12), encode(48))
        assertEquals(updated, store.transition(source, id, HandoffTransition(HandoffStatus.Committed, updated)).payload)
        assertFailsWith<AccountServiceException> {
            store.transition(
                source,
                id,
                HandoffTransition(HandoffStatus.Completed),
            )
        }
        val done = store.transition(target, id, HandoffTransition(HandoffStatus.Completed))
        assertEquals(done, store.transition(target, id, HandoffTransition(HandoffStatus.Completed)))
        assertFailsWith<AccountServiceException> {
            store.transition(
                source,
                id,
                HandoffTransition(HandoffStatus.Cancelled),
            )
        }
    }

    @Test
    fun deadlineExpiresTransferAndOfflinePresenceCannotReceive() {
        online(source)
        online(target)
        store.offer(source, HandoffOffer(id, target.sessionId, envelope, lifetimeSeconds = 15))
        clock += 15_000
        assertEquals(
            HandoffStatus.Expired,
            store
                .inbox(source)
                .requests
                .single()
                .status,
        )
        assertFailsWith<AccountServiceException> {
            store.transition(
                target,
                id,
                HandoffTransition(HandoffStatus.Preparing),
            )
        }
        clock += 31_000
        online(source)
        assertTrue(store.inbox(source).devices.isEmpty())
        assertFailsWith<AccountServiceException> {
            store.offer(source, HandoffOffer("request-0000000002", target.sessionId, envelope))
        }
    }

    @Test
    fun retryIsIdempotentAndDevicesCannotReceiveTwoActiveTransfers() {
        online(source)
        online(target)
        val offer = HandoffOffer(id, target.sessionId, envelope)
        assertEquals(store.offer(source, offer), store.offer(source, offer))
        val other = account("user", "third")
        online(other)
        assertFailsWith<AccountServiceException> {
            store.offer(other, HandoffOffer("request-0000000002", target.sessionId, envelope))
        }
        assertFailsWith<AccountServiceException> {
            store.offer(other, HandoffOffer("request-0000000003", source.sessionId, envelope))
        }
    }

    @Test
    fun malformedOrOversizeEncryptedPayloadIsRejected() {
        online(source)
        online(target)
        for (payload in listOf(envelope.copy(nonce = "!".repeat(16)), envelope.copy(ciphertext = "A".repeat(32_769)))) {
            assertFailsWith<AccountServiceException> {
                store.offer(
                    source,
                    HandoffOffer(id, target.sessionId, payload),
                )
            }
        }
    }

    @Test
    fun inboxKeepsActiveTransferAndBoundsLargeTerminalPayloads() {
        online(source)
        online(target)
        val large = HandoffEnvelope(encode(12), encode(24_576))
        repeat(12) { number ->
            val requestId = "request-${number.toString().padStart(12, '0')}"
            store.offer(source, HandoffOffer(requestId, target.sessionId, large, lifetimeSeconds = 120))
            store.transition(target, requestId, HandoffTransition(HandoffStatus.Rejected))
        }
        val active =
            store.offer(
                source,
                HandoffOffer("request-active0001", target.sessionId, large, lifetimeSeconds = 15),
            )
        val inbox = store.inbox(source)
        assertEquals(8, inbox.requests.size)
        assertEquals(active, inbox.requests.first())
        assertTrue(apiJson.encodeToString(inbox).toByteArray(Charsets.UTF_8).size < AccountLimits.MAX_RESPONSE_BYTES)
    }

    private fun online(account: AuthenticatedAccount) =
        store.heartbeat(account, HandoffHeartbeat(account.sessionId, "Android", true))

    private fun account(
        user: String,
        session: String,
    ) = AuthenticatedAccount(user, session, "viewer", "Viewer", 0, Long.MAX_VALUE)

    private fun encode(size: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size) { 1 })
}
