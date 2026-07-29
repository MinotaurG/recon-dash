package com.recon.dash

import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.dash.nav.ValhallaTripParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Proves the distance-axis fix: a maneuver's [cumulativeMeters] must equal the haversine
 * cumulative distance at its `begin_shape_index` — the SAME axis NavEngine snaps on — NOT a
 * running sum of Valhalla's `length` field (the old bug that made turn-by-turn distances wrong).
 */
class RouterParseTripTest {

    // Precision-6 encoded shape for (17.400,78.400)->(17.401,78.400)->(17.402,78.400).
    private val shape = "_k_e`@__dptCo}@?o}@?"

    private fun trip(): String = """
    { "trip": { "legs": [ {
        "shape": "$shape",
        "maneuvers": [
          { "type": 1, "instruction": "Depart", "begin_shape_index": 0, "length": 0.0 },
          { "type": 15, "verbal_pre_transition_instruction": "Turn left", "begin_shape_index": 1, "length": 0.111, "street_names": ["ISB Road", "NH-65"] },
          { "type": 4, "instruction": "Arrive", "begin_shape_index": 2, "length": 0.0 }
        ]
      } ],
      "summary": { "length": 0.222, "time": 30.0 }
    } }
    """.trimIndent()

    @Test
    fun `maneuver cumulativeMeters sits on the haversine axis at its shape index`() {
        val route = ValhallaTripParser.parse(trip()).first()
        assertEquals(3, route.geometry.size)

        // The "Turn left" maneuver is at begin_shape_index=1.
        val turn = route.maneuvers.first { it.instruction == "Turn left" }
        val expected = route.cumulative[1]  // haversine distance to vertex 1
        assertEquals(
            "maneuver distance must equal haversine cumulative at its shape index",
            expected, turn.cumulativeMeters, 0.5,
        )
        // Sanity: ~111 m for 0.001 deg latitude.
        assertEquals(111.0, turn.cumulativeMeters, 3.0)
    }

    @Test
    fun `distance-to-turn from a mid-route position matches hand-computed haversine`() {
        val route = ValhallaTripParser.parse(trip()).first()
        val turn = route.maneuvers.first { it.instruction == "Turn left" }
        // Rider sitting exactly at the start; distance to the turn = cumulative[1].
        val handComputed = GeoPoint.distMeters(route.geometry[0], route.geometry[1])
        assertEquals(handComputed, turn.cumulativeMeters, 0.5)
    }

    @Test
    fun `totalMeters uses the haversine axis not Valhalla summary length`() {
        val route = ValhallaTripParser.parse(trip()).first()
        assertEquals(route.cumulative.last(), route.totalMeters, 0.001)
    }

    @Test
    fun `maneuver type enum maps left turn correctly`() {
        val route = ValhallaTripParser.parse(trip()).first()
        assertEquals(
            com.recon.dash.dash.nav.ManeuverType.TURN_LEFT,
            route.maneuvers.first { it.instruction == "Turn left" }.type,
        )
    }

    @Test
    fun `street_names parses the first name onto the maneuver`() {
        val route = ValhallaTripParser.parse(trip()).first()
        val turn = route.maneuvers.first { it.instruction == "Turn left" }
        assertEquals("ISB Road", turn.streetName)
        // Maneuvers with no street_names stay empty (not null / not crashing).
        assertEquals("", route.maneuvers.first { it.instruction == "Depart" }.streetName)
    }

    @Test
    fun `NavEngine surfaces the current street after passing a named maneuver`() {
        val route = ValhallaTripParser.parse(trip()).first()
        val eng = com.recon.dash.dash.nav.NavEngine(route)
        // Sit just past the turn (vertex 1) so the "ISB Road" maneuver is now behind us.
        val p = eng.update(route.geometry[1], speedMps = 10f, accuracyM = 5f)
        assertEquals("ISB Road", p.currentStreet)
    }
}
