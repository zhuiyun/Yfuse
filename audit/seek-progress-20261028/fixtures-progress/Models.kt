package com.yfuse.core.model
// Metadata-only types; no progress policy is implemented in fixtures.
data class SavedServer(val id: String, val baseUrl: String, val serverName: String,
 val userId: String, val userName: String, val accessToken: String)
data class MediaDetail(val id: String, val played: Boolean = false, val resumePositionTicks: Long? = null)
