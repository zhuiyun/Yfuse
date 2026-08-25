package com.yfuse.harmony.entry.provider

import encoding.json.*

public func embyAuthenticationPayload(userName: String, password: String): String {
    let value = JsonObject()
    value.put("Username", JsonString(userName))
    value.put("Pw", JsonString(password))
    return value.toJsonString()
}

public func embyPlaybackInfoPayload(
    deviceProfileJson: String,
    startTimeTicks!: Int64 = 0,
    maxStreamingBitrate!: Int64 = 140000000
): String {
    let value = JsonObject()
    value.put("StartTimeTicks", JsonInt(startTimeTicks))
    value.put("MaxStreamingBitrate", JsonInt(maxStreamingBitrate))
    value.put("AutoOpenLiveStream", JsonBool(true))
    value.put("EnableDirectPlay", JsonBool(true))
    value.put("EnableDirectStream", JsonBool(true))
    value.put("EnableTranscoding", JsonBool(true))
    try {
        value.put("DeviceProfile", JsonValue.fromStr(deviceProfileJson))
    } catch (_: Exception) {
        value.put("DeviceProfile", JsonObject())
    }
    return value.toJsonString()
}

public func embyProgressPayload(
    itemId: String,
    mediaSourceId: String,
    playSessionId: String,
    positionTicks: Int64,
    paused: Bool
): String {
    let value = JsonObject()
    value.put("ItemId", JsonString(itemId))
    value.put("MediaSourceId", JsonString(mediaSourceId))
    value.put("PlaySessionId", JsonString(playSessionId))
    value.put("PositionTicks", JsonInt(positionTicks))
    value.put("IsPaused", JsonBool(paused))
    value.put("CanSeek", JsonBool(true))
    return value.toJsonString()
}

public func plexPinPayload(strong!: Bool = true): String {
    let value = JsonObject()
    value.put("strong", JsonBool(strong))
    return value.toJsonString()
}
