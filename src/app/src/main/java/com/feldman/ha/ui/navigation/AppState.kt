package com.feldman.ha.ui.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.compositionLocalOf
import com.feldman.ha.data.HAEntity
import com.feldman.ha.api.HomeAssistantApi
import com.feldman.ha.api.HomeAssistantOAuthSession
import com.feldman.ha.ui.pages.DashboardEntitySheetState

enum class DashboardEditAction { ADD, EDIT, DELETE }

data class DashboardEditActionRequest(
    val id: Int,
    val action: DashboardEditAction
)

data class AppState(
    val api: HomeAssistantApi,
    val selected: MutableList<HAEntity>,
    val selectedLandscape: MutableList<HAEntity>,
    val all: List<HAEntity>,
    val loading: Boolean,
    val edit: Boolean,
    val onEditToggle: () -> Unit,
    val names: MutableMap<String, String>,
    val configs: MutableMap<String, Map<String, Any>>,
    val saveLayout: () -> Unit,
    val saveConfigs: () -> Unit,
    val saveNames: () -> Unit,
    val scaffoldPadding: PaddingValues,
    val activeCardKey: String?,
    val onActiveCardKeyChange: (String?) -> Unit,
    val configEntityId: String?,
    val onConfigEntityIdChange: (String?) -> Unit,
    val onShowSettings: () -> Unit,
    val onFullScreenChange: (Boolean) -> Unit,
    val dashboardEntitySheetState: DashboardEntitySheetState? = null,
    val hostManagesDashboardBackground: Boolean = false,
    val showExpressiveDashboardSurface: Boolean = true,
    val baseUrl: String,
    val token: String,
    val tokenProvider: suspend () -> String,
    val frigateUrl: String,
    val onSaveSettings: (String, String, String, HomeAssistantOAuthSession?) -> Unit,
    val callServiceOptimistically: (
        entityId: String,
        domain: String,
        service: String,
        body: Map<String, Any>,
        updateFn: (HAEntity) -> HAEntity
    ) -> Unit,
    val loadFailed: Boolean,
    val refreshCountdown: Int,
    val onRetryLoad: () -> Unit,
    val onAddEntity: (HAEntity) -> Unit,
    val onRemoveEntity: (HAEntity) -> Unit,
    val dashboardEditActionRequest: DashboardEditActionRequest? = null,
    val onDashboardEditActionHandled: (Int) -> Unit = {},
    val externalSheetEntityId: String? = null,
    val externalSheetRequestId: Int = 0,
    // Hosts with their own chrome (e.g. Clock's standby screensaver) can hide the dashboard
    // top bar; in Home it is always shown.
    val showDashboardTopBar: Boolean = true,
    // Hide the dashboard pages tab bar (standby has a single page).
    val showDashboardPageTabs: Boolean = true,
    // Render card settings inline instead of a Dialog (needed when the host rotates the UI).
    val inlineCardSettings: Boolean = false,
    // Empty-dashboard-space tap outside edit mode (Clock standby: exit the screensaver).
    val onDashboardBackgroundTap: (() -> Unit)? = null,
)

val LocalAppState = compositionLocalOf<AppState> { error("AppState not provided") }
