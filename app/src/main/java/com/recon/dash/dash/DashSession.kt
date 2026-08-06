package com.recon.dash.dash

import com.recon.dash.dash.protocol.DashCommands
import com.recon.dash.dash.protocol.K1GPacket
import com.recon.dash.util.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashState { IDLE, CONNECTING, AUTHENTICATING, READY, STREAMING, ERROR }

/**
 * Tripper Dash session, sequenced to match better-dash (tripper_app_like_nav.py):
 *   1. Open sockets (RX :2002 bound first).
 *   2. Send initial burst on :2000 (includes q3c.e request-auth).
 *   3. RX loop ingests 07 00 / 07 03 → sends q3c.d → waits for 07 01 01.
 *   4. Nav entry: route-card ×4 → projectionFrame → z2 (once) → route-card.
 *   5. Start RTP + 4 Hz projection heartbeat + 1 Hz route-card keep-alive.
 * The RX loop runs the WHOLE time, answering auth, 09 06 IDR-decoded acks,
 * and 09 00 button events.
 */
class DashSession(
    private val scope: CoroutineScope,
    private val mode: DashMode = DashMode.DIGITAL,
    // When true, digital idle opens the projection window and shows the wallpaper (replaces the
    // dash's native RPM screen). When false, the dash keeps its own RPM screen when idle.
    private val projectWhenIdle: Boolean = false,
) {
    companion object {
        private const val TAG           = "DashSession"
        private const val AUTH_TIMEOUT  = 15_000L
        private const val BURST_PAUSE   = 20L
        private const val PROJ_HB_MS     = 250L   // 4 Hz
        private const val ROUTE_CARD_MS  = 1_000L // 1 Hz keep-alive
        private const val HOSTNAME       = "Recon Dash"
        // Default/idle destination label + sentinel for "no active nav". Shown on the dash.
        private const val DEFAULT_NAME   = "Recon Dash"
    }

    private val _state = MutableStateFlow(DashState.IDLE)
    val state = _state.asStateFlow()

    private var socket: DashSocket? = null
    private var auth: DashAuth? = null

    /** Gate the socket's sends on WiFi link state (avoids the failed-send storm when the link drops). */
    fun setLinkUp(up: Boolean) { socket?.setLinkUp(up) }
    @Volatile private var authConfirmed = false
    @Volatile private var authRetries = 0

    var onButton: ((Byte) -> Unit)? = null
    var onError:  ((String) -> Unit)? = null
    var onProtocolEvent: ((String) -> Unit)? = null
    var onTelemetry: ((TelemetryPacket) -> Unit)? = null

    @Volatile var destinationName: String = DEFAULT_NAME

    private var sessionJob: Job? = null
    private var rxJob: Job? = null
    private var projHbJob: Job? = null
    private var routeCardJob: Job? = null
    private var heartbeatJob: Job? = null
    private var navInfoJob: Job? = null
    private var mediaInfoJob: Job? = null

    @Volatile private var mediaTitle: String? = null
    @Volatile private var mediaAlbum = ""
    @Volatile private var mediaArtist = ""
    @Volatile private var callerName: String? = null

    fun updateNowPlaying(title: String?, album: String, artist: String) {
        mediaTitle = title?.takeIf { it.isNotBlank() }
        mediaAlbum = album
        mediaArtist = artist
    }

    fun updateCall(caller: String?) {
        callerName = caller?.takeIf { it.isNotBlank() }
    }

    /**
     * Send a raw packet on the control socket, off the session scope. ADDITIVE — used only by the
     * screen-focus probe (DashViewModel) to test candidate carousel-switch commands. Does not touch
     * auth/projection/route-card/RTP paths. No-op if not connected.
     */
    fun sendRaw(packet: ByteArray) {
        val sock = socket ?: return
        scope.launch(Dispatchers.IO) { runCatching { sock.send(packet) } }
    }

    // Live nav-info pushed to the dash bubble at ~1 Hz (set by NavEngine output).
    @Volatile private var navManeuver = DashCommands.NAV_MANEUVER_CONTINUE
    @Volatile private var navPrimaryDist = 0
    @Volatile private var navPrimaryUnit = DashCommands.NAV_UNIT_METERS
    @Volatile private var navTotalDist = 0
    @Volatile private var navTotalUnit = DashCommands.NAV_UNIT_METERS
    @Volatile private var navEta: String? = null
    @Volatile private var navActive = false
    @Volatile private var navChromeEnabled = false

    /** Push the latest turn-by-turn figures; sent to the dash at 1 Hz. */
    fun updateNavInfo(
        maneuver: Int, primaryDist: Int, primaryUnit: Int,
        totalDist: Int, totalUnit: Int, etaHHMM: String? = null,
    ) {
        navManeuver = maneuver
        navPrimaryDist = primaryDist
        navPrimaryUnit = primaryUnit
        navTotalDist = totalDist
        navTotalUnit = totalUnit
        navEta = etaHHMM
        navActive = true
        navChromeEnabled = true
    }

    /**
     * Route card with the LIVE nav figures patched in. The template's captured
     * values (7.9 km / glyph 0x3C / ETA 03:03) must never reach the dash once
     * real guidance is running — the card repeats at 1 Hz and would stomp the
     * activeNavPacket numbers every second.
     */
    private fun liveRouteCard(): ByteArray {
        val projection = mode == DashMode.DIGITAL
        return if (navActive) DashCommands.routeCard(
            destinationName, projection,
            maneuver = navManeuver,
            primaryUnit = navPrimaryUnit,
            totalDist = navTotalDist,
            totalUnit = navTotalUnit,
            etaHHMM = navEta,
        )
        else DashCommands.routeCard(destinationName, projection)
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun connect(ssid: String, network: android.net.Network? = null) {
        if (_state.value != DashState.IDLE && _state.value != DashState.ERROR) return
        DebugLog.i(TAG) { "connect() — ssid='$ssid' network=$network" }
        sessionJob = scope.launch(Dispatchers.IO) { runSession(ssid, network) }
    }

    fun startStreaming() {
        if (_state.value != DashState.READY) return
        _state.value = DashState.STREAMING
        if (mode == DashMode.DIGITAL) launchProjectionHeartbeat()
        launchRouteCardKeepAlive()
        launchNavInfo()
        launchMediaInfo()
    }

    fun sendRtp(packet: ByteArray) {
        if (mode == DashMode.DIGITAL) socket?.sendRtp(packet)
    }

    fun updateRouteCard(name: String) {
        val wasNavigating = navChromeEnabled
        destinationName = name.ifBlank { DEFAULT_NAME }
        navActive = false
        navChromeEnabled = destinationName != DEFAULT_NAME
        val live = _state.value == DashState.READY || _state.value == DashState.STREAMING
        if (!live) return

        when {
            // Navigation STARTING (idle -> nav): open the projection window and enter nav mode.
            // Because we no longer project when idle (dash stays on its native RPM screen),
            // nav must open projection itself — otherwise only turn-by-turn is sent and the
            // map never appears. This runs the proven nav-entry sequence.
            navChromeEnabled && !wasNavigating -> scope.launch(Dispatchers.IO) {
                socket?.let { s ->
                    if (mode == DashMode.DIGITAL) enterNavMode(s) else enterAnalogueNavMode(s)
                }
            }
            // Already navigating, destination refresh: just push an updated route card.
            navChromeEnabled -> scope.launch(Dispatchers.IO) {
                socket?.send(liveRouteCard())
            }
            // Navigation STOPPING (nav -> idle), digital mode:
            //  - projectWhenIdle ON  → keep projection open and fall back to the idle wallpaper.
            //  - projectWhenIdle OFF → close projection so the dash returns to its native RPM
            //    screen (instead of a frozen map).
            mode == DashMode.DIGITAL && wasNavigating -> scope.launch(Dispatchers.IO) {
                if (projectWhenIdle) {
                    socket?.let { enterIdleProjectionMode(it) }
                } else {
                    socket?.send(DashCommands.projectionStop())
                    delay(40)
                    socket?.send(DashCommands.projectionOff())
                }
            }
        }
    }

    fun disconnect() {
        // Cancel the session coroutine FIRST so it can't race past auth and flip state to
        // READY after we tear down (which would re-trigger streaming on a dead socket).
        sessionJob?.cancel(); sessionJob = null
        rxJob?.cancel(); projHbJob?.cancel(); routeCardJob?.cancel(); heartbeatJob?.cancel()
        navInfoJob?.cancel(); mediaInfoJob?.cancel()
        navActive = false
        navChromeEnabled = false
        socket?.let {
            if (mode == DashMode.DIGITAL) {
                runCatching { it.send(DashCommands.projectionStop()) }
                runCatching { it.send(DashCommands.projectionOff()) }
            }
            it.close()
        }
        socket = null
        _state.value = DashState.IDLE
        DebugLog.i(TAG) { "Disconnected" }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private suspend fun runSession(ssid: String, network: android.net.Network? = null) {
        try {
            _state.value = DashState.CONNECTING
            val sock = try {
                DashSocket(network).also { socket = it }
            } catch (e: java.net.BindException) {
                fail("Port ${DashSocket.RX_PORT}/${DashSocket.CTRL_PORT} in use (${e.message})")
                return
            }

            auth = DashAuth(ssid)
            authConfirmed = false
            authRetries = 0

            // RX loop MUST be running before the burst (early pubkey + no ICMP).
            launchReceiveLoop(sock)
            // 1 Hz status heartbeat throughout the session.
            launchStatusHeartbeat(sock)

            _state.value = DashState.AUTHENTICATING
            DebugLog.i(TAG) { "Sending initial burst…" }
            for (pkt in DashCommands.initialBurst(HOSTNAME)) {
                sock.send(pkt)
                delay(BURST_PAUSE)
            }

            DebugLog.i(TAG) { "Waiting up to ${AUTH_TIMEOUT}ms for auth (07 01 01)…" }
            val deadline = System.currentTimeMillis() + AUTH_TIMEOUT
            while (!authConfirmed && System.currentTimeMillis() < deadline) delay(100)

            if (!authConfirmed) {
                fail("Auth timed out — no 07 01 01 from dash. Check SSID matches '$ssid'.")
                return
            }
            DebugLog.i(TAG) { "Authenticated ✓" }

            when {
                mode == DashMode.ANALOGUE && navChromeEnabled -> enterAnalogueNavMode(sock)
                mode == DashMode.ANALOGUE -> {} // no projection, no nav chrome — heartbeat keeps session alive
                navChromeEnabled -> enterNavMode(sock)
                // Digital, no active navigation:
                //  - projectWhenIdle ON  → open the projection window to show the wallpaper
                //    (OpenDash behavior; replaces the dash's native RPM screen).
                //  - projectWhenIdle OFF → leave the dash on its native RPM screen; projection
                //    opens only once navigation actually starts (updateRouteCard → enterNavMode).
                projectWhenIdle -> enterIdleProjectionMode(sock)
                else -> {}
            }
            _state.value = DashState.READY
            DebugLog.i(TAG) { "READY ✓" }

        } catch (e: Exception) {
            DebugLog.e(TAG, { "Session error" }, e)
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Nav entry in the exact phone order (nav_open_ok.pcap):
     *   route-card ×4 (establishes destination) → projectionFrame
     *   → z2 once → route-card confirmation.
     */
    private suspend fun enterNavMode(sock: DashSocket) {
        sock.send(DashCommands.navContext()); delay(40)
        sock.send(DashCommands.emptyLists()); delay(40)

        repeat(4) {
            sock.send(DashCommands.routeCard(destinationName, projectionOn = false))
            delay(if (it < 1) 100 else 500)
        }
        sock.send(DashCommands.projectionFrame()); delay(60)
        sock.send(DashCommands.navPlaceholder()); delay(10)
        sock.send(DashCommands.navStart()); delay(40)                 // z2, ONCE
        sock.send(DashCommands.routeCard(destinationName, projectionOn = true))
        DebugLog.i(TAG) { "Nav mode kick sent" }
    }

    private suspend fun enterAnalogueNavMode(sock: DashSocket) {
        sock.send(DashCommands.navContext()); delay(40)
        sock.send(DashCommands.emptyLists()); delay(40)
        repeat(4) {
            sock.send(DashCommands.routeCard(destinationName, projectionOn = false))
            delay(if (it < 1) 100 else 500)
        }
        sock.send(DashCommands.navPlaceholder()); delay(10)
        sock.send(DashCommands.navStart()); delay(40)
        sock.send(DashCommands.routeCard(destinationName, projectionOn = false))
        DebugLog.i(TAG) { "Analogue nav mode kick sent" }
    }

    /**
     * Idle wallpaper mode: open projection without route-card/nav-start chrome.
     * Active navigation still uses [enterNavMode] unchanged.
     */
    private suspend fun enterIdleProjectionMode(sock: DashSocket) {
        sock.send(DashCommands.projectionFrame()); delay(60)
        sock.send(DashCommands.projectionOn()); delay(40)
        DebugLog.i(TAG) { "Idle projection kick sent" }
    }

    private fun launchReceiveLoop(sock: DashSocket) {
        rxJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pkt = try {
                    sock.receive()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Link dropped (EBADF/ENETUNREACH) — end the loop cleanly instead of
                    // crashing the app; DashWifiManager handles reconnect.
                    DebugLog.w(TAG) { "RX loop stopped — socket error: ${e.message}" }
                    onError?.invoke("Lost connection to dash")
                    break
                } ?: continue
                dispatchIncoming(pkt, sock)
            }
        }
    }

    private fun dispatchIncoming(pkt: ByteArray, sock: DashSocket) {
        val tlvs = K1GPacket.parseIncoming(pkt)
        // Dump the full raw packet for anything that ISN'T just the per-frame decode
        // acks (09 06 55 / 09 04 55) — those fire ~8×/s and would drown the log. This
        // captures joystick events, telemetry, and any unknown TLV in full hex so a
        // single `adb logcat -s DashSession` session is enough to reverse the protocol.
        val onlyAcks = tlvs.isNotEmpty() && tlvs.all {
            it.type == 0x09 && (it.sub == 0x06 || it.sub == 0x04) &&
                it.value.firstOrNull()?.toInt() == 0x55
        }
        if (!onlyAcks) DebugLog.i(TAG) { "RX RAW (${pkt.size}B): ${pkt.toHexFull()}" }
        for (tlv in tlvs) {
            // ── Auth (07 xx) ──
            if (tlv.type == 0x07) {
                when (val ev = auth?.ingest(tlv)) {
                    is AuthEvent.SendKey -> {
                        DebugLog.i(TAG) { "Got RSA pubkey — sending q3c.d" }
                        sock.send(ev.packet)
                    }
                    AuthEvent.Confirmed -> { authConfirmed = true }
                    AuthEvent.Rejected -> {
                        authRetries++
                        DebugLog.w(TAG) { "Auth rejected — retry #$authRetries" }
                        auth?.reset()
                        if (authRetries <= 5) sock.send(DashCommands.authRequest())
                    }
                    else -> {}
                }
                continue
            }
            // ── 09 06 55: per-IDR frame-decoded notify → mandatory q3c.L2 ──
            if (tlv.type == 0x09 && tlv.sub == 0x06 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                if (mode == DashMode.DIGITAL) sock.send(DashCommands.frameDecodedIdr())
                continue
            }
            // ── 09 04 55: P-frame decoded → q3c.K2 ──
            if (tlv.type == 0x09 && tlv.sub == 0x04 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                if (mode == DashMode.DIGITAL) sock.send(DashCommands.frameDecodedP())
                continue
            }
            // ── 09 00: button / joystick event → echo ack + notify UI ──
            if (tlv.type == 0x09 && tlv.sub == 0x00 && tlv.value.isNotEmpty()) {
                val btn = tlv.value.last()
                val msg = "JOYSTICK code=0x${(btn.toInt() and 0xFF).toString(16).uppercase()}"
                DebugLog.i(TAG) { "$msg  full=${tlv.value.toHexFull()}" }
                onProtocolEvent?.invoke(msg)
                sock.send(DashCommands.buttonAck(btn))
                scope.launch(Dispatchers.Main) { onButton?.invoke(btn) }
                continue
            }
            // ── 0F: vehicle-secure telemetry (AES-256-CBC under the session key,
            //    IV = first 16 bytes). This is the dash's instrument-cluster data
            //    (likely trip/odo/fuel/speed/temp). The better-dash reference only
            //    logs these as ciphertext — we actually DECRYPT with our session key
            //    and log the plaintext for field-mapping (P1b). It arrives over our
            //    own session, so plain `adb logcat -s DashSession` captures it — no
            //    root, no monitor mode. ──
            if (tlv.type == 0x0F) {
                val key = auth?.sessionKey
                val plain = key?.let { aesDecryptCbc(tlv.value, it) }
                DebugLog.i(TAG) { "DASH TELEMETRY 0F sub=0x%02X enc(%dB)=%s  dec=%s".format(
                    tlv.sub, tlv.value.size, tlv.value.toHexFull(),
                    plain?.toHexFull() ?: "<key=${key != null}; decrypt failed>") }
                val pkt0F = TelemetryPacket(
                    timestampMs = System.currentTimeMillis(),
                    type = 0x0F,
                    sub = tlv.sub,
                    raw = tlv.value,
                    decrypted = plain,
                )
                onTelemetry?.invoke(pkt0F)
                TelemetryBus.emit(pkt0F)
                continue
            }
            // ── 0C xx: dash → app telemetry (trip/odo/fuel/temp — P1b) ──
            if (tlv.type == 0x0C) {
                val msg = "TELEMETRY 0C sub=0x%02X (%dB)".format(tlv.sub, tlv.value.size)
                DebugLog.i(TAG) { "$msg val=${tlv.value.toHexFull()}" }
                onProtocolEvent?.invoke(msg)
                val pkt0C = TelemetryPacket(
                    timestampMs = System.currentTimeMillis(),
                    type = 0x0C,
                    sub = tlv.sub,
                    raw = tlv.value,
                    decrypted = tlv.value,
                )
                onTelemetry?.invoke(pkt0C)
                TelemetryBus.emit(pkt0C)
                continue
            }
            // Log every OTHER incoming event in FULL so its TLV can be identified + mapped.
            // SCREENEVT: any dash->app event that ISN'T a known ack/telemetry/auth/joystick is a
            // candidate "the active carousel screen changed" announcement. We tag it distinctly so
            // that, while joysticking Home->Phone->Media->Nav, one `grep SCREENEVT` shows whether
            // the dash tells us its current screen (needed to auto-focus Nav/Phone/Media cards).
            DebugLog.i(TAG) { "SCREENEVT type=0x%02X sub=0x%02X (%dB) val=%s"
                .format(tlv.type, tlv.sub, tlv.value.size, tlv.value.toHexFull()) }
        }
    }

    private fun launchStatusHeartbeat(sock: DashSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            var n = 0
            while (isActive) {
                runCatching { sock.send(DashCommands.heartbeat()) }
                // Keep the dash clock correct — it only shows what the phone feeds it.
                if (n++ % 30 == 0) runCatching { sock.send(DashCommands.timeSync()) }
                delay(1_000)
            }
        }
    }

    private fun launchProjectionHeartbeat() {
        projHbJob?.cancel()
        projHbJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                socket?.send(DashCommands.projectionFrame())
                delay(PROJ_HB_MS)
            }
        }
    }

    private fun launchRouteCardKeepAlive() {
        routeCardJob?.cancel()
        routeCardJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                if (navChromeEnabled) socket?.send(liveRouteCard())
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun launchNavInfo() {
        navInfoJob?.cancel()
        navInfoJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                if (navChromeEnabled && navActive) {
                    socket?.send(
                        DashCommands.activeNavPacket(
                            maneuver = navManeuver,
                            primaryDist = navPrimaryDist,
                            primaryUnit = navPrimaryUnit,
                            totalDist = navTotalDist,
                            totalUnit = navTotalUnit,
                        )
                    )
                }
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun launchMediaInfo() {
        mediaInfoJob?.cancel()
        mediaInfoJob = scope.launch(Dispatchers.IO) {
            var previousCaller: String? = null
            while (isActive && _state.value == DashState.STREAMING) {
                val caller = callerName
                when {
                    caller != null -> runCatching { socket?.send(DashCommands.callNotify(caller)) }
                    previousCaller != null -> runCatching { socket?.send(DashCommands.callClear()) }
                }
                previousCaller = caller
                mediaTitle?.let { title ->
                    runCatching {
                        socket?.send(DashCommands.nowPlaying(title, mediaAlbum, mediaArtist))
                    }
                }
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun fail(msg: String) {
        DebugLog.e(TAG, { "ERROR — $msg" })
        rxJob?.cancel(); heartbeatJob?.cancel(); mediaInfoJob?.cancel()
        socket?.close(); socket = null
        _state.value = DashState.ERROR
        onError?.invoke(msg)
    }

    /** Full hex dump (no truncation) — used for protocol-capture logging. */
    private fun ByteArray.toHexFull(): String =
        joinToString(" ") { "%02X".format(it) }

    /** AES-256-CBC/PKCS5 decrypt of an [iv(16) ‖ ciphertext] blob under the session key. */
    private fun aesDecryptCbc(ivAndCt: ByteArray, key: ByteArray): ByteArray? = runCatching {
        if (ivAndCt.size <= 16) return null
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.IvParameterSpec(ivAndCt.copyOfRange(0, 16)),
        )
        cipher.doFinal(ivAndCt.copyOfRange(16, ivAndCt.size))
    }.getOrNull()
}
