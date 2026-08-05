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
 *   NAVDIV   — a Google-vs-Valhalla divergence capture (attempt, skip-reason, or result)
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
        // Ground-truth for glyph verification: the maneuver TYPE and the exact glyph BYTE the dash
        // was sent this fix. Lets a ride log be checked directly against the SPEC.md code->glyph map
        // (previously NAVFIX had only the instruction text, so wrong glyphs had to be inferred).
        maneuverType: String? = null, glyphCode: Int? = null, exitCount: Int? = null,
    ): String = "NAVFIX " + kv(
        "lat" to fmt6(lat), "lng" to fmt6(lng), "acc" to fmt0(accM.toDouble()),
        "snap" to fmt0(snapM), "cum" to fmt0(cumM), "rem" to fmt0(remM),
        "dman" to fmt0(dManM),
        "mtype" to (maneuverType ?: "-"),
        "glyph" to (glyphCode?.let { "0x%02X".format(it) } ?: "-"),
        "exit" to (exitCount?.toString() ?: "-"),
        "man" to (maneuver ?: "-"),
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

    /**
     * Divergence-capture trace. [outcome] is "captured" | "skip" | "no_google" | "error".
     * On a captured line the metrics are filled; otherwise [reason] explains why nothing was saved.
     */
    fun divergenceLine(
        ctx: String, outcome: String, reason: String = "",
        overlapPct: Double? = null, deltaMeters: Double? = null, deltaSeconds: Double? = null,
        valhallaMeters: Double? = null, googleMeters: Double? = null,
    ): String {
        val base = mutableListOf("ctx" to ctx, "outcome" to outcome)
        if (reason.isNotBlank()) base.add("reason" to reason)
        if (overlapPct != null) base.add("overlap" to fmt0(overlapPct * 100))
        if (deltaMeters != null) base.add("dM" to fmt0(deltaMeters))
        if (deltaSeconds != null) base.add("dS" to fmt0(deltaSeconds))
        if (valhallaMeters != null) base.add("vM" to fmt0(valhallaMeters))
        if (googleMeters != null) base.add("gM" to fmt0(googleMeters))
        return "NAVDIV " + kv(*base.toTypedArray())
    }

    // ── Emit helpers (DEBUG-gated via DebugLog) ──

    fun fix(
        lat: Double, lng: Double, accM: Float, snapM: Double, cumM: Double,
        remM: Double, dManM: Double, maneuver: String?, offRoute: Boolean,
        arrived: Boolean, speedMps: Float,
        maneuverType: String? = null, glyphCode: Int? = null, exitCount: Int? = null,
    ) = DebugLog.d(TAG) {
        fixLine(lat, lng, accM, snapM, cumM, remM, dManM, maneuver, offRoute, arrived, speedMps,
            maneuverType, glyphCode, exitCount)
    }

    fun route(source: String, meters: Double, maneuvers: Int, reroute: Boolean) =
        DebugLog.i(TAG) { routeLine(source, meters, maneuvers, reroute) }

    fun reroute(fired: Boolean, reason: String) =
        DebugLog.i(TAG) { rerouteLine(fired, reason) }

    fun event(event: String, detail: String = "") =
        DebugLog.i(TAG) { eventLine(event, detail) }

    fun divergence(
        ctx: String, outcome: String, reason: String = "",
        overlapPct: Double? = null, deltaMeters: Double? = null, deltaSeconds: Double? = null,
        valhallaMeters: Double? = null, googleMeters: Double? = null,
    ) = DebugLog.i(TAG) {
        divergenceLine(ctx, outcome, reason, overlapPct, deltaMeters, deltaSeconds, valhallaMeters, googleMeters)
    }

    // ── formatting internals ──
    private fun kv(vararg pairs: Pair<String, String>) = pairs.joinToString(" ") { "${it.first}=${it.second}" }
    private fun fmt0(v: Double) = if (v.isFinite()) "%.0f".format(v) else "inf"
    private fun fmt1(v: Double) = if (v.isFinite()) "%.1f".format(v) else "inf"
    private fun fmt6(v: Double) = "%.6f".format(v)
}
