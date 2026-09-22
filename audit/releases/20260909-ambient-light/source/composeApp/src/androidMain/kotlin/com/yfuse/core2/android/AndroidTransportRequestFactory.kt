package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials

internal fun yCoreRandomAccessRequest(
    uri: String,
    protocol: YSourceProtocol,
    startInclusive: Long,
    endInclusive: Long,
    headers: Map<String, String>,
    credentials: YTransportCredentials?,
): YMediaTransportRequest =
    YMediaTransportRequest(
        uri = uri,
        protocol = protocol,
        range = YByteRange(startInclusive, endInclusive),
        headers = headers,
        credentials = credentials,
    )
