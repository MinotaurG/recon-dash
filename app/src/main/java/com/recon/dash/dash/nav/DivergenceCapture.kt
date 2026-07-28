package com.recon.dash.dash.nav

import com.recon.dash.BuildConfig
import com.recon.dash.dash.DashConfig
import com.recon.dash.data.RouteDivergenceDao
import com.recon.dash.data.RouteDivergenceRecord
import com.recon.dash.util.DebugLog
import com.recon.dash.util.NavLog
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures Valhalla-vs-Google route divergences for offline costing analysis.
 *
 * Debug-only by design: [enabled] is false in release builds, so the shipped app NEVER calls the
 * Google Routes API. On a debug build it fires when a route is planned, on each reroute, and on a
 * periodic mid-ride tick (see ActiveNavViewModel), each time querying Google for the same
 * origin→destination, comparing to the active Valhalla route via [RouteComparator], and persisting
 * a [RouteDivergenceRecord]. Nothing here feeds the live navigation decision.
 *
 * Timestamps are passed in by the caller (the workflow/runtime), never read from the clock here,
 * so the pure comparison stays deterministic.
 */
@Singleton
class DivergenceCapture @Inject constructor(
    private val dao: RouteDivergenceDao,
    private val dashConfig: DashConfig,
) {
    companion object {
        private const val TAG = "DivergenceCapture"
        /** Minimum gap between periodic captures (matches the ActiveNavViewModel tick). */
        const val PERIODIC_INTERVAL_MS = 5 * 60_000L
        /** Hard monthly ceiling — stops well under the 1000/mo free tier so we never pay. */
        const val MONTHLY_CAP = 900

        /** "YYYY-MM" in UTC for [ms]; the budget key that auto-resets each calendar month. */
        fun monthKey(ms: Long): String {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
            return "%04d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        }
    }

    /** Only capture on debug builds with a configured Google key. */
    val enabled: Boolean get() = BuildConfig.DEBUG && GoogleRoutesClient.isConfigured

    /**
     * Query Google for [from]→[to], compare to [valhallaRoute], and persist the divergence.
     * No-op (returns null) when disabled or the Google call fails — capture must never disturb nav.
     *
     * @param context "plan" | "reroute" | "periodic".
     * @param nowMs   caller-supplied timestamp.
     */
    suspend fun capture(
        valhallaRoute: Route,
        from: GeoPoint,
        to: GeoPoint,
        context: String,
        nowMs: Long,
    ): RouteDivergence? {
        // Every early-return logs a distinct NAVDIV outcome so a "why didn't it capture?" is one grep.
        if (!BuildConfig.DEBUG) {
            NavLog.divergence(context, outcome = "skip", reason = "releaseBuild")
            return null
        }
        if (!GoogleRoutesClient.isConfigured) {
            NavLog.divergence(context, outcome = "skip", reason = "noApiKey")
            return null
        }
        // Hard monthly budget guard — never spend past the free tier.
        val mk = monthKey(nowMs)
        val used = dashConfig.googleRoutesCallsThisMonth(mk)
        if (used >= MONTHLY_CAP) {
            NavLog.divergence(context, outcome = "skip", reason = "monthlyCap:$used/$MONTHLY_CAP")
            DebugLog.w(TAG) { "Monthly Google Routes cap reached ($used/$MONTHLY_CAP) — skipping $context" }
            return null
        }
        // Count the call BEFORE issuing it: a failed request still consumes quota with Google.
        dashConfig.recordGoogleRoutesCall(mk)
        NavLog.divergence(context, outcome = "attempt", reason = "calling_google[${used + 1}/$MONTHLY_CAP]")
        DebugLog.d(TAG) { "capture ctx=$context from=(${from.lat},${from.lng}) to=(${to.lat},${to.lng})" }

        val result = GoogleRoutesClient.route(from, to)
        val googleRoute = (result as? RouterResult.Success)?.route ?: run {
            val why = (result as? RouterResult.Failure)?.error?.let { it::class.simpleName } ?: "unknown"
            NavLog.divergence(context, outcome = "no_google", reason = why)
            DebugLog.w(TAG) { "No Google route for $context capture — $why" }
            return null
        }
        if (googleRoute.geometry.size < 2) {
            NavLog.divergence(context, outcome = "no_google", reason = "emptyGeometry")
            return null
        }

        val div = RouteComparator.compare(valhallaRoute, googleRoute)
        NavLog.divergence(
            context, outcome = "captured",
            overlapPct = div.overlapPct, deltaMeters = div.deltaMeters, deltaSeconds = div.deltaSeconds,
            valhallaMeters = div.valhallaMeters, googleMeters = div.googleMeters,
        )
        DebugLog.i(TAG) {
            "DIVERGENCE ctx=$context overlap=${"%.0f".format(div.overlapPct * 100)}% " +
                "dM=${div.deltaMeters.toInt()} dS=${div.deltaSeconds.toInt()} " +
                "(V=${div.valhallaMeters.toInt()}m G=${div.googleMeters.toInt()}m)"
        }
        runCatching {
            val id = dao.insert(
                RouteDivergenceRecord(
                    timestamp = nowMs,
                    context = context,
                    originLat = from.lat,
                    originLng = from.lng,
                    destLat = to.lat,
                    destLng = to.lng,
                    overlapPct = div.overlapPct,
                    valhallaMeters = div.valhallaMeters,
                    googleMeters = div.googleMeters,
                    valhallaSeconds = div.valhallaSeconds,
                    googleSeconds = div.googleSeconds,
                    valhallaPolyline = PolylineCodec.encode(valhallaRoute.geometry),
                    googlePolyline = PolylineCodec.encode(googleRoute.geometry),
                    appVersion = BuildConfig.VERSION_NAME,
                )
            )
            DebugLog.d(TAG) { "persisted divergence row id=$id" }
        }.onFailure {
            NavLog.divergence(context, outcome = "error", reason = "persist:${it.message}")
            DebugLog.w(TAG) { "Persist divergence failed: ${it.message}" }
        }
        return div
    }
}
