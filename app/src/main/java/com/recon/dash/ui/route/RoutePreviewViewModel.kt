package com.recon.dash.ui.route

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.dash.nav.ManeuverType
import com.recon.dash.dash.nav.OsrmClient
import com.recon.dash.dash.nav.Route
import com.recon.dash.dash.nav.RouteOptions
import com.recon.dash.dash.nav.Router
import com.recon.dash.dash.nav.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RouteChoice(
    val distanceText: String,
    val etaText: String,
    val turnCount: Int,
    val isSelected: Boolean,
)

sealed class RoutePreviewState {
    object Loading : RoutePreviewState()
    object NoGraph : RoutePreviewState()
    data class Ready(
        val destinationName: String,
        val distanceText: String,
        val etaText: String,
        val turnCount: Int,
        val maneuvers: List<String>,
        val alternatives: List<RouteChoice> = emptyList(),
        val avoidTolls: Boolean = false,
        val avoidHighways: Boolean = false,
        val isOnlineRoute: Boolean = false,
    ) : RoutePreviewState()
    data class Error(val message: String) : RoutePreviewState()
    /**
     * Routing failed and the origin falls in a region whose map isn't installed — offer to download
     * it right here instead of a dead-end "Retry". [available] is false for regions not yet hosted.
     */
    data class RegionMissing(
        val regionId: String,
        val regionName: String,
        val sizeMb: Int,
        val available: Boolean,
    ) : RoutePreviewState()
}

@HiltViewModel
class RoutePreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val router: Router,
    private val regionManager: com.recon.dash.data.RegionManager,
) : ViewModel() {

    private val _state = MutableStateFlow<RoutePreviewState>(RoutePreviewState.Loading)
    val state = _state.asStateFlow()

    private val _selectedGeometry = MutableStateFlow<List<com.recon.dash.dash.nav.GeoPoint>>(emptyList())
    val selectedGeometry = _selectedGeometry.asStateFlow()

    private val destName: String = savedStateHandle.get<String>("destName") ?: "Destination"
    val destLat: Double = savedStateHandle.get<String>("destLat")?.toDoubleOrNull() ?: 0.0
    val destLng: Double = savedStateHandle.get<String>("destLng")?.toDoubleOrNull() ?: 0.0
    private val originLat: Double = savedStateHandle.get<String>("originLat")?.toDoubleOrNull() ?: 0.0
    private val originLng: Double = savedStateHandle.get<String>("originLng")?.toDoubleOrNull() ?: 0.0

    private var allRoutes: List<Route> = emptyList()
    private var selectedIndex = 0
    private var avoidTolls = false
    private var avoidHighways = false
    private var usedOnlineRouting = false

    init {
        calculateRoute()
    }

    fun retry() {
        calculateRoute()
    }

    fun selectAlternative(index: Int) {
        if (index in allRoutes.indices) {
            selectedIndex = index
            updateReadyState()
        }
    }

    fun toggleAvoidTolls() {
        avoidTolls = !avoidTolls
        calculateRoute()
    }

    fun toggleAvoidHighways() {
        avoidHighways = !avoidHighways
        calculateRoute()
    }

    fun getSelectedRoute(): Route? = allRoutes.getOrNull(selectedIndex)

    private fun calculateRoute() {
        _state.value = RoutePreviewState.Loading
        viewModelScope.launch {
            val from = GeoPoint(originLat, originLng)
            val to = GeoPoint(destLat, destLng)

            // Try offline (Valhalla) first when a graph is loadable, then ALWAYS fall back to
            // online (OSRM) before giving up — a missing/other-region extract must not block a
            // route when the network is available.
            var result: RouterResult = RouterResult.Failure(
                com.recon.dash.dash.nav.RouterError.GraphNotLoaded("not attempted")
            )
            if (router.graphExists() && router.load().isSuccess) {
                usedOnlineRouting = false
                result = router.route(
                    from, to,
                    RouteOptions(avoidTolls = avoidTolls, avoidHighways = avoidHighways, alternativeRoutes = true),
                )
            }
            if (result is RouterResult.Failure) {
                // Offline unavailable or failed (e.g. outside the loaded extract) — try the network.
                usedOnlineRouting = true
                result = routeOnline(from, to)
            }

            when (result) {
                is RouterResult.Success -> {
                    allRoutes = listOf(result.route) + result.alternatives
                    selectedIndex = 0
                    updateReadyState()
                }
                is RouterResult.Failure -> {
                    allRoutes = emptyList()
                    // Both offline AND online failed. If the origin is in a region we don't have
                    // installed, offer to download it (the durable offline fix); otherwise a plain error.
                    val region = regionManager.regionForLocation(originLat, originLng)
                    if (region != null && region.id != regionManager.installedRegionId()) {
                        _state.value = RoutePreviewState.RegionMissing(
                            regionId = region.id,
                            regionName = region.name,
                            sizeMb = region.totalSizeMb,
                            available = regionManager.isRegionAvailable(region),
                        )
                    } else {
                        val msg = when (val err = result.error) {
                            is com.recon.dash.dash.nav.RouterError.GraphNotLoaded -> err.message
                            is com.recon.dash.dash.nav.RouterError.NoRouteFound -> "No route found to destination"
                            is com.recon.dash.dash.nav.RouterError.RoutingFailed -> "Routing error: ${err.cause.message}"
                        }
                        _state.value = RoutePreviewState.Error(msg)
                    }
                }
            }
        }
    }

    private suspend fun routeOnline(from: GeoPoint, to: GeoPoint): RouterResult {
        return OsrmClient.route(from, to)
    }

    private fun updateReadyState() {
        val route = allRoutes.getOrNull(selectedIndex) ?: return
        _selectedGeometry.value = route.geometry
        _state.value = RoutePreviewState.Ready(
            destinationName = destName,
            distanceText = formatDistance(route.totalMeters),
            etaText = formatEta(route.totalSeconds),
            turnCount = route.maneuvers.count {
                it.type != ManeuverType.DEPART && it.type != ManeuverType.CONTINUE
            },
            maneuvers = route.maneuvers
                .filter { it.type != ManeuverType.DEPART }
                .map { it.instruction },
            alternatives = allRoutes.mapIndexed { i, r ->
                RouteChoice(
                    distanceText = formatDistance(r.totalMeters),
                    etaText = formatEta(r.totalSeconds),
                    turnCount = r.maneuvers.count {
                        it.type != ManeuverType.DEPART && it.type != ManeuverType.CONTINUE
                    },
                    isSelected = i == selectedIndex,
                )
            },
            avoidTolls = avoidTolls,
            avoidHighways = avoidHighways,
            isOnlineRoute = usedOnlineRouting,
        )
    }

    // Router is a process-lifetime singleton now; do NOT release it on VM teardown.

    private fun formatDistance(meters: Double): String = when {
        meters >= 1000 -> "%.1f km".format(meters / 1000.0)
        else -> "${meters.toInt()} m"
    }

    private fun formatEta(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        return when {
            mins >= 60 -> "${mins / 60}h ${mins % 60}m"
            else -> "${mins} min"
        }
    }
}
