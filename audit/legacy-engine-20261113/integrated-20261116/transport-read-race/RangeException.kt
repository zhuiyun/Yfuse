package com.yfuse.core2.android
import java.io.IOException
import com.yfuse.core2.network.YTransportFailureKind
internal open class AndroidRangeResponseException(
    val failureKind: YTransportFailureKind,
    val statusCode: Int,
    val expectedRangeStart: Long,
    val acceptedRangeStart: Long?,
    safeMessage: String,
) : IOException(safeMessage)
