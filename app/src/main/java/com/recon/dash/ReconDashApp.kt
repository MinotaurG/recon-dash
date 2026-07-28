package com.recon.dash

import android.app.Application
import com.recon.dash.dash.DashConfig
import com.recon.dash.dash.nav.GoogleRoutesClient
import com.recon.dash.search.PhotonClient
import com.recon.dash.ui.theme.ThemeMode
import com.recon.dash.ui.theme.ThemeState
import com.recon.dash.util.DebugLog
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ReconDashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this) // persistent ride logs survive the logcat buffer
        val config = DashConfig.get(this)
        ThemeState.mode = runCatching { ThemeMode.valueOf(config.themeMode) }
            .getOrDefault(ThemeMode.AUTO)

        val placesKey = config.googlePlacesApiKey.ifBlank { BuildConfig.GOOGLE_PLACES_KEY }
        PhotonClient.googleApiKey = placesKey
        // Same GCP key drives the debug-only Routes API divergence capture (release never calls it).
        GoogleRoutesClient.apiKey = placesKey
        DebugLog.i("ReconDashApp") {
            "Google key ${if (placesKey.isNotBlank()) "present" else "MISSING"}; " +
                "divergence capture ${if (BuildConfig.DEBUG && placesKey.isNotBlank()) "ARMED" else "off"}"
        }
    }
}
