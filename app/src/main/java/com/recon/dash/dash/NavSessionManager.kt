package com.recon.dash.dash

import com.recon.dash.dash.nav.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class NavUpdate(
    val lat: Double,
    val lng: Double,
    val speedMps: Float,
    val timestamp: Long,
)

/**
 * Shared state between ActiveNavViewModel (produces position updates)
 * and DashViewModel (consumes them to push nav data to the dash).
 *
 * Singleton so both ViewModels see the same instance via Hilt injection.
 */
@Singleton
class NavSessionManager @Inject constructor() {

    private val _activeRoute = MutableStateFlow<Route?>(null)
    val activeRoute = _activeRoute.asStateFlow()

    private val _destinationName = MutableStateFlow("")
    val destinationName = _destinationName.asStateFlow()

    private val _latestPosition = MutableStateFlow<NavUpdate?>(null)
    val latestPosition = _latestPosition.asStateFlow()

    private val _isNavigating = MutableStateFlow(false)
    val isNavigating = _isNavigating.asStateFlow()

    fun startNavigation(route: Route, destination: String) {
        _activeRoute.value = route
        _destinationName.value = destination
        _isNavigating.value = true
    }

    fun updateRoute(route: Route) {
        _activeRoute.value = route
    }

    fun updatePosition(lat: Double, lng: Double, speedMps: Float) {
        _latestPosition.value = NavUpdate(lat, lng, speedMps, System.currentTimeMillis())
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _activeRoute.value = null
        _destinationName.value = ""
        _latestPosition.value = null
    }
}
