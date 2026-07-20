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
        private const val LOG_MAX = 100
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
                        appendLog("WiFi connected -- starting session")
                        startSession(currentMode, ws.ssid, wifi.network)
                    }
                    WifiConnStatus.ERROR -> {
                        appendLog("WiFi error -- ${ws.error}")
                        _connectionState.value = DashState.ERROR
                    }
                    else -> {}
                }
            }
        }

        val ssid = config.ssid
        if (ssid.isNotBlank()) {
            wifi.connect(ssid, config.password)
        } else {
            wifi.connect(config.ssidPrefix, config.password, prefixMatch = true)
        }
    }

    fun disconnect() {
        appendLog("Disconnecting")
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
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private fun startSession(mode: DashMode, ssid: String, network: android.net.Network?) {
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
                when (state) {
                    DashState.AUTHENTICATING -> appendLog("Authenticating with dash")
                    DashState.READY -> {
                        appendLog("Session ready")
                        onSessionReady(mode, sess)
                    }
                    DashState.STREAMING -> {
                        appendLog("Streaming active (${mode.name})")
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
        if (selected != null) {
            val file = java.io.File(context.cacheDir, "wallpaper_current.png")
            if (!file.exists()) {
                wallpaperRepo.loadBitmap(selected, DashEncoder.WIDTH, DashEncoder.HEIGHT)?.let { bmp ->
                    file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
            }
            wallpaperPath = file.absolutePath
        }
    }

    private fun startRenderLoop(sess: DashSession) {
        val enc = encoder ?: return
        val renderer = mapRenderer ?: return
        val idle = idleRenderer
        val intervalMs = 1000L / DashEncoder.FPS

        renderJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive && (sess.state.value == DashState.STREAMING ||
                   sess.state.value == DashState.READY)) {
                val start = SystemClock.elapsedRealtime()
                val navigating = navSessionManager.isNavigating.value
                val pos = navSessionManager.latestPosition.value

                enc.renderFrame { canvas ->
                    if (navigating && pos != null) {
                        renderer.draw(canvas, MapRenderer.Frame(
                            centerLat = pos.lat,
                            centerLng = pos.lng,
                            zoom = 17,
                            headingUp = true,
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
                val elapsed = SystemClock.elapsedRealtime() - start
                delay((intervalMs - elapsed).coerceAtLeast(0))
            }
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
