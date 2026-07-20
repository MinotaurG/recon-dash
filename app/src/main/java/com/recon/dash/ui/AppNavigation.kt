package com.recon.dash.ui

import androidx.compose.runtime.Composable
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
                    navController.navigate(
                        "${Routes.ROUTE_PREVIEW}/${place.name}/${place.lat}/${place.lng}/0.0/0.0"
                    )
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
                        "${Routes.ROUTE_PREVIEW}/${result.name}/${result.location.lat}/${result.location.lng}/0.0/0.0"
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
