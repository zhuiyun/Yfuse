package com.yfuse.watch.account

import com.yfuse.watch.protocol.TraktAuthStart
import com.yfuse.watch.protocol.TraktRefreshRequest
import com.yfuse.watch.protocol.TraktRevokeRequest
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

internal fun Route.traktOAuthRoutes(
    backend: AccountBackend,
    limiter: AccountRateLimiter,
    broker: TraktOAuthBroker = TraktOAuthBroker(),
) {
    route("/api/v1/account/trakt") {
        get("/configuration") {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.ProfileRead) {
                backend.validateAccessToken(call.requireBearerToken())
                call.respondLimitedJson(broker.configuration())
            }
        }
        post("/authorize") {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.SyncWrite) {
                val account = backend.validateAccessToken(call.requireBearerToken())
                call.respondLimitedJson(broker.begin(account, call.receiveLimitedJson<TraktAuthStart>(1024).device))
            }
        }
        get("/authorize/{id}") {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.SyncRead) {
                call.respondLimitedJson(
                    broker.poll(
                        backend.validateAccessToken(call.requireBearerToken()),
                        call.parameters["id"].orEmpty(),
                    ),
                )
            }
        }
        delete("/authorize/{id}") {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.SyncWrite) {
                broker.cancel(backend.validateAccessToken(call.requireBearerToken()), call.parameters["id"].orEmpty())
                call.respondText("{}", ContentType.Application.Json)
            }
        }
        get("/callback") {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.SyncWrite) {
                broker.callback(
                    call.request.queryParameters["state"].orEmpty(),
                    call.request.queryParameters["code"],
                    call.request.queryParameters["error"] != null,
                )
                call.respondText("Trakt 授权已处理。请返回鱼服查看连接结果。", ContentType.Text.Plain)
            }
        }
        post("/refresh") {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.SyncWrite) {
                val account = backend.validateAccessToken(call.requireBearerToken())
                call.respondLimitedJson(broker.refresh(account, call.receiveLimitedJson<TraktRefreshRequest>(8192)))
            }
        }
        post("/revoke") {
            call.handleAccountEndpoint(limiter, AccountRateLimitBucket.SyncWrite) {
                backend.validateAccessToken(call.requireBearerToken())
                broker.revoke(call.receiveLimitedJson<TraktRevokeRequest>(8192).accessToken)
                call.respondText("{}", ContentType.Application.Json)
            }
        }
    }
}
