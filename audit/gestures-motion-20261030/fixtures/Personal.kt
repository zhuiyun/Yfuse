package com.yfuse.core.personal
const val DEFAULT_PERSONAL_PROFILE = "default"
// Account service is not connected in this host test.
class PersonalLibraryRepository {
 val accountOwnerId = ""
 val activeProfileId = DEFAULT_PERSONAL_PROFILE
 val scopeToken = "legacy"
 val coordinationLock = Any()
 fun observeChanges(observer: () -> Unit) = Unit
}
