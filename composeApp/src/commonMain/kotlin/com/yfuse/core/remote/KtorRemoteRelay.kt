package com.yfuse.core.remote

import com.yfuse.core.network.embyHttpEngine

/** Supplies the app engine; all relay communication belongs to yfuseBackendClient. */
object KtorRemoteRelayConnector : RemoteRelayConnector by BackendRemoteRelayConnector(::embyHttpEngine)
