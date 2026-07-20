package com.recon.dash.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import com.recon.dash.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val isPlaying: Boolean,
)

/**
 * Listens to active media sessions (Spotify, YT Music, any player) via
 * MediaSessionManager. Requires NotificationListenerService permission.
 *
 * Exposes [nowPlaying] as a StateFlow that the DashViewModel reads and
 * forwards to the dash's media widget at 1 Hz.
 */
object MediaSessionListener {

    private const val TAG = "MediaSessionListener"

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()

    private var sessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    private var callback: MediaController.Callback? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickBestController(controllers)
    }

    fun start(context: Context) {
        val componentName = ComponentName(context, MediaNotificationListener::class.java)
        val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        sessionManager = sm ?: return

        try {
            sm.addOnActiveSessionsChangedListener(sessionListener, componentName)
            pickBestController(sm.getActiveSessions(componentName))
            DebugLog.i(TAG) { "Started listening for media sessions" }
        } catch (e: SecurityException) {
            DebugLog.w(TAG) { "NotificationListener permission not granted: ${e.message}" }
        }
    }

    fun stop() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        detachController()
        sessionManager = null
        _nowPlaying.value = null
    }

    private fun pickBestController(controllers: List<MediaController>?) {
        val playing = controllers?.firstOrNull { c ->
            c.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers?.firstOrNull()

        if (playing == activeController) return
        detachController()
        if (playing == null) {
            _nowPlaying.value = null
            return
        }

        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                updateFromController(playing)
            }
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                updateFromController(playing)
            }
        }
        playing.registerCallback(cb)
        activeController = playing
        callback = cb
        updateFromController(playing)
    }

    private fun updateFromController(controller: MediaController) {
        val meta = controller.metadata
        val state = controller.playbackState

        if (meta == null) {
            _nowPlaying.value = null
            return
        }

        _nowPlaying.value = NowPlaying(
            title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
        )
    }

    private fun detachController() {
        callback?.let { activeController?.unregisterCallback(it) }
        activeController = null
        callback = null
    }
}
