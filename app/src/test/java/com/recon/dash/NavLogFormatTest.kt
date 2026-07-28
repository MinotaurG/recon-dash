package com.recon.dash

import com.recon.dash.util.NavLog
import org.junit.Assert.*
import org.junit.Test

/** Locks the greppable NavLog line format so log-parsing/tooling stays stable. */
class NavLogFormatTest {

    @Test
    fun `fix line has the expected keys and prefix`() {
        val line = NavLog.fixLine(
            lat = 17.385044, lng = 78.486671, accM = 8f, snapM = 12.4, cumM = 1234.6,
            remM = 5000.0, dManM = 150.2, maneuver = "Turn left", offRoute = false,
            arrived = false, speedMps = 11.3f,
        )
        assertTrue(line.startsWith("NAVFIX "))
        assertTrue(line.contains("lat=17.385044"))
        assertTrue(line.contains("lng=78.486671"))
        assertTrue(line.contains("acc=8"))
        assertTrue(line.contains("snap=12"))
        assertTrue(line.contains("cum=1235"))       // rounded
        assertTrue(line.contains("dman=150"))
        assertTrue(line.contains("man=Turn left"))
        assertTrue(line.contains("off=false"))
        assertTrue(line.contains("arr=false"))
        assertTrue(line.contains("v=11.3"))
    }

    @Test
    fun `fix line renders null maneuver as dash and infinite accuracy safely`() {
        val line = NavLog.fixLine(0.0, 0.0, Float.MAX_VALUE, 0.0, 0.0, 0.0, 0.0, null, false, false, 0f)
        assertTrue(line.contains("man=-"))
        // No crash / no NaN garbage; finite floats format cleanly.
        assertFalse(line.contains("NaN"))
    }

    @Test
    fun `reroute line encodes fired and reason`() {
        assertEquals("NAVRRT fired=false reason=minInterval:2000ms",
            NavLog.rerouteLine(fired = false, reason = "minInterval:2000ms"))
        assertEquals("NAVRRT fired=true reason=offRoute",
            NavLog.rerouteLine(fired = true, reason = "offRoute"))
    }

    @Test
    fun `route line encodes source distance and maneuver count`() {
        val line = NavLog.routeLine("valhalla/osrm", 13800.0, 13, reroute = false)
        assertEquals("NAVROUTE src=valhalla/osrm m=13800 man=13 reroute=false", line)
    }

    @Test
    fun `event line optional detail`() {
        assertEquals("NAVEVT evt=nav_stop", NavLog.eventLine("nav_stop"))
        assertEquals("NAVEVT evt=nav_start dest=Home", NavLog.eventLine("nav_start", "dest=Home"))
    }

    @Test
    fun `divergence skip line carries reason and no metrics`() {
        assertEquals("NAVDIV ctx=plan outcome=skip reason=noApiKey",
            NavLog.divergenceLine("plan", outcome = "skip", reason = "noApiKey"))
    }

    @Test
    fun `divergence captured line carries metrics`() {
        val line = NavLog.divergenceLine(
            "periodic", outcome = "captured",
            overlapPct = 0.87, deltaMeters = -320.0, deltaSeconds = 45.0,
            valhallaMeters = 13800.0, googleMeters = 14120.0,
        )
        assertTrue(line.startsWith("NAVDIV "))
        assertTrue(line.contains("ctx=periodic"))
        assertTrue(line.contains("outcome=captured"))
        assertTrue(line.contains("overlap=87"))
        assertTrue(line.contains("dM=-320"))
        assertTrue(line.contains("dS=45"))
        assertTrue(line.contains("vM=13800"))
        assertTrue(line.contains("gM=14120"))
    }
}
