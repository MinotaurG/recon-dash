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
) : ViewModel() {

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
        // joined by prefix — so a prefix connect leaves us unable to read the real name and
        // we hang forever in CONNECTING. Resolve the exact SSID from a WiFi scan first
        // (this is what OpenDash does); only fall back to prefix if the scan finds nothing.
        val prefix = config.ssidPrefix
        val stored = config.ssid
        // Resolve the EXACT SSID, in priority order:
        //   1. Already joined to the dash WiFi (connectionInfo) — the path proven to work on
        //      Android 16, and the common case (rider joins RE_* in system settings first).
        //   2. Stored from a previous successful connect.
        //   3. WiFi scan results.
        // Only if all three fail do we fall back to a prefix connect (hangs on Android 13+
        // because the OS redacts the joined SSID from our network callback).
        val ssid = wifi.activeDashSsid(prefix)
            ?: stored.takeIf { it.isNotBlank() }
            ?: wifi.findDashSsid(prefix)
        if (!ssid.isNullOrBlank()) {
            config.ssid = ssid
            appendLog("Connecting to exact SSID -- ${ssid.take(6)}...")
            wifi.connect(ssid, config.password)
        } else {
            appendLog("No exact SSID found -- trying prefix (may fail on Android 13+)")
            wifi.connect(prefix, config.password, prefixMatch = true)
        }
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
        val sess = DashSession(viewModelScope, mode)
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
                navSessionManager.latestPosition.collect { update ->
                    if (update != null && navSessionManager.isNavigating.value) {
                        bridge.updatePosition(update.lat, update.lng, update.speedMps)
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

    fun startNavOnDash(route: Route, destinationName: String) {
        bridge?.startNavigation(route, destinationName)
    }

    fun updatePositionOnDash(lat: Double, lng: Double, speedMps: Float) {
        bridge?.updatePosition(lat, lng, speedMps)
    }

    fun updateRouteOnDash(route: Route) {
        bridge?.updateRoute(route)
    }

    fun stopNavOnDash() {
        bridge?.stopNavigation()
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
                val pos = navSessionManager.latestPosition.value
                val route = navSessionManager.activeRoute.value
                val destName = navSessionManager.destinationName.value

                enc.renderFrame { canvas ->
                    if (navigating && pos != null) {
                        val geometry = route?.geometry ?: emptyList()
                        val dest = geometry.lastOrNull()
                        // Heading toward the next route point so the map orients travel-up
                        // and the rider arrow points the right way (matches the RE app view).
                        val heading = headingAlong(geometry, pos.lat, pos.lng)
                        renderer.draw(canvas, MapRenderer.Frame(
                            centerLat = pos.lat,
                            centerLng = pos.lng,
                            zoom = 17,
                            headingUp = true,
                            heading = heading,
                            // Passing riderLat/route/dest is what draws the position arrow,
                            // the blue route line, and clears the "waiting for GPS" banner
                            // (which showed permanently because these were never provided).
                            riderLat = pos.lat,
                            riderLng = pos.lng,
                            destLat = dest?.lat,
                            destLng = dest?.lng,
                            destName = destName.ifBlank { null },
                            route = geometry,
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
                    DebugLog.i(TAG) { "Render: ${frames}f/s navigating=$navigating pos=${pos != null}" }
                    frames = 0; lastLog = start
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                delay((intervalMs - elapsed).coerceAtLeast(0))
            }
            DebugLog.w(TAG) { "Render loop ENDED (state=${sess.state.value})" }
        }
    }

    /**
     * Bearing (degrees) from the rider toward the closest route point ahead, so the dash map
     * orients travel-up and the rider arrow points along the route. Falls back to 0 (north-up)
     * when there's no usable route geometry.
     */
    private fun headingAlong(route: List<com.recon.dash.dash.nav.GeoPoint>, lat: Double, lng: Double): Float {
        if (route.size < 2) return 0f
        // Find the nearest route vertex, then aim at the one after it.
        var nearestIdx = 0
        var nearestD = Double.MAX_VALUE
        for (i in route.indices) {
            val d = (route[i].lat - lat) * (route[i].lat - lat) + (route[i].lng - lng) * (route[i].lng - lng)
            if (d < nearestD) { nearestD = d; nearestIdx = i }
        }
        val target = route.getOrNull(nearestIdx + 1) ?: route[nearestIdx]
        val dLng = Math.toRadians(target.lng - lng)
        val y = Math.sin(dLng) * Math.cos(Math.toRadians(target.lat))
        val x = Math.cos(Math.toRadians(lat)) * Math.sin(Math.toRadians(target.lat)) -
            Math.sin(Math.toRadians(lat)) * Math.cos(Math.toRadians(target.lat)) * Math.cos(dLng)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()
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
