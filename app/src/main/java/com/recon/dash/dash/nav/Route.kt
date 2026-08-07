package com.recon.dash.dash.nav

/** Maneuver glyphs the dash understands. Glyph byte codes were verified on fw 11.63 via the
 *  active probe (see captures/2026-08-05-bench-ownapp/SPEC.md); [Maneuver.dashCode] maps each
 *  type to its verified code and falls back to continue (0x09) for anything unmapped. */
enum class ManeuverType { CONTINUE, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, DEPART, ARRIVE,
    // Fork / keep / ramp maneuvers — distinct from a plain slight turn. The dash has dedicated
    // fork/keep glyphs (SPEC 0x1F/0x20), so these must NOT collapse to SLIGHT_* (that showed a
    // slight-turn arrow for a "keep left"). See captures/2026-08-05-bench-ownapp/SPEC.md.
    KEEP_LEFT, KEEP_RIGHT;

    companion object {
        /** Map an OSRM step maneuver (type + modifier) to our enum. */
        fun fromOsrm(type: String?, modifier: String?): ManeuverType = when (type) {
            "depart"   -> DEPART
            "arrive"   -> ARRIVE
            "roundabout", "rotary" -> ROUNDABOUT
            // Fork / merge / ramp are "keep" maneuvers (bear left/right onto a branch), NOT slight
            // turns — give them the dash's dedicated keep glyph so they don't look like a turn.
            "fork", "merge", "on ramp", "off ramp" ->
                when (modifier) {
                    "left", "slight left", "sharp left"    -> KEEP_LEFT
                    "right", "slight right", "sharp right" -> KEEP_RIGHT
                    else                                    -> CONTINUE
                }
            "end of road", "turn", "new name", "continue" ->
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
    /**
     * Dash maneuver glyph byte. Codes VERIFIED on fw 11.63 via the active glyph probe
     * (see captures/2026-08-05-bench-ownapp/SPEC.md — anchored code->glyph capture):
     *   0x09 continue · 0x0A roundabout(generic) · 0x0B..0x13 roundabout exits 1..9
     *   0x14 sharp/turn-right · 0x18 turn-left · 0x27 slight-right · (slight-left -> outline 0x16)
     * The straight-up "continue" arrow is 0x09 (NOT 0x0B as previously assumed; 0x0B is
     * roundabout-exit-1). Unmapped types fall back to 0x09 so the dash never shows a wrong turn.
     */
    val dashCode: Int get() = when (type) {
        ManeuverType.CONTINUE, ManeuverType.DEPART -> 0x09
        ManeuverType.ARRIVE       -> 0x09  // no distinct arrive glyph verified; keep straight
        // Sharp and normal turns share the "near turn" glyph per SPEC (0x14 = sharp/turn-right
        // near, 0x18 = turn-left near) — the dash has no distinct sharp variant.
        ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT -> 0x14
        ManeuverType.TURN_LEFT,  ManeuverType.SHARP_LEFT  -> 0x18
        // Slight turns: 0x27 = verified filled slight-right (near). There is NO verified
        // slight-left-near glyph in the catalog; 0x16 is turn-left-FAR (outline, hooked) which
        // reads as a full turn, not a slight — so a slight left uses the near turn-left (0x18),
        // which is closer to the truth than a far-outline glyph. (Revisit if a recapture finds a
        // dedicated slight-left.)
        ManeuverType.SLIGHT_RIGHT -> 0x27
        ManeuverType.SLIGHT_LEFT  -> 0x18
        // Keep/fork/ramp: dedicated fork glyphs, NOT slight-turn arrows (0x1F/0x20 = keep near).
        ManeuverType.KEEP_LEFT    -> 0x1F
        ManeuverType.KEEP_RIGHT   -> 0x20
        ManeuverType.UTURN        -> 0x1A
        ManeuverType.ROUNDABOUT   ->
            // 0x0B is exit 1 ... 0x13 is exit 9; generic roundabout (unknown exit) = 0x0A.
            if (roundaboutExitCount in 1..9) 0x0A + roundaboutExitCount else 0x0A
    }
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
