package com.recon.dash.util

/**
 * Structured, greppable navigation logging. Every line is `PREFIX key=value key=value …` so a
 * single `adb logcat -s NavLog` reconstructs a whole ride and can be parsed/plotted.
 *
 * Prefixes:
 *   NAVFIX   — one per GPS fix (position, snap, distances, off-route/arrival state)
 *   NAVROUTE — a route was computed / rerouted
 *   NAVRRT   — a reroute decision (fired or suppressed, with reason)
 *   NAVEVT   — lifecycle events (nav started/stopped, arrival)
 *
 * The formatting is pure (see [fixLine] etc.) so it's unit-testable; the emit methods just log.
 */
object NavLog {
    const val TAG = "NavLog"

    // ── Pure formatters (unit-tested in NavLogFormatTest) ──

    fun fixLine(
        lat: Double, lng: Double, accM: Float, snapM: Double, cumM: Double,
        remM: Double, dManM: Double, maneuver: String?, offRoute: Boolean,
        arrived: Boolean, speedMps: Float,
    ): String = "NAVFIX " + kv(
        "lat" to fmt6(lat), "lng" to fmt6(lng), "acc" to fmt0(accM.toDouble()),
        "snap" to fmt0(snapM), "cum" to fmt0(cumM), "rem" to fmt0(remM),
        "dman" to fmt0(dManM), "man" to (maneuver ?: "-"),
        "off" to offRoute.toString(), "arr" to arrived.toString(),
        "v" to fmt1(speedMps.toDouble()),
    )

    fun routeLine(source: String, meters: Double, maneuvers: Int, reroute: Boolean): String =
        "NAVROUTE " + kv(
            "src" to source, "m" to fmt0(meters), "man" to maneuvers.toString(),
            "reroute" to reroute.toString(),
        )

    fun rerouteLine(fired: Boolean, reason: String): String =
        "NAVRRT " + kv("fired" to fired.toString(), "reason" to reason)

    fun eventLine(event: String, detail: String = ""): String =
        "NAVEVT " + kv("evt" to event) + if (detail.isNotBlank()) " $detail" else ""

    // ── Emit helpers (DEBUG-gated via DebugLog) ──

    fun fix(
        lat: Double, lng: Double, accM: Float, snapM: Double, cumM: Double,
        remM: Double, dManM: Double, maneuver: String?, offRoute: Boolean,
        arrived: Boolean, speedMps: Float,
    ) = DebugLog.d(TAG) {
        fixLine(lat, lng, accM, snapM, cumM, remM, dManM, maneuver, offRoute, arrived, speedMps)
    }

    fun route(source: String, meters: Double, maneuvers: Int, reroute: Boolean) =
        DebugLog.i(TAG) { routeLine(source, meters, maneuvers, reroute) }

    fun reroute(fired: Boolean, reason: String) =
        DebugLog.i(TAG) { rerouteLine(fired, reason) }

    fun event(event: String, detail: String = "") =
        DebugLog.i(TAG) { eventLine(event, detail) }

    // ── formatting internals ──
    private fun kv(vararg pairs: Pair<String, String>) = pairs.joinToString(" ") { "${it.first}=${it.second}" }
    private fun fmt0(v: Double) = if (v.isFinite()) "%.0f".format(v) else "inf"
    private fun fmt1(v: Double) = if (v.isFinite()) "%.1f".format(v) else "inf"
    private fun fmt6(v: Double) = "%.6f".format(v)
}
