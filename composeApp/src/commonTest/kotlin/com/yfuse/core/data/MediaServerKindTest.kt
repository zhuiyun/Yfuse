package com.yfuse.core.data

import com.yfuse.core.data.dto.PublicInfoDto
import com.yfuse.core.model.MediaServerKind
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaServerKindTest {
    @Test
    fun jellyfin_public_info_is_not_persisted_as_emby() {
        assertEquals(
            MediaServerKind.Jellyfin,
            PublicInfoDto(ProductName = "Jellyfin Server").mediaServerKind(),
        )
    }

    @Test
    fun missing_product_name_keeps_backward_compatible_emby_default() {
        assertEquals(MediaServerKind.Emby, PublicInfoDto(ServerName = "Living room").mediaServerKind())
        assertEquals(MediaServerKind.Emby, null.mediaServerKind())
    }
}
