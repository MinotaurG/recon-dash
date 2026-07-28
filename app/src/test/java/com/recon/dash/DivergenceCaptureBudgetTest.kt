package com.recon.dash

import com.recon.dash.dash.nav.DivergenceCapture
import org.junit.Assert.*
import org.junit.Test

/** The month-key budget logic must be deterministic and roll over on calendar month. */
class DivergenceCaptureBudgetTest {

    @Test
    fun `month key is UTC year-month`() {
        // 2026-07-27T00:00:00Z = 1785456000000 ms
        assertEquals("2026-07", DivergenceCapture.monthKey(1785456000000L))
    }

    @Test
    fun `different months produce different keys`() {
        val july = DivergenceCapture.monthKey(1785456000000L)          // 2026-07-27
        val august = DivergenceCapture.monthKey(1785456000000L + 10L * 86_400_000L) // +10 days -> Aug
        assertNotEquals(july, august)
        assertEquals("2026-08", august)
    }

    @Test
    fun `cap is under the free tier`() {
        assertTrue("cap must stay below the 1000/mo free tier", DivergenceCapture.MONTHLY_CAP < 1000)
    }
}
