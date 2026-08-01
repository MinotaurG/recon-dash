package com.recon.dash.dash

/**
 * Tracks WHY the [DashKeepAliveService] (wakelock + foreground `location` type) must stay alive.
 *
 * The service keeps GPS delivering with the screen off. Two independent subsystems need it:
 *  - the dash stream ([DashKeepAliveService.REASON_DASH])
 *  - active navigation ([DashKeepAliveService.REASON_NAV])
 *
 * The service must stay up while ANY reason is active and only stop when NONE is. This pure class
 * holds that decision so it is unit-testable without Android (the bug it fixes: the dash link
 * flapping stopped the service and froze GPS mid-ride even though nav still needed it).
 *
 * Thread-safe: start/stop can arrive from different coroutines (dash RX loop vs. nav VM).
 */
class KeepAliveReasons {
    private val active = LinkedHashSet<String>()

    @Synchronized fun add(reason: String): Boolean = active.add(reason)

    @Synchronized fun remove(reason: String): Boolean = active.remove(reason)

    @Synchronized fun anyActive(): Boolean = active.isNotEmpty()

    @Synchronized fun snapshot(): Set<String> = active.toSet()
}
