package com.recon.dash.ui.nav

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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

/** Route summary shown on arrival (mirrors the RE app's "Navigation Ended" card). */
data class RideSummary(
    val distanceMeters: Double,
    val durationSeconds: Long,
    val avgSpeedKmh: Double,
)

data class NavDisplayState(
    val etaText: String = "--",
    val remainingText: String = "",
    val distToTurnText: String = "",
    val nextInstruction: String? = null,
    val destinationName: String = "",
    val currentStreet: String = "",
    val arrived: Boolean = false,
    /** Populated once on arrival so the screen can show a route summary instead of going blank. */
    val summary: RideSummary? = null,
    val offRoute: Boolean = false,
    val speedAlertActive: Boolean = false,
    /**
     * True when the OS Battery Saver is ON. On Samsung One UI, Battery Saver throttles screen-off
     * GPS delivery to ~20s network-only batches (verified 2026-08-02) — devastating for nav — and
     * per-app "unrestricted" does NOT override it. We surface a warning so the rider disables it.
     */
    val batterySaverOn: Boolean = false,
)

@HiltViewModel
class ActiveNavViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val navSessionManager: NavSessionManager,
    private val rideRecorder: RideRecorder,
    private val dashConfig: DashConfig,
    private val divergenceCapture: com.recon.dash.dash.nav.DivergenceCapture,
    private val router: Router,
    @com.recon.dash.di.ApplicationScope private val appScope: kotlinx.coroutines.CoroutineScope,
) : ViewModel() {

    companion object {
        private const val TAG = "ActiveNavVM"
        private const val MIN_REROUTE_INTERVAL_MS = 8_000L
        private const val REROUTE_ACCURACY_GATE_M = 50f
        // If a real GPS fix arrived within this window, ignore NETWORK-provider fixes entirely.
        private const val GPS_FRESH_MS = 10_000L
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

    private var route: Route? = null
    private var voiceManager: VoiceManager? = null
    private var locationListener: LocationListener? = null
    private var navTickJob: Job? = null

    // Location delivery: FusedLocationProviderClient (Play Services) is the PRIMARY source — it
    // fuses GPS + network + device sensors and does short dead-reckoning, so it survives the
    // momentary GPS dropouts near buildings that made raw LocationManager go stale (the Prestige
    // "freeze"), and it's more resistant to OEM screen-off throttling. We keep the raw
    // LocationManager path as a fallback if Play Services is unavailable, and either way funnel
    // into the same onLocationUpdate(Location) sink so the rest of the pipeline is unchanged.
    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    private var fusedCallback: LocationCallback? = null

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

    /** OS Battery Saver on? It throttles screen-off GPS (Samsung: ~20s network-only batches). */
    private fun isBatterySaverOn(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isPowerSaveMode

    private fun startNavigation() {
        // Keep GPS alive with the screen off for the WHOLE nav, independent of the dash. Previously
        // only the dash held this foreground/wakelock, so a flapping dash link froze GPS mid-ride.
        com.recon.dash.dash.DashKeepAliveService.startFor(
            context, com.recon.dash.dash.DashKeepAliveService.REASON_NAV,
        )
        if (isBatterySaverOn()) {
            DebugLog.w(TAG) { "BATTERY SAVER is ON — screen-off GPS will be throttled (~20s gaps). Warn user." }
            com.recon.dash.util.NavLog.event("battery_saver", "on=true")
        }
        registerScreenStateLogging()
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
            startDivergenceTicker()
        }
    }

    private suspend fun computeRoute(from: GeoPoint, isReroute: Boolean = false) {
        val to = GeoPoint(destLat, destLng)
        // Track which engine actually produced the route (was a static "valhalla/osrm" string that
        // told us nothing — so we could never tell from logs whether a ride was offline or online).
        var actualSource = "valhalla"
        var result = router.route(from, to)
        if (result is RouterResult.Failure) {
            val why = (result as RouterResult.Failure).error
            DebugLog.w(TAG) { "Offline (valhalla) route failed, trying OSRM online: $why" }
            com.recon.dash.util.NavLog.route("valhalla_fail", 0.0, 0, reroute = isReroute)
            actualSource = "osrm"
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
                com.recon.dash.util.NavLog.route(actualSource, r.totalMeters, r.maneuvers.size, reroute = isReroute)
                DebugLog.i(TAG) { "Route computed via $actualSource — ${r.totalMeters.toInt()}m, ${r.maneuvers.size} maneuvers" }
                // Debug-only: capture how this on-device route diverges from Google's, for offline
                // costing analysis. Runs off the nav path and never blocks routing.
                captureDivergence(r, from, if (isReroute) "reroute" else "plan")
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

        // Seed the map immediately with the last-known fix so the dash shows the map right away.
        try {
            @Suppress("MissingPermission")
            val seed = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (seed != null) {
                DebugLog.i(TAG) { "Seeding position from last-known fix" }
                onLocationUpdate(seed)
            }
        } catch (e: SecurityException) {
            DebugLog.w(TAG) { "Location permission not granted (seed): ${e.message}" }
        }

        if (startFusedUpdates()) return       // primary path
        startRawLocationUpdates(lm)           // fallback if Play Services unavailable
    }

    /**
     * Primary location path. Returns true if fused updates were requested. High-accuracy, 1 Hz.
     * Delivered on the MAIN looper — kept alive by the foreground service so it survives screen-off
     * (a private HandlerThread broke screen-off GPS on Samsung; see the raw path's history).
     */
    private fun startFusedUpdates(): Boolean {
        return try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(1000L)
                .setWaitForAccurateLocation(false)
                .build()
            val cb = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { onLocationUpdate(it) }
                }
            }
            fusedCallback = cb
            @Suppress("MissingPermission")
            fusedClient.requestLocationUpdates(request, cb, Looper.getMainLooper())
            DebugLog.i(TAG) { "Location: FusedLocationProviderClient @1Hz (high accuracy)" }
            true
        } catch (e: SecurityException) {
            DebugLog.w(TAG) { "Fused location permission denied: ${e.message}" }; false
        } catch (e: Exception) {
            // Play Services missing / disabled — fall back to raw LocationManager.
            DebugLog.w(TAG) { "Fused location unavailable (${e.message}); falling back to LocationManager" }; false
        }
    }

    /** Fallback path: raw LocationManager, same behavior as before Fused was introduced. */
    private fun startRawLocationUpdates(lm: LocationManager) {
        val listener = LocationListener { location -> onLocationUpdate(location) }
        locationListener = listener
        val looper = Looper.getMainLooper()
        try {
            @Suppress("MissingPermission")
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 1000L, 0f, listener, looper)
                    DebugLog.i(TAG) { "Location: raw LocationManager updates from $provider" }
                }
            }
        } catch (e: SecurityException) {
            DebugLog.w(TAG) { "Location permission not granted: ${e.message}" }
        }
    }

    /** Stop whichever location source is active (fused and/or raw). Safe to call repeatedly. */
    private fun stopLocationUpdates() {
        fusedCallback?.let { runCatching { fusedClient.removeLocationUpdates(it) } }
        fusedCallback = null
        locationListener?.let { listener ->
            runCatching {
                (context.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(listener)
            }
        }
        locationListener = null
    }

    private fun onLocationUpdate(location: Location) {
        // Record phone-only rides too — the <100 m discard in RideRecorder is the clutter guard;
        // the old dash-connected gate wrongly suppressed all phone-only rides.
        if (!rideRecorder.isRecording.value && route != null) {
            rideRecorder.start(destName, location.latitude, location.longitude)
        }
        rideRecorder.addPoint(location)

        if (route == null) return
        val rawPos = GeoPoint(location.latitude, location.longitude)
        val speed = location.speed
        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE

        // Log the provider + inter-fix gap so we can SEE the location-pipeline health (was invisible:
        // we could only infer network-fix poisoning + starved fix rate from accuracy values).
        val nowMs = System.currentTimeMillis()
        val gapMs = if (lastFixAtMs == 0L) 0 else nowMs - lastFixAtMs
        lastFixAtMs = nowMs
        com.recon.dash.util.NavLog.event(
            "fix_src",
            "prov=${location.provider} acc=${accuracy.toInt()} gapMs=$gapMs v=${"%.1f".format(speed)}",
        )

        // Reject coarse NETWORK fixes while GPS is fresh. NETWORK (cell/wifi) fixes are ~100 m-1 km
        // and fire alongside real GPS; feeding them to the matcher snapped the rider up to ~1.2 km
        // off-road and broke turn-by-turn. Track the last GPS fix time; if a good GPS fix arrived in
        // the last GPS_FRESH_MS, ignore NETWORK entirely. NETWORK is only used before the first lock.
        // NOTE: With FusedLocationProviderClient (the primary path) the provider is "fused" — this
        // filter is then inert BY DESIGN: fused already blends GPS/network internally and hands us
        // one clean stream, so we accept every fused fix. This gate only bites on the raw-fallback
        // path. Downstream accuracy gating (reroute) uses `accuracy`, not provider, so it still works.
        val isGps = location.provider == LocationManager.GPS_PROVIDER
        if (isGps) lastGpsFixAtMs = nowMs
        val gpsFresh = lastGpsFixAtMs != 0L && (nowMs - lastGpsFixAtMs) <= GPS_FRESH_MS
        if (!isGps && gpsFresh) {
            com.recon.dash.util.NavLog.event("fix_drop", "prov=${location.provider} reason=gps_fresh")
            return
        }

        // ONE progress computation, shared by phone + dash via NavSessionManager.
        val bearing = if (location.hasBearing()) location.bearing else null
        val progress = navSessionManager.onLocationUpdate(
            location.latitude, location.longitude, speed, accuracy, bearing,
        ) ?: return

        // When ON-route, show the snapped point (rides the line). When OFF-route, show the RAW
        // GPS position + heading so the marker follows the rider away from the stale route
        // instead of freezing on the old line's nearest point (the "stuck" bug).
        if (progress.offRoute) {
            _riderPosition.value = rawPos
            if (location.hasBearing()) _riderBearing.value = location.bearing
        } else {
            _riderPosition.value = progress.snapped
            _riderBearing.value = progress.bearing.toFloat()
        }
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
            currentStreet = progress.currentStreet,
            arrived = progress.arrived,
            offRoute = progress.offRoute,
            speedAlertActive = alertActive,
            batterySaverOn = isBatterySaverOn(),
        )

        voiceManager?.maybeAnnounce(
            progress.nextManeuver,
            progress.distanceToManeuverM,
            progress.remainingMeters,
        )

        if (progress.arrived && !arrivedHandled) {
            // Reached the destination — end navigation once, save the ride, and let the screen
            // show the arrival summary. Guarded so it fires exactly once.
            arrivedHandled = true
            com.recon.dash.util.NavLog.event("arrived", "rem=${progress.remainingMeters.toInt()}")
            DebugLog.i(TAG) { "Arrived at destination — ending navigation" }
            onArrival()
        } else if (progress.offRoute) {
            // Reroute from the RAW GPS position (where the rider actually IS), not the snapped
            // point on the old route (which is where they left it).
            maybeReroute(rawPos, accuracy)
        }
    }

    @Volatile private var arrivedHandled = false
    @Volatile private var lastFixAtMs = 0L    // for logging inter-fix gaps (location-pipeline health)
    @Volatile private var lastGpsFixAtMs = 0L // last real GPS fix; gates out coarse NETWORK fixes

    // Diagnostic: log screen on/off so the next ride can prove whether the GPS dropouts line up
    // with screen-off (Android suspending location delivery) vs. happening screen-on too (which
    // would point at a different cause, e.g. a second location client). Registered during nav.
    private var screenReceiver: android.content.BroadcastReceiver? = null
    private fun registerScreenStateLogging() {
        if (screenReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                val evt = when (i?.action) {
                    android.content.Intent.ACTION_SCREEN_ON -> "screen_on"
                    android.content.Intent.ACTION_SCREEN_OFF -> "screen_off"
                    android.content.Intent.ACTION_USER_PRESENT -> "user_present"
                    else -> return
                }
                com.recon.dash.util.NavLog.event(evt)
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_ON)
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(r, filter)
        screenReceiver = r
    }
    private fun unregisterScreenStateLogging() {
        screenReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenReceiver = null
    }

    /**
     * Destination reached: capture the route summary, stop location updates + save the ride, and
     * LINGER on an arrival screen (map stays centred on the destination, summary card shown) rather
     * than tearing the whole nav view down instantly — that blank-out was the bad UX. The rider
     * dismisses via End Navigation; only then do we pop back (see [stopNavigation]).
     */
    private fun onArrival() {
        // Snapshot the ride stats BEFORE stopping the recorder, so the summary card has real numbers.
        val s = rideRecorder.stats.value
        val summary = RideSummary(
            distanceMeters = s.distanceMeters,
            durationSeconds = s.durationSeconds,
            avgSpeedKmh = s.avgSpeedKmh,
        )
        _navState.value = _navState.value.copy(arrived = true, summary = summary)

        stopLocationUpdates()
        // Release nav's hold on the keep-alive service (only actually stops it if the dash also
        // no longer needs it — see KeepAliveReasons).
        com.recon.dash.dash.DashKeepAliveService.stopFor(
            context, com.recon.dash.dash.DashKeepAliveService.REASON_NAV,
        )
        unregisterScreenStateLogging()
        divergenceTickJob?.cancel()
        divergenceTickJob = null
        voiceManager?.resetTrip()
        // Save on the app scope, NOT viewModelScope: the nav screen often finishes right after
        // arrival, cancelling viewModelScope before the DB insert runs and silently losing the ride.
        appScope.launch { rideRecorder.stop() }
        // Deliberately DO NOT call navSessionManager.stopNavigation() here — keeping the nav session
        // alive holds the route geometry + destination on-screen so the arrival view isn't blank.
        // stopNavigation() runs when the rider taps End Navigation (dismisses the arrival screen).
    }

    // ── Google divergence capture (debug-only tuning data) ──
    private var divergenceTickJob: Job? = null

    /** Fire a one-off divergence capture for [route] from [from]; swallows all failures. */
    private fun captureDivergence(route: Route, from: GeoPoint, ctx: String) {
        if (!divergenceCapture.enabled) return
        viewModelScope.launch {
            runCatching {
                divergenceCapture.capture(route, from, GeoPoint(destLat, destLng), ctx, System.currentTimeMillis())
            }.onFailure { DebugLog.w(TAG) { "Divergence capture ($ctx) threw: ${it.message}" } }
        }
    }

    /** Periodic mid-ride divergence tick: compare the CURRENT Valhalla route from the rider's
     *  live position to Google every PERIODIC_INTERVAL_MS. Debug-only; started with navigation. */
    private fun startDivergenceTicker() {
        if (!divergenceCapture.enabled) return
        divergenceTickJob?.cancel()
        divergenceTickJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(com.recon.dash.dash.nav.DivergenceCapture.PERIODIC_INTERVAL_MS)
                val current = route ?: continue
                val here = _riderPosition.value ?: getLastKnownLocation() ?: continue
                runCatching {
                    divergenceCapture.capture(current, here, GeoPoint(destLat, destLng), "periodic", System.currentTimeMillis())
                }.onFailure { DebugLog.w(TAG) { "Periodic divergence threw: ${it.message}" } }
            }
        }
    }

    // ── Debounced, single-flight reroute (fixes the reroute storm) ──
    @Volatile private var rerouteInFlight = false
    private var lastRerouteAtMs = 0L

    private fun maybeReroute(from: GeoPoint, accuracyM: Float) {
        val now = System.currentTimeMillis()
        when {
            rerouteInFlight ->
                com.recon.dash.util.NavLog.reroute(fired = false, reason = "inFlight")
            now - lastRerouteAtMs < MIN_REROUTE_INTERVAL_MS ->
                com.recon.dash.util.NavLog.reroute(fired = false, reason = "minInterval:${now - lastRerouteAtMs}ms")
            accuracyM > REROUTE_ACCURACY_GATE_M ->
                com.recon.dash.util.NavLog.reroute(fired = false, reason = "lowAccuracy:${accuracyM.toInt()}m")
            else -> {
                rerouteInFlight = true
                lastRerouteAtMs = now
                com.recon.dash.util.NavLog.reroute(fired = true, reason = "offRoute")
                viewModelScope.launch {
                    try {
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
            DebugLog.w(TAG) { "getLastKnownLocation: permission denied: ${e.message}" }
            null
        }
    }

    fun stopNavigation() {
        navTickJob?.cancel()
        divergenceTickJob?.cancel()
        divergenceTickJob = null
        stopLocationUpdates()
        // Release nav's hold on the keep-alive service (only actually stops it if the dash also
        // no longer needs it — see KeepAliveReasons).
        com.recon.dash.dash.DashKeepAliveService.stopFor(
            context, com.recon.dash.dash.DashKeepAliveService.REASON_NAV,
        )
        unregisterScreenStateLogging()
        voiceManager?.resetTrip()
        navSessionManager.stopNavigation()
        // App scope, not viewModelScope: stopNavigation() is often called from onCleared(), where
        // viewModelScope is already cancelled — the ride save must outlive the ViewModel.
        appScope.launch { rideRecorder.stop() }
        // Router is a process-lifetime singleton now; do NOT release it here.
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
