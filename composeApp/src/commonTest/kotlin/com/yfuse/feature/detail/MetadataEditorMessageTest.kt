package com.yfuse.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataEditorMessageTest {
    @Test
    fun only_a_refusal_blames_edit_permission() {
        assertEquals("操作失败，请检查服务器编辑权限后重试", metadataEditorHttpMessage(401))
        assertEquals("操作失败，请检查服务器编辑权限后重试", metadataEditorHttpMessage(403))
        assertEquals("服务器上找不到该内容，可能已被删除或移动", metadataEditorHttpMessage(404))
        assertEquals("服务器错误(500)", metadataEditorHttpMessage(500))
        assertEquals("服务器暂时不可用或响应超时（503），请稍后重试或切换线路", metadataEditorHttpMessage(503))
        assertEquals("操作失败（HTTP 400），请稍后重试", metadataEditorHttpMessage(400))
    }
}
