package com.recon.dash.ui

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.dash.*
import com.recon.dash.dash.map.MapRenderer
import com.recon.dash.dash.protocol.DashCommands
import com.recon.dash.dash.map.TileProvider
import com.recon.dash.dash.nav.Route
import com.recon.dash.dash.video.DashEncoder
import com.recon.dash.dash.video.DashIdleRenderer
import com.recon.dash.dash.video.NalProcessor
import com.recon.dash.dash.video.RtpPacketizer
import com.recon.dash.data.DashWallpaperFit
import com.recon.dash.data.DashWallpaperKind
import com.recon.dash.data.WallpaperRepository
import com.recon.dash.media.MediaSessionListener
import com.recon.dash.ui.theme.ThemeState
import com.recon.dash.util.DebugLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DashViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: DashConfig,
    private val navSessionManager: NavSessionManager,
    private val wallpaperRepo: WallpaperRepository,
    private val regionManager: com.recon.dash.data.RegionManager,
) : ViewModel() {

    init {
        // When a region download finishes, refresh the live tile provider so the newly-installed
        // pmtiles are served immediately — without this the map kept serving stale/online tiles
        // until an app restart (TileSource opens the file once at creation).
        viewModelScope.launch {
            regionManager.downloadState.collect { st ->
                if (st is com.recon.dash.data.DownloadState.Complete) {
                    tileProvider?.reloadOfflineTiles()
                }
            }
        }
    }

    companion object {
        private const val TAG = "DashViewModel"
        private const val LOG_MAX = 300
    }

    private val _mode = MutableStateFlow(config.mode)
    val mode = _mode.asStateFlow()

    private val _connectionState = MutableStateFlow(DashState.IDLE)
    val connectionState = _connectionState.asStateFlow()

    private val _protocolLog = MutableStateFlow<List<String>>(emptyList())
    val protocolLog = _protocolLog.asStateFlow()

    // First-time discovery: when we find an RE_* dash we haven't paired with yet, we surface
    // its exact SSID here and wait for the rider to confirm it's their bike (mirrors OpenDash's
    // pairing step) before storing + connecting. Null = no pending prompt.
    private val _pendingPairingSsid = MutableStateFlow<String?>(null)
    val pendingPairingSsid = _pendingPairingSsid.asStateFlow()

    private var wifiManager: DashWifiManager? = null
    private var session: DashSession? = null
    private var wifiCollectJob: Job? = null
    private var stateCollectJob: Job? = null
    private var renderJob: Job? = null

    // Digital-only pipeline
    private var encoder: DashEncoder? = null
    private var nalProcessor: NalProcessor? = null
    private var rtpPacketizer: RtpPacketizer? = null
    private var mapRenderer: MapRenderer? = null
    private var tileProvider: TileProvider? = null
    private var idleRenderer: DashIdleRenderer? = null
    private var wallpaperPath: String? = null

    // Nav-dash bridge
    private var bridge: NavDashBridge? = null
    private var navObserveJob: Job? = null

    // True once we've successfully streamed this connection. Used to tell a benign bike-off
    // (WiFi vanishes AFTER we were connected → return to Idle quietly) from a real failure
    // (never connected → show the error). Reset on each fresh connect()/disconnect().
    @Volatile private var wasEverStreaming = false

    fun setMode(mode: DashMode) {
        if (_connectionState.value != DashState.IDLE && _connectionState.value != DashState.ERROR) return
        _mode.value = mode
        config.mode = mode
    }

    fun connect() {
        if (_connectionState.value != DashState.IDLE && _connectionState.value != DashState.ERROR) return
        wasEverStreaming = false
        val currentMode = _mode.value
        appendLog("Connecting in ${currentMode.name} mode")

        _connectionState.value = DashState.CONNECTING

        val wifi = DashWifiManager(context, viewModelScope)
        wifiManager = wifi

        wifi.onSsidResolved = { ssid ->
            config.ssid = ssid
            appendLog("SSID resolved -- ${ssid.take(6)}...")
        }

        wifiCollectJob = viewModelScope.launch {
            wifi.state.collect { ws ->
                // Gate the dash socket's sends on link state: when WiFi drops (onLost ->
                // REQUESTING), pause RTP/heartbeat sending so we don't hammer a dead socket ~48x/s
                // (that produced 40k ENETUNREACH failed-sends + a logcat flood in one ride).
                val linkUp = ws.status == WifiConnStatus.CONNECTED
                session?.setLinkUp(linkUp)
                // When the bike is switched off, its WiFi vanishes: onLost -> REQUESTING. The
                // session's own state stays STREAMING (no packet-timeout watchdog), so screens that
                // read DashConnectionState (ActiveNavScreen's "Dash connected" badge) went stale
                // while the Dash screen showed the WiFi ERROR — two screens, two truths. Reflect the
                // link loss app-wide immediately so they agree. We do NOT tear the session down here:
                // WiFi auto-reconnects for ~8s (RECONNECT_DELAY), and if it comes back the session's
                // own state collector re-asserts STREAMING. If it doesn't, the WiFi ERROR path fails it.
                if (!linkUp && com.recon.dash.dash.DashConnectionState.isConnected) {
                    com.recon.dash.dash.DashConnectionState.update(DashState.CONNECTING)
                }
                when (ws.status) {
                    WifiConnStatus.CONNECTED -> {
                        // WiFi CONNECTED can fire more than once (Android 13+ resolves the
                        // redacted SSID via a later capabilities change; reconnects re-emit).
                        // Start the dash session ONLY once — a second session opens a second
                        // RX socket on :2002, and the two RX loops steal each other's packets,
                        // so the RSA auth reply lands on the wrong loop and auth never completes.
                        val existing = session
                        val busy = existing != null &&
                            existing.state.value != DashState.IDLE &&
                            existing.state.value != DashState.ERROR
                        // The dash validates the SSID inside the encrypted handshake, so we must
                        // have the EXACT resolved SSID — never the bare "RE_" prefix or blank.
                        val ssid = ws.ssid.trim()
                        val ssidReady = ssid.isNotBlank() && ssid != config.ssidPrefix
                        when {
                            busy -> {}
                            !ssidReady -> appendLog("WiFi connected -- waiting for exact SSID")
                            else -> {
                                appendLog("WiFi connected -- settling network")
                                // The WiFi CONNECTED signal arrives BEFORE the network is
                                // actually routable — opening sockets immediately gives
                                // ENETUNREACH on the auth burst, auth times out, and we churn
                                // in a reconnect loop. Wait for the link to settle (OpenDash
                                // does the same 1.2s wait), then re-check we still want it and
                                // aren't already running before starting the session.
                                delay(1_200)
                                val stillWanted = _connectionState.value == DashState.CONNECTING &&
                                    wifi.state.value.status == WifiConnStatus.CONNECTED
                                val stillIdle = session.let {
                                    it == null || it.state.value == DashState.IDLE ||
                                        it.state.value == DashState.ERROR
                                }
                                if (stillWanted && stillIdle) {
                                    appendLog("Network settled -- starting session")
                                    startSession(currentMode, ssid, wifi.network)
                                }
                            }
                        }
                    }
                    WifiConnStatus.ERROR -> {
                        if (wasEverStreaming) {
                            // We WERE connected and the WiFi is now gone — this is almost always the
                            // bike being switched off, not a fault. Return to Idle quietly instead of
                            // flashing a red "could not connect / wrong password" error (bad UX for a
                            // normal key-off). A fresh connect() starts clean.
                            appendLog("Dash WiFi gone (bike off?) -- disconnecting")
                            disconnect()
                        } else {
                            // Never got connected → a genuine connect failure; surface it.
                            appendLog("WiFi error -- ${ws.error}")
                            _connectionState.value = DashState.ERROR
                        }
                    }
                    else -> {}
                }
            }
        }

        // We must connect with the EXACT SSID: the dash validates it inside the encrypted
        // auth handshake, and Android 13+ (strict on 15/16) REDACTS the SSID of a network
        // joined by prefix. So a prefix connect leaves us unable to read the real name and
        // we hang forever in CONNECTING.
        val prefix = config.ssidPrefix
        val stored = config.ssid.takeIf { it.isNotBlank() }

        when {
            // Already paired with this rider's bike before → connect exact immediately.
            stored != null -> connectWifiExact(wifi, stored)
            // First time: discover the exact SSID. Prefer the network the phone is already
            // joined to (connectionInfo — reliable on Android 16), else a WiFi scan.
            else -> {
                val discovered = wifi.activeDashSsid(prefix) ?: wifi.findDashSsid(prefix)
                if (discovered != null) {
                    // Ask the rider to confirm it's their bike before storing (OpenDash's
                    // pairing step) — avoids silently pairing to a neighbour's RE_* dash.
                    appendLog("Found dash -- confirm pairing: ${discovered.take(6)}...")
                    _pendingPairingSsid.value = discovered
                    // Stay in CONNECTING visually; confirmPairing()/rejectPairing() resolve it.
                } else {
                    // Nothing found. The prefix path can't reliably auth on Android 13+;
                    // guide the rider to join the dash WiFi once so connectionInfo can read it.
                    appendLog("No dash found -- join the RE_ WiFi in Settings once, then retry")
                    wifi.connect(prefix, config.password, prefixMatch = true)
                }
            }
        }
    }

    /** Persist the exact SSID and start the WiFi association with it. */
    private fun connectWifiExact(wifi: DashWifiManager, ssid: String) {
        config.ssid = ssid
        appendLog("Connecting to exact SSID -- ${ssid.take(6)}...")
        wifi.connect(ssid, config.password)
    }

    /** Rider confirmed the discovered dash is theirs: store it and connect. */
    fun confirmPairing() {
        val ssid = _pendingPairingSsid.value ?: return
        _pendingPairingSsid.value = null
        val wifi = wifiManager ?: return
        connectWifiExact(wifi, ssid)
    }

    /** Rider declined the discovered dash: cancel the pending connection. */
    fun rejectPairing() {
        _pendingPairingSsid.value = null
        appendLog("Pairing cancelled")
        disconnect()
    }

    fun disconnect() {
        appendLog("Disconnecting")
        wasEverStreaming = false
        glyphProbeJob?.cancel(); glyphProbeJob = null
        _glyphProbeRunning.value = false; _glyphProbeCode.value = null
        ThemeState.forceRiding = false
        navObserveJob?.cancel(); navObserveJob = null
        bridge?.stopMediaForwarding()
        bridge?.stopNavigation()
        bridge = null
        MediaSessionListener.stop()
        com.recon.dash.media.CallStateListener.stop()
        renderJob?.cancel(); renderJob = null
        stateCollectJob?.cancel(); stateCollectJob = null
        wifiCollectJob?.cancel(); wifiCollectJob = null
        session?.disconnect(); session = null
        wifiManager?.disconnect(); wifiManager = null
        releaseDigitalPipeline()
        DashKeepAliveService.stop(context)
        _connectionState.value = DashState.IDLE
        com.recon.dash.dash.DashConnectionState.update(DashState.IDLE)
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private fun startSession(mode: DashMode, ssid: String, network: android.net.Network?) {
        // Never run two sessions at once (see the caller): a duplicate RX socket breaks auth.
        session?.let { existing ->
            if (existing.state.value != DashState.IDLE && existing.state.value != DashState.ERROR) return
        }
        val sess = DashSession(viewModelScope, mode, projectWhenIdle = config.projectWhenIdle)
        session = sess

        sess.onError = { msg ->
            appendLog("ERROR -- $msg")
        }
        sess.onProtocolEvent = { msg ->
            appendLog(msg)
        }
        sess.onButton = { btn ->
            appendLog("Button 0x${(btn.toInt() and 0xFF).toString(16).uppercase()}")
        }

        stateCollectJob = viewModelScope.launch {
            sess.state.collect { state ->
                _connectionState.value = state
                com.recon.dash.dash.DashConnectionState.update(state)
                when (state) {
                    DashState.AUTHENTICATING -> appendLog("Authenticating with dash")
                    DashState.READY -> {
                        appendLog("Session ready")
                        onSessionReady(mode, sess)
                    }
                    DashState.STREAMING -> {
                        appendLog("Streaming active (${mode.name})")
                        wasEverStreaming = true
                        ThemeState.forceRiding = true
                        DashKeepAliveService.start(context)
                    }
                    DashState.ERROR -> {
                        appendLog("Session error")
                        releaseDigitalPipeline()
                        // Auth failed on a STORED SSID → it's likely stale (rider switched
                        // bikes, or the dash's SSID changed). Clear it so the next connect
                        // re-discovers instead of retrying the same wrong name forever.
                        if (config.ssid.isNotBlank()) {
                            appendLog("Clearing stored SSID after failure -- will re-discover")
                            config.ssid = ""
                        }
                    }
                    else -> {}
                }
            }
        }

        sess.connect(ssid, network)
    }

    private fun onSessionReady(mode: DashMode, sess: DashSession) {
        if (mode == DashMode.DIGITAL) {
            prepareDigitalPipeline(sess)
            startRenderLoop(sess)
        }
        val b = NavDashBridge(sess, viewModelScope)
        bridge = b
        MediaSessionListener.start(context)
        com.recon.dash.media.CallStateListener.start(context)
        b.startMediaForwarding()
        observeNavSession(b)
        sess.startStreaming()
    }

    private fun observeNavSession(bridge: NavDashBridge) {
        navObserveJob?.cancel()
        navObserveJob = viewModelScope.launch {
            launch {
                navSessionManager.isNavigating.collect { navigating ->
                    if (navigating) {
                        val route = navSessionManager.activeRoute.value
                        val dest = navSessionManager.destinationName.value
                        if (route != null) bridge.startNavigation(route, dest)
                    } else {
                        bridge.stopNavigation()
                    }
                }
            }
            launch {
                // Single shared progress snapshot drives the dash nav bubble.
                navSessionManager.progress.collect { progress ->
                    if (progress != null && navSessionManager.isNavigating.value) {
                        bridge.updateProgress(progress)
                    }
                }
            }
            launch {
                navSessionManager.activeRoute.collect { route ->
                    if (route != null) bridge.updateRoute(route)
                }
            }
        }
    }

    private fun prepareDigitalPipeline(sess: DashSession) {
        val rtp = RtpPacketizer { packet -> sess.sendRtp(packet) }
        rtpPacketizer = rtp

        val nal = NalProcessor { nalBytes, isIdr ->
            rtp.packetize(nalBytes, endOfAU = true, wallClockMs = SystemClock.elapsedRealtime())
        }
        nalProcessor = nal

        val enc = DashEncoder { annexB, _ -> nal.process(annexB) }
        encoder = enc
        enc.prepare()

        val tiles = TileProvider(context, viewModelScope)
        tileProvider = tiles

        val renderer = MapRenderer(tiles)
        mapRenderer = renderer

        idleRenderer = DashIdleRenderer()
        resolveWallpaperPath()

        appendLog("Digital pipeline ready -- encoder: ${DashEncoder.WIDTH}x${DashEncoder.HEIGHT}@${DashEncoder.FPS}fps")
    }

    private fun resolveWallpaperPath() {
        val selected = wallpaperRepo.getSelected()
        if (selected == null) {
            // "None" selected — dash idle screen falls back to the plain dark background.
            wallpaperPath = null
            return
        }
        val file = java.io.File(context.cacheDir, "wallpaper_current.png")
        if (!file.exists()) {
            wallpaperRepo.loadBitmap(selected, DashEncoder.WIDTH, DashEncoder.HEIGHT)?.let { bmp ->
                file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
        }
        wallpaperPath = file.absolutePath
    }

    private fun startRenderLoop(sess: DashSession) {
        val enc = encoder ?: return
        val renderer = mapRenderer ?: return
        val idle = idleRenderer
        val intervalMs = 1000L / DashEncoder.FPS

        appendLog("Render loop starting")
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            var frames = 0
            var lastLog = SystemClock.elapsedRealtime()
            while (isActive && (sess.state.value == DashState.STREAMING ||
                   sess.state.value == DashState.READY)) {
                val start = SystemClock.elapsedRealtime()
                val navigating = navSessionManager.isNavigating.value
                val progress = navSessionManager.progress.value
                val route = navSessionManager.activeRoute.value
                val destName = navSessionManager.destinationName.value

                enc.renderFrame { canvas ->
                    if (navigating && progress != null) {
                        val dest = route?.destination
                        // Use the SHARED snapped progress: rider rides the snapped line, bearing
                        // is travel-up, and the route is split into traveled (grey) + ahead (blue).
                        renderer.draw(canvas, MapRenderer.Frame(
                            centerLat = progress.snapped.lat,
                            centerLng = progress.snapped.lng,
                            zoom = 17,
                            headingUp = true,
                            heading = progress.bearing.toFloat(),
                            riderLat = progress.snapped.lat,
                            riderLng = progress.snapped.lng,
                            destLat = dest?.lat,
                            destLng = dest?.lng,
                            destName = destName.ifBlank { null },
                            route = progress.aheadGeometry,
                            travelledRoute = progress.traveledGeometry,
                        ))
                    } else if (idle != null) {
                        idle.draw(
                            canvas,
                            wallpaperPath,
                            DashWallpaperKind.IMAGE,
                            0f, 0f,
                            DashWallpaperFit.CROP,
                        )
                    } else {
                        renderer.draw(canvas, MapRenderer.Frame(
                            centerLat = 0.0,
                            centerLng = 0.0,
                            zoom = 15,
                        ))
                    }
                }
                enc.drain()
                frames++
                // Heartbeat log once a second so we can see the loop is alive and whether
                // it has a position/nav state (diagnosing the "map not streaming" issue).
                if (start - lastLog >= 1000) {
                    DebugLog.i(TAG) { "Render: ${frames}f/s navigating=$navigating progress=${progress != null}" }
                    frames = 0; lastLog = start
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                delay((intervalMs - elapsed).coerceAtLeast(0))
            }
            DebugLog.w(TAG) { "Render loop ENDED (state=${sess.state.value})" }
        }
    }

    private fun releaseDigitalPipeline() {
        renderJob?.cancel(); renderJob = null
        encoder?.release(); encoder = null
        nalProcessor = null
        rtpPacketizer = null
        mapRenderer = null
        tileProvider = null
        idleRenderer?.release(); idleRenderer = null
        wallpaperPath = null
    }

    // ── Glyph probe (debug) ────────────────────────────────────────────────
    // Maps the dash's turn-glyph codes without any WiFi sniffing: we drive the
    // EXISTING nav-info send path (session.updateNavInfo -> activeNavPacket /
    // routeCard 05 02 maneuver field, sent at 1 Hz while streaming) across every
    // candidate code and let the rider photograph which glyph the TFT shows. This
    // touches only ui/ — no changes to the proven dash/ protocol code. We currently
    // hardcode CONTINUE (0x0B); everything else is unverified, hence this sweep.
    private var glyphProbeJob: Job? = null

    private val _glyphProbeRunning = MutableStateFlow(false)
    val glyphProbeRunning = _glyphProbeRunning.asStateFlow()
    private val _glyphProbeCode = MutableStateFlow<Int?>(null)
    val glyphProbeCode = _glyphProbeCode.asStateFlow()

    /**
     * Sweep maneuver codes [from]..[to] inclusive, dwelling [dwellMs] on each so the
     * dash re-renders and the rider can photograph the glyph. Requires an active
     * STREAMING session (connect first). Fixed distances/units keep every field but
     * the glyph constant, so only the maneuver changes frame-to-frame.
     *
     * Range default 0x00..0x40: covers 0x0B (CONTINUE, the one hardware-verified code)
     * AND 0x3C — a code the real RE app was observed sending in a captured route card
     * (see DashCommands template + OpenDash notes), our best lead for a real glyph.
     * Neither OpenDash nor us has verified anything beyond 0x0B; this sweep is how we do it.
     *
     * SELF-LABELING: unlike the earlier in-memory-only marker, every code is written to a CSV
     * on disk (filesDir/glyph-probe/probe-<startMs>.csv) AND emitted as a distinctive greppable
     * "GLYPHMAP" logcat line. That gives an exact code<->timestamp anchor to align against a
     * video/photo capture afterwards, with NO alignment guesswork:
     *   - Video: elapsedMs (from the CSV) maps straight to the video second.
     *   - Photos: the wall-clock column aligns with photo EXIF time.
     *   - Live:  `adb logcat -s DashViewModel | grep GLYPHMAP` captures it in real time.
     */
    fun startGlyphProbe(from: Int = 0x00, to: Int = 0x40, dwellMs: Long = 5_000L) {
        val sess = session
        if (sess == null || _connectionState.value != DashState.STREAMING) {
            appendLog("Glyph probe needs an active streaming session — connect first")
            return
        }
        if (glyphProbeJob?.isActive == true) return
        glyphProbeJob = viewModelScope.launch {
            _glyphProbeRunning.value = true
            // One CSV per run; header documents the columns. Failure to open the file must NOT
            // abort the probe (the logcat GLYPHMAP line is a second, independent record).
            val startWall = System.currentTimeMillis()
            val startElapsed = SystemClock.elapsedRealtime()
            val csv = runCatching {
                val dir = File(context.filesDir, "glyph-probe").apply { mkdirs() }
                File(dir, "probe-$startWall.csv").also {
                    it.appendText("code_dec,code_hex,elapsed_ms,wall_iso,dwell_ms\n")
                }
            }.getOrNull()
            val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US)
            appendLog("GLYPH PROBE start ${hex(from)}..${hex(to)} @ ${dwellMs}ms" +
                (csv?.let { " -> ${it.name}" } ?: " (csv open failed; logcat GLYPHMAP still active)"))
            try {
                for (code in from..to) {
                    if (!isActive || session == null) break
                    _glyphProbeCode.value = code
                    // Hold every other field steady (500 m to turn, 5.0 km total) so the
                    // ONLY thing changing on the dash between codes is the glyph itself.
                    sess.updateNavInfo(
                        maneuver = code,
                        primaryDist = 500, primaryUnit = DashCommands.NAV_UNIT_METERS,
                        totalDist = 5000, totalUnit = DashCommands.NAV_UNIT_METERS,
                    )
                    val elapsed = SystemClock.elapsedRealtime() - startElapsed
                    val wall = iso.format(java.util.Date())
                    // Greppable one-liner for live `adb logcat -s DashViewModel | grep GLYPHMAP`.
                    DebugLog.i(TAG) { "GLYPHMAP code=$code hex=${hex(code)} elapsedMs=$elapsed wall=$wall" }
                    csv?.let { runCatching { it.appendText("$code,${hex(code)},$elapsed,$wall,$dwellMs\n") } }
                    appendLog("GLYPH ${hex(code)} (${code}) @ ${elapsed}ms — photograph now")
                    delay(dwellMs)
                }
                appendLog("GLYPH PROBE done" + (csv?.let { " — saved ${it.name}" } ?: ""))
            } finally {
                _glyphProbeRunning.value = false
                _glyphProbeCode.value = null
            }
        }
    }

    fun stopGlyphProbe() {
        glyphProbeJob?.cancel(); glyphProbeJob = null
        _glyphProbeRunning.value = false
        _glyphProbeCode.value = null
        appendLog("GLYPH PROBE stopped")
    }

    // ── Screen-focus probe (debug) ─────────────────────────────────────────
    // Finds the "switch the dash carousel to screen N" command so we can auto-open Nav / Phone /
    // Media instead of the rider joysticking to them. navStart = 06 80 01 0B, so 06 80 <byte> is
    // our best lead. This sweeps candidate bytes; you WATCH the dash and note which value jumps it
    // to Nav/Phone/Media. Self-labeling (SCREENPROBE logcat + CSV) so the value that worked is
    // recoverable exactly. Additive: uses sendRaw, touches no proven send path.
    private var screenProbeJob: Job? = null
    private val _screenProbeRunning = MutableStateFlow(false)
    val screenProbeRunning = _screenProbeRunning.asStateFlow()
    private val _screenProbeCode = MutableStateFlow<Int?>(null)
    val screenProbeCode = _screenProbeCode.asStateFlow()

    fun startScreenProbe(from: Int = 0x00, to: Int = 0x20, dwellMs: Long = 5_000L) {
        val sess = session
        if (sess == null || _connectionState.value != DashState.STREAMING) {
            appendLog("Screen probe needs an active streaming session — connect first")
            return
        }
        if (screenProbeJob?.isActive == true) return
        screenProbeJob = viewModelScope.launch {
            _screenProbeRunning.value = true
            val startWall = System.currentTimeMillis()
            val startElapsed = SystemClock.elapsedRealtime()
            val csv = runCatching {
                val dir = File(context.filesDir, "screen-probe").apply { mkdirs() }
                File(dir, "screen-$startWall.csv").also {
                    it.appendText("code_dec,code_hex,elapsed_ms,wall_iso,dwell_ms\n")
                }
            }.getOrNull()
            val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US)
            appendLog("SCREEN PROBE start 06 80 ${hex(from)}..${hex(to)} @ ${dwellMs}ms — watch the dash carousel")
            try {
                for (code in from..to) {
                    if (!isActive || session == null) break
                    _screenProbeCode.value = code
                    sess.sendRaw(DashCommands.screenFocusProbe(code))
                    val elapsed = SystemClock.elapsedRealtime() - startElapsed
                    val wall = iso.format(java.util.Date())
                    DebugLog.i(TAG) { "SCREENPROBE code=$code hex=${hex(code)} elapsedMs=$elapsed wall=$wall (sent 06 80 ${hex(code)})" }
                    csv?.let { runCatching { it.appendText("$code,${hex(code)},$elapsed,$wall,$dwellMs\n") } }
                    appendLog("SCREEN 06 80 ${hex(code)} — note if the dash switched screen")
                    delay(dwellMs)
                }
                appendLog("SCREEN PROBE done" + (csv?.let { " — saved ${it.name}" } ?: ""))
            } finally {
                _screenProbeRunning.value = false
                _screenProbeCode.value = null
            }
        }
    }

    fun stopScreenProbe() {
        screenProbeJob?.cancel(); screenProbeJob = null
        _screenProbeRunning.value = false
        _screenProbeCode.value = null
        appendLog("SCREEN PROBE stopped")
    }

    private fun hex(v: Int) = "0x%02X".format(v)

    private fun appendLog(message: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val entry = "$ts  $message"
        DebugLog.i(TAG) { message }
        val current = _protocolLog.value.toMutableList()
        current.add(0, entry)
        if (current.size > LOG_MAX) current.removeAt(current.lastIndex)
        _protocolLog.value = current
    }
}
