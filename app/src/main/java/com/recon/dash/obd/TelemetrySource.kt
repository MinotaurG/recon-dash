package com.recon.dash.obd

import kotlinx.coroutines.flow.Flow

/**
 * A snapshot of live engine telemetry. Nullable fields = "this PID isn't available right now"
 * (a dongle may not report every PID, and a replay may have gaps) — the UI must handle nulls, not
 * assume zeros. Values are in display units (km/h, rpm, °C, %).
 *
 * Verified (docs/OBD_TELEMETRY_FINDINGS.md): the dash does NOT put engine data on WiFi, so this
 * ONLY comes from an OBD-II source — a live ELM327 dongle, or a replayed Car Scanner .brc.
 */
data class EngineState(
    val rpm: Int? = null,
    val speedKmh: Int? = null,
    val coolantC: Int? = null,
    val throttlePct: Int? = null,
    val fuelInstL100: Double? = null,   // instantaneous consumption, L/100km (null if unknown)
    val accelMs2: Double? = null,       // vehicle acceleration, m/s^2
    /** Wall-clock ms when this snapshot was produced (source-supplied; stamped by the source). */
    val timestampMs: Long = 0L,
) {
    companion object {
        val EMPTY = EngineState()
    }
}

/** Connection state of a telemetry source, surfaced so the UI can show connecting/error. */
enum class TelemetryStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * A source of live [EngineState]. Two implementations share this contract so the cluster UI (phone
 * + dash) is identical regardless of where the data comes from:
 *  - [Elm327Source]     — a real Bluetooth ELM327 dongle on the bike.
 *  - [BrcReplaySource]  — a decoded Car Scanner .brc replayed at real cadence (dev / no-bike).
 *
 * [state] emits at the source's natural rate (dongle poll rate, or the .brc sample cadence). The
 * cluster tweens between emissions for smooth needles, so a ~5-10 Hz source is plenty.
 */
interface TelemetrySource {
    val status: Flow<TelemetryStatus>
    val state: Flow<EngineState>

    /** Begin producing telemetry (connect the dongle / start the replay). Idempotent. */
    suspend fun start()

    /** Stop and release resources (close the socket / cancel the replay). Idempotent. */
    fun stop()
}
