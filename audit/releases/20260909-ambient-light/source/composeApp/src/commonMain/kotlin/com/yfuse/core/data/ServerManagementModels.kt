package com.yfuse.core.data

import com.yfuse.core.model.MediaLibrary
import kotlinx.serialization.Serializable

data class ServerManagementSnapshot(
    val libraries: List<MediaLibrary>,
    val tasks: List<ServerScheduledTask>,
    val supportsScheduledTasks: Boolean,
    val supportsMetadataAnalysis: Boolean,
    val plexHomeUsers: List<PlexHomeUser> = emptyList(),
    val supportsPlexHomeSwitch: Boolean = false,
    val scheduledTasksError: String? = null,
)

data class ServerScheduledTask(
    val id: String,
    val name: String,
    val state: String,
    val progressPercent: Double?,
    val lastResult: String?,
)

@Serializable
internal data class EmbyScheduledTaskDto(
    val Id: String = "",
    val Name: String = "",
    val State: String = "Idle",
    val CurrentProgressPercentage: Double? = null,
    val LastExecutionResult: EmbyTaskResultDto? = null,
)

@Serializable
internal data class EmbyTaskResultDto(
    val Status: String? = null,
    val Name: String? = null,
)
