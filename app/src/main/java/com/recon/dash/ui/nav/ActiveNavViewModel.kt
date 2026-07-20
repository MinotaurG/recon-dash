package com.recon.dash.ui.nav

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

@HiltViewModel
class ActiveNavViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val navSessionManager: NavSessionManager,
    private val rideRecorder: RideRecorder,
) : ViewModel() {

    companion object {
        private const val TAG = "ActiveNavVM"
    }

    private val _navState = MutableStateFlow(NavDisplayState())
    val navState = _navState.asStateFlow()

    private val _dashStatus = MutableStateFlow("Disconnected")
    val dashStatus = _dashStatus.asStateFlow()

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
        startNavigation()
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
                rideRecorder.start(destName, origin.lat, origin.lng)
            } else {
                DebugLog.w(TAG) { "No GPS fix yet — will start recording on first fix" }
            }

            startLocationUpdates()
        }
    }

    private suspend fun computeRoute(from: GeoPoint) {
        val to = GeoPoint(destLat, destLng)
        when (val result = router.route(from, to)) {
            is RouterResult.Success -> {
                route = result.route
                voiceManager = VoiceManager.get(context)
                voiceManager?.resetTrip()
                navSessionManager.startNavigation(result.route, destName)
                DebugLog.i(TAG) { "Route computed — ${result.route.totalMeters.toInt()}m, ${result.route.maneuvers.size} maneuvers" }
            }
            is RouterResult.Failure -> {
                DebugLog.w(TAG) { "Route failed: ${result.error}" }
            }
        }
    }

    private fun startLocationUpdates() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { location -> onLocationUpdate(location) }
        locationListener = listener

        try {
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                5f,
                listener,
                Looper.getMainLooper(),
            )
        } catch (e: SecurityException) {
            DebugLog.w(TAG) { "Location permission not granted: ${e.message}" }
        }
    }

    private fun onLocationUpdate(location: Location) {
        if (!rideRecorder.isRecording.value && route != null) {
            viewModelScope.launch {
                rideRecorder.start(destName, location.latitude, location.longitude)
            }
        }
        rideRecorder.addPoint(location)

        val currentRoute = route ?: return
        val pos = GeoPoint(location.latitude, location.longitude)
        val speed = location.speed

        navSessionManager.updatePosition(location.latitude, location.longitude, speed)

        val progress = NavEngine.progress(currentRoute, pos, speed)

        _navState.value = NavDisplayState(
            etaText = formatEta(progress.etaSeconds),
            remainingText = formatDistance(progress.remainingMeters),
            distToTurnText = formatDistance(progress.distanceToManeuverM),
            nextInstruction = progress.nextManeuver?.instruction,
            destinationName = destName,
            arrived = progress.arrived,
            offRoute = progress.offRoute,
        )

        voiceManager?.maybeAnnounce(
            progress.nextManeuver,
            progress.distanceToManeuverM,
            progress.remainingMeters,
        )

        if (progress.offRoute) {
            viewModelScope.launch { reroute(pos) }
        }
    }

    private suspend fun reroute(from: GeoPoint) {
        DebugLog.i(TAG) { "Off-route — recalculating" }
        computeRoute(from)
        route?.let { navSessionManager.updateRoute(it) }
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
