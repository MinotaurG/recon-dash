package com.recon.dash

import android.app.Application
import com.recon.dash.dash.DashConfig
import com.recon.dash.search.PhotonClient
import com.recon.dash.ui.theme.ThemeMode
import com.recon.dash.ui.theme.ThemeState
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ReconDashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val config = DashConfig.get(this)
        ThemeState.mode = runCatching { ThemeMode.valueOf(config.themeMode) }
            .getOrDefault(ThemeMode.AUTO)

        val placesKey = config.googlePlacesApiKey.ifBlank { BuildConfig.GOOGLE_PLACES_KEY }
        PhotonClient.googleApiKey = placesKey
    }
}
