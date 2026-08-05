package com.recon.dash.dash.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the verified dash glyph codes (captures/2026-08-05-bench-ownapp/SPEC.md) so the
 * type -> glyph mapping can't silently regress. Codes anchored via the active glyph probe on
 * fw 11.63. Note continue == 0x09 (NOT 0x0B; 0x0B is roundabout-exit-1).
 */
class ManeuverDashCodeTest {

    private fun m(type: ManeuverType, exit: Int = 0) = Maneuver(
        type = type, instruction = "", location = GeoPoint(0.0, 0.0),
        cumulativeMeters = 0.0, roundaboutExitCount = exit,
    )

    @Test fun continueAndDepartAreStraight() {
        assertEquals(0x09, m(ManeuverType.CONTINUE).dashCode)
        assertEquals(0x09, m(ManeuverType.DEPART).dashCode)
    }

    @Test fun turns() {
        assertEquals(0x14, m(ManeuverType.TURN_RIGHT).dashCode)
        assertEquals(0x14, m(ManeuverType.SHARP_RIGHT).dashCode)
        assertEquals(0x18, m(ManeuverType.TURN_LEFT).dashCode)
        assertEquals(0x18, m(ManeuverType.SHARP_LEFT).dashCode)
        assertEquals(0x27, m(ManeuverType.SLIGHT_RIGHT).dashCode)
        assertEquals(0x16, m(ManeuverType.SLIGHT_LEFT).dashCode)
        assertEquals(0x1A, m(ManeuverType.UTURN).dashCode)
    }

    @Test fun roundaboutExitsMapTo0x0BThrough0x13() {
        // generic (unknown exit) = 0x0A; exit N = 0x0A + N
        assertEquals(0x0A, m(ManeuverType.ROUNDABOUT, exit = 0).dashCode)
        assertEquals(0x0B, m(ManeuverType.ROUNDABOUT, exit = 1).dashCode)
        assertEquals(0x0F, m(ManeuverType.ROUNDABOUT, exit = 5).dashCode)
        assertEquals(0x13, m(ManeuverType.ROUNDABOUT, exit = 9).dashCode)
        // out-of-range exit falls back to generic roundabout, never a wrong glyph
        assertEquals(0x0A, m(ManeuverType.ROUNDABOUT, exit = 12).dashCode)
    }
}
