package com.recon.dash.ui.nav

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.dash.DashConfig
import com.recon.dash.dash.NavSessionManager
import com.recon.dash.dash.nav.*
import com.recon.dash.data.RideRecorder
import com.recon.dash.util.DebugLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NavDisplayState(
    val etaText: String = "--",
    val remainingText: String = "",
    val distToTurnText: String = "",
    val nextInstruction: String? = null,
    val destinationName: String = "",
    val arrived: Boolean = false,
    val offRoute: Boolean = false,
    val speedAlertActive: Boolean = false,
)

@HiltViewModel
class ActiveNavViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val navSessionManager: NavSessionManager,
    private val rideRecorder: RideRecorder,
    private val dashConfig: DashConfig,
) : ViewModel() {

    companion object {
        private const val TAG = "ActiveNavVM"
        private const val MIN_REROUTE_INTERVAL_MS = 8_000L
        private const val REROUTE_ACCURACY_GATE_M = 50f
    }

    private val _navState = MutableStateFlow(NavDisplayState())
    val navState = _navState.asStateFlow()

    private val _dashStatus = MutableStateFlow("Disconnected")
    val dashStatus = _dashStatus.asStateFlow()

    private val _routeGeometry = MutableStateFlow<List<GeoPoint>>(emptyList())
    val routeGeometry = _routeGeometry.asStateFlow()

    private val _riderPosition = MutableStateFlow<GeoPoint?>(null)
    val riderPosition = _riderPosition.asStateFlow()

    private val _riderBearing = MutableStateFlow(0f)
    val riderBearing = _riderBearing.asStateFlow()

    // Route split for line trimming: traveled (grey, behind) + ahead (blue).
    private val _travelledGeometry = MutableStateFlow<List<GeoPoint>>(emptyList())
    val travelledGeometry = _travelledGeometry.asStateFlow()
    private val _aheadGeometry = MutableStateFlow<List<GeoPoint>>(emptyList())
    val aheadGeometry = _aheadGeometry.asStateFlow()

    val destination = GeoPoint(
        savedStateHandle.get<String>("destLat")?.toDoubleOrNull() ?: 0.0,
        savedStateHandle.get<String>("destLng")?.toDoubleOrNull() ?: 0.0,
    )

    private val router = Router(context)
    private var route: Route? = null
    private var voiceManager: VoiceManager? = null
    private var locationListener: LocationListener? = null
    private var navTickJob: Job? = null

    private val destName = savedStateHandle.get<String>("destName") ?: ""
    private val destLat = savedStateHandle.get<String>("destLat")?.toDoubleOrNull() ?: 0.0
    private val destLng = savedStateHandle.get<String>("destLng")?.toDoubleOrNull() ?: 0.0

    init {
        _navState.value = NavDisplayState(destinationName = destName)
        observeDashStatus()
        startNavigation()
    }

    private fun observeDashStatus() {
        viewModelScope.launch {
            com.recon.dash.dash.DashConnectionState.state.collect { state ->
                _dashStatus.value = when (state) {
                    com.recon.dash.dash.DashState.STREAMING,
                    com.recon.dash.dash.DashState.READY -> "Streaming"
                    else -> "Disconnected"
                }
            }
        }
    }

    private fun startNavigation() {
        viewModelScope.launch {
            if (!router.graphExists()) {
                DebugLog.w(TAG) { "No graph — nav will rely on pre-computed route if available" }
            } else {
                router.load()
            }

            val origin = getLastKnownLocation()
            if (origin != null) {
                computeRoute(origin)
                // Only record a ride when the dash is connected — nav from the phone
                // alone (previewing a route) shouldn't clutter ride history.
                if (com.recon.dash.dash.DashConnectionState.isConnected) {
                    rideRecorder.start(destName, origin.lat, origin.lng)
                }
            } else {
                DebugLog.w(TAG) { "No GPS fix yet — will start recording on first fix" }
            }

            startLocationUpdates()
        }
    }

    private suspend fun computeRoute(from: GeoPoint, isReroute: Boolean = false) {
        val to = GeoPoint(destLat, destLng)
        var result = router.route(from, to)
        if (result is RouterResult.Failure) {
            DebugLog.w(TAG) { "Offline route failed, trying OSRM online: ${(result as RouterResult.Failure).error}" }
            result = com.recon.dash.dash.nav.OsrmClient.route(from, to)
        }
        when (result) {
            is RouterResult.Success -> {
                route = result.route
                _routeGeometry.value = result.route.geometry
                voiceManager = VoiceManager.get(context)
                voiceManager?.resetTrip()
                // Reroute swaps the route (and resets the progress cursor) WITHOUT re-emitting the
                // "nav started" event; only the initial route starts navigation.
                if (isReroute) navSessionManager.updateRoute(result.route)
                else navSessionManager.startNavigation(result.route, destName)
                val r = result.route
                val firstManeuver = r.maneuvers.firstOrNull { it.type != com.recon.dash.dash.nav.ManeuverType.DEPART }
                _navState.value = NavDisplayState(
                    etaText = formatEta(r.totalSeconds),
                    remainingText = formatDistance(r.totalMeters),
                    distToTurnText = firstManeuver?.let { formatDistance(it.cumulativeMeters) } ?: "",
                    nextInstruction = firstManeuver?.instruction,
                    destinationName = destName,
                )
                DebugLog.i(TAG) { "Route computed — ${r.totalMeters.toInt()}m, ${r.maneuvers.size} maneuvers" }
            }
            is RouterResult.Failure -> {
                _navState.value = NavDisplayState(
                    etaText = "--",
                    remainingText = "Route unavailable",
                    destinationName = destName,
                )
                DebugLog.w(TAG) { "Route failed (offline + online): ${result.error}" }
            }
        }
    }

    private fun startLocationUpdates() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { location -> onLocationUpdate(location) }
        locationListener = listener

        try {
            // Seed the map center immediately with the last-known fix so the dash shows the
            // map right away — GPS_PROVIDER can take a while (or never fix indoors), which
            // otherwise left the render loop with pos=null and no map.
            @Suppress("MissingPermission")
            val seed = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (seed != null) {
                DebugLog.i(TAG) { "Seeding position from last-known fix" }
                onLocationUpdate(seed)
            }
            // Register BOTH providers: NETWORK works indoors / before a GPS lock, GPS is
            // accurate outdoors. Whichever delivers first drives the map.
            @Suppress("MissingPermission")
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                    DebugLog.i(TAG) { "Requested updates from $provider" }
                }
            }
        } catch (e: SecurityException) {
            DebugLog.w(TAG) { "Location permission not granted: ${e.message}" }
        }
    }

    private fun onLocationUpdate(location: Location) {
        // Record phone-only rides too — the <100 m discard in RideRecorder is the clutter guard;
        // the old dash-connected gate wrongly suppressed all phone-only rides.
        if (!rideRecorder.isRecording.value && route != null) {
            rideRecorder.start(destName, location.latitude, location.longitude)
        }
        rideRecorder.addPoint(location)

        if (route == null) return
        val speed = location.speed
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE

        // ONE progress computation, shared by phone + dash via NavSessionManager.
        val progress = navSessionManager.onLocationUpdate(
            location.latitude, location.longitude, speed, accuracy,
        ) ?: return

        // Snapped rider + bearing (rides the line, arrow rotates) and the traveled/ahead split.
        _riderPosition.value = progress.snapped
        _riderBearing.value = progress.bearing.toFloat()
        _travelledGeometry.value = progress.traveledGeometry
        _aheadGeometry.value = progress.aheadGeometry

        val threshold = dashConfig.speedAlertKmh
        val speedKmh = speed * 3.6f
        val alertActive = threshold > 0 && speedKmh > threshold

        _navState.value = NavDisplayState(
            etaText = formatEta(progress.etaSeconds),
            remainingText = formatDistance(progress.remainingMeters),
            distToTurnText = formatDistance(progress.distanceToManeuverM),
            nextInstruction = progress.nextManeuver?.instruction,
            destinationName = destName,
            arrived = progress.arrived,
            offRoute = progress.offRoute,
            speedAlertActive = alertActive,
        )

        voiceManager?.maybeAnnounce(
            progress.nextManeuver,
            progress.distanceToManeuverM,
            progress.remainingMeters,
        )

        if (progress.offRoute) {
            maybeReroute(progress.snapped, accuracy)
        }
    }

    // ── Debounced, single-flight reroute (fixes the reroute storm) ──
    @Volatile private var rerouteInFlight = false
    private var lastRerouteAtMs = 0L

    private fun maybeReroute(from: GeoPoint, accuracyM: Float) {
        val now = System.currentTimeMillis()
        when {
            rerouteInFlight ->
                DebugLog.d(TAG) { "reroute suppressed: inFlight" }
            now - lastRerouteAtMs < MIN_REROUTE_INTERVAL_MS ->
                DebugLog.d(TAG) { "reroute suppressed: minInterval (${now - lastRerouteAtMs}ms)" }
            accuracyM > REROUTE_ACCURACY_GATE_M ->
                DebugLog.d(TAG) { "reroute suppressed: lowAccuracy ($accuracyM m)" }
            else -> {
                rerouteInFlight = true
                lastRerouteAtMs = now
                viewModelScope.launch {
                    try {
                        DebugLog.i(TAG) { "Off-route — recalculating from $from" }
                        computeRoute(from, isReroute = true)
                    } finally {
                        rerouteInFlight = false
                    }
                }
            }
        }
    }

    private fun getLastKnownLocation(): GeoPoint? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            @Suppress("MissingPermission")
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            loc?.let { GeoPoint(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            null
        }
    }

    fun stopNavigation() {
        navTickJob?.cancel()
        locationListener?.let { listener ->
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(listener)
        }
        locationListener = null
        voiceManager?.resetTrip()
        navSessionManager.stopNavigation()
        viewModelScope.launch { rideRecorder.stop() }
        router.release()
    }

    override fun onCleared() {
        stopNavigation()
        super.onCleared()
    }

    private fun formatDistance(meters: Double): String = when {
        meters >= 1000 -> "%.1f km".format(meters / 1000.0)
        meters >= 100 -> "${(meters / 50).toInt() * 50} m"
        else -> "${meters.toInt()} m"
    }

    private fun formatEta(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        return when {
            mins >= 60 -> "${mins / 60}h ${mins % 60}m"
            mins > 0 -> "${mins} min"
            else -> "<1 min"
        }
    }
}
