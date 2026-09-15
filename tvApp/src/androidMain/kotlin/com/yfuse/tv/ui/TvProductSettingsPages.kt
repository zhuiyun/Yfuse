package com.yfuse.tv.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.arkivanov.mvikotlin.extensions.coroutines.states
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.feature.handoff.DeviceHandoffScreen
import com.yfuse.feature.personal.PersonalCenterScreen
import com.yfuse.feature.personal.PersonalCenterTab
import com.yfuse.feature.profile.ProfileComponent
import com.yfuse.feature.trakt.TraktSettingsScreen

/** The first TV row owns initial/return focus; shared controls already support D-pad activation. */
@Composable
internal fun TvProductSettingsPage(
    page: TvSettingsPage,
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
    onBack: () -> Unit,
) {
    val servers by component.store.states.collectAsState(component.store.state)
    Column(Modifier.fillMaxSize()) {
        TvSettingRow(
            title = "返回我的与设置",
            value = page.title,
            stableId = "settings:product:${page.name}:back",
            focusMemory = focusMemory,
            onClick = onBack,
            icon = AppIcons.ChevronLeft,
            focusScope = "settings:product:${page.name}",
            focusRequester = firstRowRequester,
            navigationRequester = navigationRequester,
        )
        Box(Modifier.weight(1f).focusGroup()) {
            when (page) {
                TvSettingsPage.Handoff -> DeviceHandoffScreen(component.handoff, onBack)
                TvSettingsPage.Trakt -> TraktSettingsScreen(component.trakt, onBack, television = true)
                else ->
                    PersonalCenterScreen(
                        personal = component.personal,
                        account = component.account,
                        playbackSync = component.playbackSync,
                        serverSync = component.dependencies.serverSyncManager,
                        servers =
                            if (component.personal.policy.value.canManageServers) {
                                component.familyServers()
                            } else {
                                servers.servers
                            },
                        onBack = onBack,
                        onOpenMedia = component.onOpenPersonalMedia,
                        initialTab =
                            when (page) {
                                TvSettingsPage.Family -> PersonalCenterTab.Profiles
                                TvSettingsPage.SyncStatus -> PersonalCenterTab.Sync
                                else -> PersonalCenterTab.WatchLater
                            },
                        repo = component.repository,
                    )
            }
        }
    }
}
