package com.yfuse.core.sync

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class RelayFrameReaderTest {
    @Test
    fun ignoresNonTextFramesAndReturnsTheNextMessage() =
        runTest {
            val incoming = Channel<Frame>(Channel.UNLIMITED)
            incoming.send(Frame.Binary(true, byteArrayOf(1, 2)))
            incoming.send(Frame.Ping(byteArrayOf(3)))
            incoming.send(Frame.Text("message"))
            assertEquals("message", receiveRelayText(incoming, CompletableDeferred()))
            incoming.close()
        }

    @Test
    fun orderlyClosureReturnsNullWithoutTreatingAnUnrelatedPolicyAsAuthentication() =
        runTest {
            for (reason in listOf(
                null,
                CloseReason(CloseReason.Codes.NORMAL, "bye"),
                CloseReason(CloseReason.Codes.VIOLATED_POLICY, "rate_limit"),
            )) {
                val incoming = Channel<Frame>()
                incoming.close()
                assertNull(receiveRelayText(incoming, CompletableDeferred(reason)))
            }
        }

    @Test
    fun authenticationClosureIsReportedAtTheReceiveBoundary() =
        runTest {
            for (reason in listOf("account_auth_required", "account_auth_expired")) {
                val incoming = Channel<Frame>()
                incoming.close()
                assertFailsWith<WatchAuthenticationException> {
                    receiveRelayText(
                        incoming,
                        CompletableDeferred(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason)),
                    )
                }
            }
        }

    @Test
    fun failedIncomingDoesNotWaitForACloseReasonOrBecomeAnOrderlyEnd() =
        runTest {
            val failure = IllegalStateException("socket failed")
            val incoming = Channel<Frame>()
            incoming.close(failure)
            val thrown = assertFailsWith<IllegalStateException> { receiveRelayText(incoming, CompletableDeferred()) }
            assertSame(failure, thrown)
        }

    @Test
    fun cancelledIncomingIsPropagated() =
        runTest {
            val incoming = Channel<Frame>()
            incoming.cancel(CancellationException("session cancelled"))
            assertFailsWith<CancellationException> { receiveRelayText(incoming, CompletableDeferred()) }
        }

    @Test
    fun cancellationOfCloseReasonIsNotConvertedToAnOrderlyEnd() =
        runTest {
            val incoming = Channel<Frame>()
            incoming.close()
            val reason = CompletableDeferred<CloseReason?>()
            reason.cancel(CancellationException("session cancelled"))
            assertFailsWith<CancellationException> { receiveRelayText(incoming, reason) }
        }
}
