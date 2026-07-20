package com.recon.dash.dash

import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.dash.nav.ManeuverType
import com.recon.dash.dash.nav.NavEngine
import com.recon.dash.dash.nav.Route
import com.recon.dash.dash.protocol.DashCommands
import com.recon.dash.media.MediaSessionListener
import com.recon.dash.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bridges navigation progress and media state into [DashSession] commands.
 *
 * Responsibilities:
 * - Pushes live nav data (maneuver, distance, ETA) into the session's updateNavInfo
 * - Pushes now-playing (title, artist, album) into the session's updateNowPlaying
 * - Updates the route card destination name
 * - Manages the dash's nav chrome state (enable/disable based on active route)
 *
 * Does NOT own the session or media listener — the ViewModel does.
 */
class NavDashBridge(
    private val session: DashSession,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "NavDashBridge"
    }

    private var mediaJob: Job? = null
    private var currentRoute: Route? = null

    fun startMediaForwarding() {
        mediaJob?.cancel()
        mediaJob = scope.launch {
            MediaSessionListener.nowPlaying.collectLatest { np ->
                if (np != null && np.isPlaying) {
                    session.updateNowPlaying(np.title, np.album, np.artist)
                } else {
                    session.updateNowPlaying(null, "", "")
                }
            }
        }
    }

    fun stopMediaForwarding() {
        mediaJob?.cancel()
        mediaJob = null
        session.updateNowPlaying(null, "", "")
    }

    fun startNavigation(route: Route, destinationName: String) {
        currentRoute = route
        session.updateRouteCard(destinationName)
        DebugLog.i(TAG) { "Nav started — $destinationName (${route.totalMeters.toInt()}m)" }
    }

    fun updatePosition(lat: Double, lng: Double, speedMps: Float) {
        val route = currentRoute ?: return
        val pos = GeoPoint(lat, lng)
        val progress = NavEngine.progress(route, pos, speedMps)

        val maneuverCode = mapManeuverToDashCode(progress.nextManeuver?.type)
        val distM = progress.distanceToManeuverM
        val remainM = progress.remainingMeters

        val (primaryDist, primaryUnit) = toDashDistUnit(distM)
        val (totalDist, totalUnit) = toDashDistUnit(remainM)

        val etaMinutes = (progress.etaSeconds / 60).toInt()
        val etaHHMM = "%02d%02d".format(etaMinutes / 60, etaMinutes % 60)

        session.updateNavInfo(
            maneuver = maneuverCode,
            primaryDist = primaryDist,
            primaryUnit = primaryUnit,
            totalDist = totalDist,
            totalUnit = totalUnit,
            etaHHMM = etaHHMM,
        )
    }

    fun stopNavigation() {
        currentRoute = null
        session.updateRouteCard("")
    }

    fun updateRoute(route: Route) {
        currentRoute = route
    }

    private fun mapManeuverToDashCode(type: ManeuverType?): Int = when (type) {
        ManeuverType.TURN_LEFT -> 0x01
        ManeuverType.TURN_RIGHT -> 0x02
        ManeuverType.SLIGHT_LEFT -> 0x03
        ManeuverType.SLIGHT_RIGHT -> 0x04
        ManeuverType.SHARP_LEFT -> 0x05
        ManeuverType.SHARP_RIGHT -> 0x06
        ManeuverType.UTURN -> 0x07
        ManeuverType.ROUNDABOUT -> 0x08
        ManeuverType.ARRIVE -> 0x09
        else -> DashCommands.NAV_MANEUVER_CONTINUE
    }

    private fun toDashDistUnit(meters: Double): Pair<Int, Int> {
        return if (meters >= 1000) {
            val kmTenths = (meters / 100.0).toInt()
            kmTenths to DashCommands.NAV_UNIT_KM_TENTHS
        } else {
            meters.toInt() to DashCommands.NAV_UNIT_METERS
        }
    }
}
