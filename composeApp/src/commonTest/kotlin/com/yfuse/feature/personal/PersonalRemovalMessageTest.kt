package com.yfuse.feature.personal

import com.yfuse.core.personal.PersonalCollection
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonalRemovalMessageTest {
    @Test
    fun theToastNamesTheListARecordLeft() {
        assertEquals("已从想看移除「沙丘」", personalRemovalMessage(PersonalCollection.WatchLater, "沙丘"))
        assertEquals("已从收藏移除「沙丘」", personalRemovalMessage(PersonalCollection.Favorite, "沙丘"))
        assertEquals("已移除「沙丘」的观看记录", personalRemovalMessage(PersonalCollection.History, "沙丘"))
    }
}
