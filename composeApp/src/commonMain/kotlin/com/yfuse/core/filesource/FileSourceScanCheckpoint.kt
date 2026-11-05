package com.yfuse.core.filesource

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class FileSourceScanVideo(
    val path: List<String>,
    val sizeBytes: Long? = null,
)

@Serializable
data class FileSourceScanFolder(
    val path: List<String>,
    val depth: Int,
)

/** No credentials. The last published library stays visible while this separate walk resumes. */
@Serializable
data class FileSourceScanCheckpoint(
    val sourceVersion: String,
    val pending: List<FileSourceScanFolder> = listOf(FileSourceScanFolder(emptyList(), 0)),
    val videos: List<FileSourceScanVideo> = emptyList(),
    val foldersRead: Int = 0,
    val partial: Boolean = false,
)

fun FileSource.scanVersion(): String =
    Json.encodeToString(
        listOf(id, kind.name, origin, Json.encodeToString(rootSegments), username, revision.toString()),
    )
