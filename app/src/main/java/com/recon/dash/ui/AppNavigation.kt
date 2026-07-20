package com.recon.dash.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.recon.dash.ui.history.RideHistoryScreen
import com.recon.dash.ui.home.HomeScreen
import com.recon.dash.ui.nav.ActiveNavScreen
import com.recon.dash.ui.permissions.PermissionsScreen
import com.recon.dash.ui.route.RoutePreviewScreen
import com.recon.dash.ui.search.SearchScreen
import com.recon.dash.ui.settings.RegionDownloadScreen
import com.recon.dash.ui.settings.SettingsScreen
import com.recon.dash.ui.settings.WallpaperPickerScreen
import com.recon.dash.util.LocationHelper

object Routes {
    const val PERMISSIONS = "permissions"
    const val HOME = "home"
    const val SEARCH = "search"
    const val DASH = "dash"
    const val ROUTE_PREVIEW = "route_preview"
    const val ACTIVE_NAV = "active_nav"
    const val SETTINGS = "settings"
    const val WALLPAPER_PICKER = "wallpaper_picker"
    const val REGION_DOWNLOAD = "region_download"
    const val RIDE_HISTORY = "ride_history"
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current

    fun routePreviewPath(name: String, lat: Double, lng: Double): String {
        val origin = LocationHelper.getLastKnown(context)
        val oLat = origin?.lat ?: 0.0
        val oLng = origin?.lng ?: 0.0
        return "${Routes.ROUTE_PREVIEW}/$name/$lat/$lng/$oLat/$oLng"
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                onAllGranted = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PERMISSIONS) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onSearchTap = { navController.navigate("${Routes.SEARCH}?saveSlot=") },
                onFavoriteTap = { place ->
                    navController.navigate(routePreviewPath(place.name, place.lat, place.lng))
                },
                onFavoriteSlotTap = { slot ->
                    navController.navigate("${Routes.SEARCH}?saveSlot=${slot.name}")
                },
                onDashTap = { navController.navigate(Routes.DASH) },
                onSettingsTap = { navController.navigate(Routes.SETTINGS) },
                onRidesTap = { navController.navigate(Routes.RIDE_HISTORY) },
            )
        }
        composable(
            route = "${Routes.SEARCH}?saveSlot={saveSlot}",
            arguments = listOf(
                navArgument("saveSlot") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            SearchScreen(
                onResultTap = { result ->
                    navController.navigate(
                        routePreviewPath(result.name, result.location.lat, result.location.lng)
                    ) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "${Routes.ROUTE_PREVIEW}/{destName}/{destLat}/{destLng}/{originLat}/{originLng}",
            arguments = listOf(
                navArgument("destName") { type = NavType.StringType },
                navArgument("destLat") { type = NavType.StringType },
                navArgument("destLng") { type = NavType.StringType },
                navArgument("originLat") { type = NavType.StringType },
                navArgument("originLng") { type = NavType.StringType },
            ),
        ) { entry ->
            val destName = entry.arguments?.getString("destName") ?: ""
            val destLat = entry.arguments?.getString("destLat") ?: "0.0"
            val destLng = entry.arguments?.getString("destLng") ?: "0.0"
            RoutePreviewScreen(
                onStartNav = {
                    navController.navigate(
                        "${Routes.ACTIVE_NAV}/$destName/$destLat/$destLng"
                    ) {
                        popUpTo(Routes.HOME)
                    }
                },
                onBack = { navController.popBackStack() },
                onDownloadRegion = {
                    navController.navigate(Routes.REGION_DOWNLOAD)
                },
            )
        }
        composable(
            route = "${Routes.ACTIVE_NAV}/{destName}/{destLat}/{destLng}",
            arguments = listOf(
                navArgument("destName") { type = NavType.StringType },
                navArgument("destLat") { type = NavType.StringType },
                navArgument("destLng") { type = NavType.StringType },
            ),
        ) {
            ActiveNavScreen(
                onStop = { navController.popBackStack(Routes.HOME, false) },
            )
        }
        composable(Routes.DASH) {
            TestScreen()
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onWallpaperTap = { navController.navigate(Routes.WALLPAPER_PICKER) },
                onRegionTap = { navController.navigate(Routes.REGION_DOWNLOAD) },
            )
        }
        composable(Routes.REGION_DOWNLOAD) {
            RegionDownloadScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.WALLPAPER_PICKER) {
            WallpaperPickerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.RIDE_HISTORY) {
            RideHistoryScreen(onBack = { navController.popBackStack() })
        }
    }
}
