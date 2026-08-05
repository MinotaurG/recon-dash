package com.recon.dash.dash

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

    /**
     * Push the shared [NavProgress] snapshot to the dash's nav bubble. The progress is computed
     * once in [NavSessionManager]; the dash no longer runs its own NavEngine (kept both surfaces
     * consistent and fixed the duplicate-computation drift).
     */
    fun updateProgress(progress: NavProgress) {
        // Use the maneuver's own verified dash glyph code (see Maneuver.dashCode +
        // captures/2026-08-05-bench-ownapp/SPEC.md). It reads roundaboutExitCount so a
        // roundabout renders the correct exit-numbered glyph (0x0B..0x13 = exits 1..9).
        val maneuverCode = progress.nextManeuver?.dashCode ?: 0x09
        val (primaryDist, primaryUnit) = toDashDistUnit(progress.distanceToManeuverM)
        val (totalDist, totalUnit) = toDashDistUnit(progress.remainingMeters)

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

    private fun toDashDistUnit(meters: Double): Pair<Int, Int> {
        return if (meters >= 1000) {
            val kmTenths = (meters / 100.0).toInt()
            kmTenths to DashCommands.NAV_UNIT_KM_TENTHS
        } else {
            meters.toInt() to DashCommands.NAV_UNIT_METERS
        }
    }
}
