package com.recon.dash

import com.recon.dash.dash.KeepAliveReasons
import org.junit.Assert.*
import org.junit.Test

/**
 * The keep-alive service (wakelock + foreground location) must stay up while EITHER the dash OR
 * navigation needs it, and only stop when NEITHER does. This is the exact logic whose absence
 * caused GPS to freeze mid-ride: the dash link flapped, stopped the service, and location died
 * even though nav was still active.
 */
class KeepAliveReasonsTest {

    private val DASH = "dash"
    private val NAV = "nav"

    @Test
    fun `service needed while any reason active`() {
        val r = KeepAliveReasons()
        assertFalse("idle at start", r.anyActive())
        r.add(NAV)
        assertTrue("nav needs it", r.anyActive())
    }

    @Test
    fun `dash stopping does NOT release the service while nav still needs it (the bug)`() {
        val r = KeepAliveReasons()
        r.add(DASH)
        r.add(NAV)
        // Dash link drops mid-ride:
        r.remove(DASH)
        assertTrue("nav still needs the service -> must stay up", r.anyActive())
    }

    @Test
    fun `service only stops when the last reason is released`() {
        val r = KeepAliveReasons()
        r.add(DASH); r.add(NAV)
        r.remove(NAV)
        assertTrue("dash still needs it", r.anyActive())
        r.remove(DASH)
        assertFalse("nobody needs it now -> stop", r.anyActive())
    }

    @Test
    fun `adding the same reason twice is idempotent`() {
        val r = KeepAliveReasons()
        r.add(NAV); r.add(NAV)
        r.remove(NAV)
        assertFalse("one remove clears a single logical reason", r.anyActive())
    }

    @Test
    fun `removing an unknown reason is harmless`() {
        val r = KeepAliveReasons()
        r.add(DASH)
        r.remove("bogus")
        assertTrue(r.anyActive())
        assertEquals(setOf(DASH), r.snapshot())
    }
}
