package com.yfuse.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

actual fun embyHttpEngine(): HttpClientEngine = CIO.create()
