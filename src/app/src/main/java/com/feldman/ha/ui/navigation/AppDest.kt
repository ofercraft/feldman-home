@file:Suppress("PROPERTY_WONT_BE_SERIALIZED")

package com.feldman.ha.ui.navigation

import androidx.compose.runtime.Composable
import com.github.skydoves.navgraph.annotations.NavDestination
import com.github.skydoves.navgraph.annotations.NavEdge
import com.github.skydoves.navgraph.annotations.NavGraphRoot
import com.feldman.motion.navigation.Dest
import com.feldman.motion.navigation.Navigator
import com.feldman.ha.ui.pages.CamerasPage
import com.feldman.ha.ui.pages.FrigateCameraPage
import com.feldman.ha.ui.pages.HomeAssistantCameraPage
import com.feldman.ha.ui.pages.CameraSettingsPage
import com.feldman.ha.ui.pages.ConnectionSettingsPage
import com.feldman.ha.ui.pages.DashboardSettingsPage
import com.feldman.ha.ui.pages.PhoneSettingsPage
import com.feldman.ha.ui.pages.SettingsPage
import com.feldman.ha.ui.pages.ScreensaverSettingsPage
import com.feldman.ha.ui.pages.AppearancePage
import com.feldman.ha.ui.pages.HomePage
import com.feldman.ha.R
import com.feldman.ha.ui.pages.CustomFeatureDetailPage
import com.feldman.motion.navigation.PaneType
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.IgnoredOnParcel

@Serializable
@Parcelize
sealed class AppDest : Dest {

    @NavGraphRoot
    @NavEdge(to = Grid::class, label = "bottom bar: cameras")
    @NavEdge(to = Detail::class, label = "open Frigate camera")
    @NavEdge(to = HACamera::class, label = "open Home Assistant camera")
    @NavEdge(to = Settings::class, label = "settings")
    @Serializable
    @Parcelize
    data class Dashboard(val dummy: Int = 0) : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Home"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_home
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_home

        @NavDestination(route = Dashboard::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            val state = LocalAppState.current
            com.feldman.ha.ui.pages.HomePage(
                api = state.api,
                selected = state.selected,
                selectedLandscape = state.selectedLandscape,
                all = state.all,
                loading = state.loading,
                edit = state.edit,
                onEditToggle = state.onEditToggle,
                names = state.names,
                configs = state.configs,
                saveLayout = state.saveLayout,
                saveConfigs = state.saveConfigs,
                saveNames = state.saveNames,
                scaffoldPadding = state.scaffoldPadding,
                activeCardKey = state.activeCardKey,
                onActiveCardKeyChange = state.onActiveCardKeyChange,
                configEntityId = state.configEntityId,
                onConfigEntityIdChange = state.onConfigEntityIdChange,
                onShowSettings = state.onShowSettings,
                isCameraOpen = false,
                loadFailed = state.loadFailed,
                refreshCountdown = state.refreshCountdown,
                onRetryLoad = state.onRetryLoad,
                onNavigate = onNavigate,
                onAddEntity = state.onAddEntity,
                onRemoveEntity = state.onRemoveEntity,
                dashboardEditActionRequest = state.dashboardEditActionRequest,
                entityStateSheetState = state.dashboardEntitySheetState,
                hostManagesBackground = state.hostManagesDashboardBackground,
                showExpressiveSurface = state.showExpressiveDashboardSurface,
                externalSheetEntityId = state.externalSheetEntityId,
                externalSheetRequestId = state.externalSheetRequestId,
                showTopBar = state.showDashboardTopBar,
                showPageTabs = state.showDashboardPageTabs,
                inlineCardSettings = state.inlineCardSettings,
                onBackgroundTap = state.onDashboardBackgroundTap,
            )
        }
    }

    @NavEdge(to = Dashboard::class, label = "bottom bar: home")
    @NavEdge(to = Detail::class, label = "open camera")
    @Serializable
    @Parcelize
    data class Grid(val frigateUrl: String, val token: String) : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Cameras"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = R.drawable.ic_menu_camera
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = R.drawable.ic_menu_camera

        @NavDestination(route = Grid::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            val state = LocalAppState.current
            CamerasPage(
                frigateUrl = frigateUrl,
                token = token,
                tokenProvider = state.tokenProvider,
                onCameraClick = { camera -> onNavigate(Detail(camera, frigateUrl, token)) },
                onShowSettings = state.onShowSettings,
                scaffoldPadding = state.scaffoldPadding
            )
        }
    }

    @Serializable
    @Parcelize
    data class Detail(val cameraName: String, val frigateUrl: String, val token: String) : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Camera Detail"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = 0
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = 0
        @IgnoredOnParcel
        @Transient
        override val pane = PaneType.DETAIL
        @IgnoredOnParcel
        @Transient
        override val parent = Grid(frigateUrl, token)

        @NavDestination(route = Detail::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            val state = LocalAppState.current
            FrigateCameraPage(
                cameraName = cameraName,
                frigateUrl = frigateUrl,
                token = token,
                tokenProvider = state.tokenProvider,
                onBack = onBack,
                onFullScreenChange = state.onFullScreenChange,
                scaffoldPadding = state.scaffoldPadding
            )
        }
    }

    @Serializable
    @Parcelize
    data object Settings : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Settings"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = 0
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = 0
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @NavDestination(route = Settings::class)
        @NavEdge(to = ThemeSettings::class, label = "appearance")
        @NavEdge(to = ConnectionSettings::class, label = "connection")
        @NavEdge(to = DashboardSettings::class, label = "dashboard")
        @NavEdge(to = ScreensaverSettings::class, label = "screensaver")
        @NavEdge(to = CameraSettings::class, label = "cameras")
        @NavEdge(to = PhoneSettings::class, label = "phone")
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            val state = LocalAppState.current
            SettingsPage(
                onBack = onBack,
                onOpenAppearance = { onNavigate(ThemeSettings) },
                onOpenConnection = { onNavigate(ConnectionSettings) },
                onOpenDashboard = { onNavigate(DashboardSettings) },
                onOpenScreensaver = { onNavigate(ScreensaverSettings) },
                onOpenCameras = { onNavigate(CameraSettings) },
                onOpenPhone = { onNavigate(PhoneSettings) }
            )
        }
    }

    @Serializable
    @Parcelize
    data object ConnectionSettings : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Connection"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = 0
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = 0
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @NavDestination(route = ConnectionSettings::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            val state = LocalAppState.current
            ConnectionSettingsPage(
                initialBaseUrl = state.baseUrl,
                initialToken = state.token,
                frigateUrl = state.frigateUrl,
                onBack = onBack,
                onSave = state.onSaveSettings
            )
        }
    }

    @Serializable
    @Parcelize
    data object DashboardSettings : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Dashboard"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = 0
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = 0
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @NavDestination(route = DashboardSettings::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            DashboardSettingsPage(onBack = onBack)
        }
    }

    @Serializable
    @Parcelize
    data object ScreensaverSettings : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Screensaver"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = 0
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = 0
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @NavDestination(route = ScreensaverSettings::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            ScreensaverSettingsPage(onBack = onBack)
        }
    }
    @Serializable
    @Parcelize
    data object CameraSettings : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Cameras"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = 0
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = 0
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @NavDestination(route = CameraSettings::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            val state = LocalAppState.current
            CameraSettingsPage(
                baseUrl = state.baseUrl,
                token = state.token,
                initialFrigateUrl = state.frigateUrl,
                onBack = onBack,
                onSave = state.onSaveSettings
            )
        }
    }

    @Serializable
    @Parcelize
    data object PhoneSettings : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Phone"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = 0
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = 0
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @NavDestination(route = PhoneSettings::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            PhoneSettingsPage(onBack = onBack)
        }
    }

    @Serializable
    @Parcelize
    data object ThemeSettings : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Appearance"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = 0
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = 0
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @NavDestination(route = ThemeSettings::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            AppearancePage(onBack = onBack)
        }
    }

    @Serializable
    @Parcelize
    data class HACamera(val entityId: String, val haBaseUrl: String, val token: String) : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Camera"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = 0
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = 0
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @NavDestination(route = HACamera::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            val state = LocalAppState.current
            HomeAssistantCameraPage(
                entityId = entityId,
                haBaseUrl = haBaseUrl,
                token = token,
                tokenProvider = state.tokenProvider,
                haEntity = state.all.find { it.entity_id == entityId },
                onBack = onBack,
                onFullScreenChange = state.onFullScreenChange,
            )
        }
    }

    @Serializable
    @Parcelize
    data class CustomFeatureDetail(val entityId: String, val featureId: String? = null) : AppDest() {
        @IgnoredOnParcel
        @Transient
        override val label = "Configure Custom Feature"
        @IgnoredOnParcel
        @Transient
        override val filledIcon = 0
        @IgnoredOnParcel
        @Transient
        override val outlineIcon = 0
        @IgnoredOnParcel
        @Transient
        override val showNavigation = false

        @NavDestination(route = CustomFeatureDetail::class)
        @Composable
        override fun Content(onNavigate: Navigator, onBack: () -> Unit, searchQuery: String, onFabAction: ((() -> Unit) -> Unit) -> Unit) {
            val state = LocalAppState.current
            CustomFeatureDetailPage(
                entityId = entityId,
                featureId = featureId,
                onBack = onBack,
                appState = state
            )
        }
    }
}
