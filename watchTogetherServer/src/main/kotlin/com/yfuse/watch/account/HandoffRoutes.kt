package com.yfuse.watch.account

import com.yfuse.watch.protocol.HandoffHeartbeat
import com.yfuse.watch.protocol.HandoffOffer
import com.yfuse.watch.protocol.HandoffTransition
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.handoffRoutes(
    backend: AccountBackend,
    limiter: AccountRateLimiter,
    store: HandoffStore = HandoffStore(),
) {
    route("/api/v1/account/handoff") {
        post("/heartbeat") {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.SyncRead) {
                val account = backend.validateAccessToken(call.requireBearerToken())
                val body = call.receiveLimitedJson<HandoffHeartbeat>(4096)
                call.respondLimitedJson(store.heartbeat(account, body))
            }
        }
        get {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.SyncRead) {
                call.respondLimitedJson(store.inbox(backend.validateAccessToken(call.requireBearerToken())))
            }
        }
        post {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.SyncWrite) {
                val account = backend.validateAccessToken(call.requireBearerToken())
                call.respondLimitedJson(store.offer(account, call.receiveLimitedJson<HandoffOffer>(40 * 1024)))
            }
        }
        post("/{id}") {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.SyncWrite) {
                val account = backend.validateAccessToken(call.requireBearerToken())
                call.respondLimitedJson(
                    store.transition(
                        account,
                        call.parameters["id"].orEmpty(),
                        call.receiveLimitedJson<HandoffTransition>(
                            40 * 1024,
                        ),
                    ),
                )
            }
        }
    }
}
