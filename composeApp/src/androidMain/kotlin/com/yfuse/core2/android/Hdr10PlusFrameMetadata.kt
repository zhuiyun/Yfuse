package com.yfuse.core2.android

import com.yfuse.core2.hdr.YHdr10PlusParser
import com.yfuse.core2.hdr.YHdr10PlusSceneMetadata
import java.util.TreeMap

/** Metadata belongs to one picture: never borrow a later B-frame's SEI or retain an old scene. */
internal class Hdr10PlusFrameMetadata(
    private val maximumPending: Int = 96,
) {
    init {
        require(maximumPending > 0)
    }

    private val pending = TreeMap<Long, YHdr10PlusSceneMetadata?>()

    @Synchronized
    fun queue(
        presentationTimeUs: Long,
        payload: ByteArray?,
    ) {
        pending[presentationTimeUs] = payload?.let(YHdr10PlusParser::parse)
        while (pending.size > maximumPending) pending.pollFirstEntry()
    }

    @Synchronized
    fun take(presentationTimeUs: Long): YHdr10PlusSceneMetadata? = pending.remove(presentationTimeUs)

    @Synchronized
    fun clear() = pending.clear()
}
