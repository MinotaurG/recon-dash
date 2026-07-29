package com.recon.dash.dash.nav

/** Maneuver glyphs the dash understands. Only CONTINUE (0x0B) is hardware-verified;
 *  the rest are best-effort guesses and must be checked on fw 11.63. Until then
 *  [Maneuver.dashCode] falls back to CONTINUE so the dash never shows a wrong arrow. */
enum class ManeuverType { CONTINUE, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, DEPART, ARRIVE;

    companion object {
        /** Map an OSRM step maneuver (type + modifier) to our enum. */
        fun fromOsrm(type: String?, modifier: String?): ManeuverType = when (type) {
            "depart"   -> DEPART
            "arrive"   -> ARRIVE
            "roundabout", "rotary" -> ROUNDABOUT
            "fork", "end of road", "turn", "new name", "continue", "merge", "on ramp", "off ramp" ->
                when (modifier) {
                    "left"         -> TURN_LEFT
                    "right"        -> TURN_RIGHT
                    "slight left"  -> SLIGHT_LEFT
                    "slight right" -> SLIGHT_RIGHT
                    "sharp left"   -> SHARP_LEFT
                    "sharp right"  -> SHARP_RIGHT
                    "uturn"        -> UTURN
                    else           -> CONTINUE
                }
            else -> CONTINUE
        }
    }
}

/** One routing instruction located at a point along the geometry. */
data class Maneuver(
    val type: ManeuverType,
    val instruction: String,
    val location: GeoPoint,
    /**
     * Cumulative distance (m) from the route start to this maneuver's location, measured on the
     * SAME haversine polyline axis that [NavEngine] snaps onto. This is what makes
     * distance-to-turn (`cumulativeMeters - snappedCumulative`) correct.
     */
    val cumulativeMeters: Double,
    /** Roundabout exit number (Valhalla roundabout_exit_count); 0 when not a roundabout. */
    val roundaboutExitCount: Int = 0,
    /** Valhalla's own maneuver length (m); kept for logging/diagnostics only, not for distances. */
    val valhallaLengthM: Double = 0.0,
    /** Name of the road this maneuver travels along (Valhalla street_names); empty if unnamed. */
    val streetName: String = "",
) {
    /** Dash maneuver glyph byte. CONTINUE (0x0B) is the only verified value. */
    val dashCode: Int get() = 0x0B // TODO: verify other glyph codes on fw 11.63
}

/**
 * A computed road route from origin to destination.
 *
 * NOT a data class: it holds a [DoubleArray] ([cumulative]) whose structural equality is
 * identity-based, which broke `data class` equals/hashCode and corrupted StateFlow dedup.
 * Equality is instead based on a monotonic [routeId] assigned at creation, so each distinct
 * route generation (initial route, each reroute) is a distinct value for flow purposes.
 */
class Route(
    val geometry: List<GeoPoint>,
    val maneuvers: List<Maneuver>,
    val totalMeters: Double,
    val totalSeconds: Double,
    /** Cumulative distance (m) at each geometry vertex — same length as [geometry]. */
    val cumulative: DoubleArray,
    val routeId: Long = nextRouteId(),
) {
    val destination: GeoPoint? get() = geometry.lastOrNull()

    override fun equals(other: Any?): Boolean = other is Route && other.routeId == routeId
    override fun hashCode(): Int = routeId.hashCode()

    companion object {
        private val routeCounter = java.util.concurrent.atomic.AtomicLong(0L)
        fun nextRouteId(): Long = routeCounter.incrementAndGet()
    }
}
