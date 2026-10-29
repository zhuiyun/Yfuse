package com.yfuse.core.data.dto
data class UserDataDto(val PlayedPercentage: Double? = null, val PlaybackPositionTicks: Long? = null,
 val LastPlayedDate: String? = null, val Played: Boolean? = null, val IsFavorite: Boolean? = null)
data class BaseItemDto(val Id: String, val Type: String? = null, val RunTimeTicks: Long? = null,
 val UserData: UserDataDto? = null, val ProviderIds: Map<String,String> = emptyMap())
