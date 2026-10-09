package com.yfuse.core.account

import com.yfuse.backend.BackendAccess
import com.yfuse.core.network.embyHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/** Supplies the platform engine; account requests and wire contracts live in yfuseBackendClient. */
fun createAccountClient(
    engine: HttpClientEngine = embyHttpEngine(),
    access: BackendAccess = BackendAccess.Default,
): HttpClient = createBackendAccountClient(engine, access)
