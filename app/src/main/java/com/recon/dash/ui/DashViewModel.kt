package com.recon.dash.ui

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.dash.*
import com.recon.dash.dash.map.MapRenderer
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

    fun setMode(mode: DashMode) {
        if (_connectionState.value != DashState.IDLE && _connectionState.value != DashState.ERROR) return
        _mode.value = mode
        config.mode = mode
    }

    fun connect() {
        if (_connectionState.value != DashState.IDLE && _connectionState.value != DashState.ERROR) return
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
                        appendLog("WiFi error -- ${ws.error}")
                        _connectionState.value = DashState.ERROR
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
        ThemeState.forceRiding = false
        navObserveJob?.cancel(); navObserveJob = null
        bridge?.stopMediaForwarding()
        bridge?.stopNavigation()
        bridge = null
        MediaSessionListener.stop()
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
