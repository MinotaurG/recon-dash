package com.recon.dash.ui

import androidx.compose.animation.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.recon.dash.ui.theme.*
import com.recon.dash.ui.garage.GarageScreen
import com.recon.dash.ui.places.SavedPlacesScreen
import com.recon.dash.ui.places.ManagePlacesScreen
import com.recon.dash.ui.history.RideDetailScreen
import com.recon.dash.ui.history.RideHistoryScreen
import com.recon.dash.ui.home.HomeScreen
import com.recon.dash.ui.nav.ActiveNavScreen
import com.recon.dash.ui.permissions.PermissionsScreen
import com.recon.dash.ui.route.RoutePreviewScreen
import com.recon.dash.ui.search.SearchScreen
import com.recon.dash.ui.settings.RegionDownloadScreen
import com.recon.dash.ui.settings.SettingsScreen
import com.recon.dash.ui.settings.WallpaperPickerScreen
import com.recon.dash.ui.telemetry.TelemetryLabScreen
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
    const val RIDE_DETAIL = "ride_detail"
    const val GARAGE = "garage"
    const val SAVED_PLACES = "saved_places"
    const val MANAGE_PLACES = "manage_places"
    const val TELEMETRY_LAB = "telemetry_lab"
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current

    // Dash session lives at the Activity scope (like OpenDash's DashViewModel), NOT scoped to
    // the Dash nav entry. Obtaining it here — under AppNavigation, hosted by the Activity —
    // means it survives Back navigation and screen changes, so pressing Back from the Dash
    // screen no longer tears down the session and drops the bike connection.
    val dashViewModel: DashViewModel = androidx.hilt.navigation.compose.hiltViewModel()

    fun routePreviewPath(name: String, lat: Double, lng: Double): String {
        val origin = LocationHelper.getLastKnown(context)
        val oLat = origin?.lat ?: 0.0
        val oLng = origin?.lng ?: 0.0
        return "${Routes.ROUTE_PREVIEW}/$name/$lat/$lng/$oLat/$oLng"
    }

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = { slideInFromRight() },
        exitTransition = { fadeOutSmooth() },
        popEnterTransition = { fadeInSmooth() },
        popExitTransition = { slideOutToRight() },
    ) {
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
                onGarageTap = { navController.navigate(Routes.GARAGE) },
                onPlacesTap = { navController.navigate(Routes.SAVED_PLACES) },
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
                    )
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
                onStop = { navController.popBackStack() },
            )
        }
        composable(Routes.DASH) {
            TestScreen(
                onTelemetryLabTap = { navController.navigate(Routes.TELEMETRY_LAB) },
                viewModel = dashViewModel,
            )
        }
        composable(Routes.TELEMETRY_LAB) {
            TelemetryLabScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onWallpaperTap = { navController.navigate(Routes.WALLPAPER_PICKER) },
                onRegionTap = { navController.navigate(Routes.REGION_DOWNLOAD) },
                onManagePlacesTap = { navController.navigate(Routes.MANAGE_PLACES) },
            )
        }
        composable(Routes.MANAGE_PLACES) {
            ManagePlacesScreen(
                onBack = { navController.popBackStack() },
                onSetPlace = { slot ->
                    navController.navigate("${Routes.SEARCH}?saveSlot=${slot.name}")
                },
            )
        }
        composable(Routes.REGION_DOWNLOAD) {
            RegionDownloadScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.WALLPAPER_PICKER) {
            WallpaperPickerScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SAVED_PLACES) {
            SavedPlacesScreen(
                onBack = { navController.popBackStack() },
                onPlaceTap = { place ->
                    navController.navigate(routePreviewPath(place.name, place.lat, place.lng))
                },
                onAddTap = { slot ->
                    navController.navigate("${Routes.SEARCH}?saveSlot=${slot.name}")
                },
            )
        }
        composable(Routes.GARAGE) {
            GarageScreen(
                onBack = { navController.popBackStack() },
                onFuelStationTap = { station ->
                    navController.navigate(
                        routePreviewPath(station.name, station.location.lat, station.location.lng)
                    ) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }
        composable(Routes.RIDE_HISTORY) {
            RideHistoryScreen(
                onBack = { navController.popBackStack() },
                onRideTap = { rideId -> navController.navigate("${Routes.RIDE_DETAIL}/$rideId") },
            )
        }
        composable(
            route = "${Routes.RIDE_DETAIL}/{rideId}",
            arguments = listOf(navArgument("rideId") { type = NavType.StringType }),
        ) {
            RideDetailScreen(
                onBack = { navController.popBackStack() },
                onNavigateAgain = { lat, lng, name ->
                    navController.navigate(routePreviewPath(name, lat, lng)) {
                        popUpTo(Routes.HOME)
                    }
                },
            )
        }
    }
}
