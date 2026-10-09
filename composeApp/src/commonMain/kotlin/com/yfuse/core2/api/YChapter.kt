package com.yfuse.core2.api

/** A chapter as the container itself declares it: where it starts on the timeline and its title. */
data class YChapter(
    val startMs: Long,
    val title: String,
)
