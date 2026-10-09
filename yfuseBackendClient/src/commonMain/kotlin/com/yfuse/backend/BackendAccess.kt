package com.yfuse.backend

/** Capability boundary for project-owned services; never applied to user media servers. */
class BackendAccess(
    val enabled: Boolean = BackendBuildConfig.ENABLED,
) {
    fun requireEnabled(feature: BackendFeature) {
        if (!enabled) throw BackendUnavailableException(feature)
    }

    companion object {
        val Default = BackendAccess()
    }
}

class BackendUnavailableException(
    val feature: BackendFeature,
) : IllegalStateException("此版本未启用在线服务")

enum class BackendFeature {
    Account,
    PlaybackSync,
    WatchTogether,
    Calendar,
    Handoff,
    RemoteControl,
    TraktAuthorization,
    Migration,
    Qoe,
    Updates,
    PlaybackPolicy,
}
