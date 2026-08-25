package com.nuvio.app

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.memory.MemoryCache
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.DeviceSessionRegistration
import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.deeplink.AppDeepLinkRepository
import com.nuvio.app.core.network.NetworkCondition
import com.nuvio.app.core.network.NetworkStatusRepository
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.ProfileSettingsSync
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.ui.LocalNuvioNavBarScrollState
import com.nuvio.app.core.ui.NuvioNavigationBar
import com.nuvio.app.core.ui.NuvioClassicNavigationBar
import com.nuvio.app.core.ui.NuvioNavBarScrollState
import com.nuvio.app.core.ui.rememberNuvioNavBarScrollState
import com.nuvio.app.core.format.formatReleaseDateForDisplay
import com.nuvio.app.core.ui.NuvioContinueWatchingActionSheet
import com.nuvio.app.core.ui.NuvioCardDepthSurface
import com.nuvio.app.core.ui.NuvioPosterZoomActionOverlay
import com.nuvio.app.core.ui.PosterZoomAnchor
import com.nuvio.app.core.ui.PosterZoomAnchorHolder
import com.nuvio.app.core.ui.PosterZoomOverlayAction
import com.nuvio.app.core.ui.PosterZoomOverlayExitAnimation
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.PlatformKeyboardNavigation
import com.nuvio.app.core.ui.platformExitApp
import com.nuvio.app.core.ui.configurePlatformImageLoader
import com.nuvio.app.core.ui.NuvioToastHost
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioFloatingPrompt
import com.nuvio.app.core.ui.nuvioFocusBorder
import com.nuvio.app.core.ui.ProfileMeshBackground
import com.nuvio.app.core.ui.TrackingListPickerDialog
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.LocalNuvioBottomNavigationOverlayPadding
import com.nuvio.app.core.ui.NativeNavigationTab
import com.nuvio.app.core.ui.NativeProfileSwitcherController
import com.nuvio.app.core.ui.desktopUiScaleForWindow
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.navigation.AppRoute
import com.nuvio.app.navigation.TabsRoute

fun disposeRoute(route: AppRoute) {
    disposeRouteResources(route)
}

@OptIn(ExperimentalCoilApi::class)
@Composable
@Preview
fun App(
    initialTab: AppScreenTab = AppScreenTab.Home,
    initialRoute: AppRoute = TabsRoute,
    useNativeNavigation: Boolean = false,
    useNativeTabBar: Boolean = false,
    useTabletFloatingTabBar: Boolean = false,
    ownsAppRuntime: Boolean = true,
    bypassAppGate: Boolean = false,
    onNavigate: ((AppRoute, launchSingleTop: Boolean) -> Unit)? = null,
    onGoBack: (() -> Unit)? = null,
    onReplace: ((AppRoute) -> Unit)? = null,
    onActivate: ((AppScreenTab) -> Unit)? = null,
    onAppReady: ((Boolean) -> Unit)? = null,
    onTabTitles: ((home: String, search: String, library: String, profile: String, switchProfile: String, addProfile: String) -> Unit)? = null,
    nativeProfileSwitcherController: NativeProfileSwitcherController? = null,
    appGateController: AppGateController? = null,
) {
    AppEnvironment {
        AppGate(
            initialTab = initialTab,
            initialRoute = initialRoute,
            useNativeNavigation = useNativeNavigation,
            useNativeTabBar = useNativeTabBar,
            useTabletFloatingTabBar = useTabletFloatingTabBar,
            ownsAppRuntime = ownsAppRuntime,
            bypassAppGate = bypassAppGate,
            renderMainContent = true,
            onNavigate = onNavigate,
            onGoBack = onGoBack,
            onReplace = onReplace,
            onActivate = onActivate,
            onAppReady = onAppReady,
            onMainContentMountChanged = null,
            onMainContentVisibleChanged = null,
            onTabTitles = onTabTitles,
            nativeProfileSwitcherController = nativeProfileSwitcherController,
            appGateController = appGateController,
        )
    }
}

@Composable
internal fun AppEnvironment(content: @Composable () -> Unit) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(128 * 1024 * 1024)
                    .build()
            }
            .components {
                add(SvgDecoder.Factory())
                add(
                    coil3.network.ktor3.KtorNetworkFetcherFactory(
                        cacheStrategy = { coil3.network.cachecontrol.CacheControlCacheStrategy() },
                    ),
                )
            }
            .configurePlatformImageLoader()
            .build()
    }
    val selectedTheme by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.selectedTheme
    }.collectAsStateWithLifecycle()
    val amoledEnabled by remember {
        ThemeSettingsRepository.amoledEnabled
    }.collectAsStateWithLifecycle()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        NuvioTheme(
            appTheme = selectedTheme,
            amoled = amoledEnabled,
            desktopUiScale = desktopUiScaleForWindow(maxWidth.value, maxHeight.value),
        ) {
            content()
        }
    }
}
