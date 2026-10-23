package com.yfuse.core2.android

import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.legacy.YNativeSubtitleStyle
import com.yfuse.core2.legacy.YNativeSubtitleStyleTarget

/** Callers update intent; only the router worker applies it to an attaching or active child. */
internal class AndroidChildPlaybackSettings(
    private val scheduleApply: () -> Unit,
) {
    private data class Snapshot(
        val videoSuspended: Boolean = false,
        val subtitleStyle: YNativeSubtitleStyle? = null,
    )

    @Volatile
    private var latest = Snapshot()

    val videoSuspended: Boolean get() = latest.videoSuspended

    fun setVideoSuspended(suspended: Boolean) {
        synchronized(this) { latest = latest.copy(videoSuspended = suspended) }
        scheduleApply()
    }

    fun setSubtitleStyle(style: YNativeSubtitleStyle) {
        synchronized(this) { latest = latest.copy(subtitleStyle = style) }
        scheduleApply()
    }

    /**
     * Runs on the same worker as child attachment. A concurrent update queues another application
     * after this one; it never calls the child directly and cannot be overwritten by this snapshot.
     */
    fun applyTo(child: YPlayer): Boolean {
        val snapshot = latest
        child.setVideoSuspended(snapshot.videoSuspended)
        return snapshot.subtitleStyle?.let { style ->
            (child as? YNativeSubtitleStyleTarget)?.setNativeSubtitleStyle(style) != false
        } ?: true
    }
}
