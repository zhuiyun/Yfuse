package com.yfuse.backend

interface BackendDiagnostics {
    fun info(
        event: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    )

    fun warning(
        event: String,
        message: String,
        attributes: Map<String, String> = emptyMap(),
    )

    object None : BackendDiagnostics {
        override fun info(
            event: String,
            message: String,
            attributes: Map<String, String>,
        ) = Unit

        override fun warning(
            event: String,
            message: String,
            attributes: Map<String, String>,
        ) = Unit
    }
}
