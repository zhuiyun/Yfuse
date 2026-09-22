package com.yfuse.core2.demux
// Data-only scaffolding; the production mapper and buffer below are unmodified.
data class YTrackId(val value: Int)
data class YCompressedSample(val trackId: YTrackId, val data: ByteArray, val presentationTimeUs: Long, val durationUs: Long? = null)