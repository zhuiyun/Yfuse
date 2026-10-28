package com.yfuse.core.sync
// Series listing is not exercised by the progress lookup tests.
data class EpisodeCoordinate(val seriesKey: String)
fun parseEpisodeWatchKey(key: String): EpisodeCoordinate? = null
