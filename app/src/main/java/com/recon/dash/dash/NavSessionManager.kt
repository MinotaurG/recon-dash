package com.recon.dash.dash

import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.dash.nav.Maneuver
import com.recon.dash.dash.nav.NavEngine
import com.recon.dash.dash.nav.Route
import com.recon.dash.util.NavLog
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
 * A single, authoritative snapshot of navigation progress, computed ONCE per GPS fix and shared
 * by every consumer (phone map, phone nav card, dash renderer, dash route-card). Replaces the
 * old design where the phone and the dash each ran [NavEngine] independently and could disagree.
 */
data class NavProgress(
    val snapped: GeoPoint,
    val bearing: Double,
    val traveledGeometry: List<GeoPoint>,   // route consumed so far (grey/trimmed)
    val aheadGeometry: List<GeoPoint>,       // route still to ride (blue)
    val distanceToManeuverM: Double,
    val nextManeuver: Maneuver?,
    val remainingMeters: Double,
    val etaSeconds: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    val snapDistanceM: Double,
    val currentStreet: String,
)

/**
 * Shared navigation state + the SINGLE source of truth for progress.
 *
 * ActiveNavViewModel produces GPS fixes ([onLocationUpdate]); this class owns the one [NavEngine]
 * and publishes [progress]. Both ActiveNavViewModel and the dash side (NavDashBridge / the
 * DashViewModel render loop) consume [progress] — neither runs its own NavEngine.
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

    /** The one, shared progress snapshot. Null when not navigating / before the first fix. */
    private val _progress = MutableStateFlow<NavProgress?>(null)
    val progress = _progress.asStateFlow()

    private var engine: NavEngine? = null

    // After a reroute we suppress off-route detection for a short grace window so the fresh
    // NavEngine (cursor at 0 on the new route) can acquire the rider's position — otherwise the
    // very next fix re-trips off-route and we feedback-loop into a reroute storm.
    @Volatile private var offRouteSuppressedUntilMs = 0L
    private val graceMs = 6_000L

    fun startNavigation(route: Route, destination: String) {
        _activeRoute.value = route
        _destinationName.value = destination
        _isNavigating.value = true
        engine = NavEngine(route)          // fresh progress cursor for the new route
        _progress.value = null
        offRouteSuppressedUntilMs = System.currentTimeMillis() + graceMs
        NavLog.event("nav_start", "dest=$destination m=${route.totalMeters.toInt()} man=${route.maneuvers.size}")
    }

    /** Reroute: swap in the new route AND reset the progress cursor to match it. */
    fun updateRoute(route: Route) {
        _activeRoute.value = route
        engine = NavEngine(route)
        _progress.value = null
        offRouteSuppressedUntilMs = System.currentTimeMillis() + graceMs
        NavLog.event("route_swapped", "m=${route.totalMeters.toInt()} man=${route.maneuvers.size}")
    }

    /**
     * Feed a GPS fix; runs the single NavEngine update and republishes [progress].
     * Returns the fresh snapshot (or null if not navigating) so callers can act immediately.
     */
    fun onLocationUpdate(lat: Double, lng: Double, speedMps: Float, accuracyM: Float, bearingDeg: Float? = null): NavProgress? {
        _latestPosition.value = NavUpdate(lat, lng, speedMps, System.currentTimeMillis())
        val eng = engine ?: return null
        val p = eng.update(GeoPoint(lat, lng), speedMps, accuracyM, bearingDeg)
        val (traveled, ahead) = eng.split(p)
        // Honor the post-reroute grace window: don't surface off-route while the new route settles.
        val offRoute = p.offRoute && System.currentTimeMillis() >= offRouteSuppressedUntilMs
        val snapshot = NavProgress(
            snapped = p.snapped,
            bearing = p.routeBearing,
            traveledGeometry = traveled,
            aheadGeometry = ahead,
            distanceToManeuverM = p.distanceToManeuverM,
            nextManeuver = p.nextManeuver,
            remainingMeters = p.remainingMeters,
            etaSeconds = p.etaSeconds,
            offRoute = offRoute,
            arrived = p.arrived,
            snapDistanceM = p.snapDistanceM,
            currentStreet = p.currentStreet,
        )
        _progress.value = snapshot
        NavLog.fix(
            lat = lat, lng = lng, accM = accuracyM, snapM = p.snapDistanceM,
            cumM = p.traveledMeters, remM = p.remainingMeters, dManM = p.distanceToManeuverM,
            maneuver = p.nextManeuver?.instruction, offRoute = offRoute,
            arrived = p.arrived, speedMps = speedMps,
            // Glyph ground-truth: the exact byte + type the dash was sent this fix.
            maneuverType = p.nextManeuver?.type?.name,
            glyphCode = p.nextManeuver?.dashCode,
            exitCount = p.nextManeuver?.roundaboutExitCount,
        )
        return snapshot
    }

    fun stopNavigation() {
        _isNavigating.value = false
        _activeRoute.value = null
        _destinationName.value = ""
        _latestPosition.value = null
        _progress.value = null
        engine = null
        NavLog.event("nav_stop")
    }
}
