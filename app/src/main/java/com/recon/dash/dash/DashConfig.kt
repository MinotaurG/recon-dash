package com.recon.dash.dash

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.recon.dash.util.DebugLog

/**
 * Per-rider dash WiFi configuration, persisted on-device.
 *
 * OpenDash is meant to work on any compatible Tripper dash, not just the author's.
 * Every dash advertises a different SSID (e.g. `RE_P0RP_260525`, `RE_XXXX_yymmdd`) but
 * they all share the `RE_` prefix and the factory passphrase `12345678`. So out of the
 * box we connect by PREFIX (see [DashWifiManager]) — the rider just picks their dash from
 * the system dialog once — and then we remember that exact SSID here for direct reconnects.
 *
 * Everything is overridable in Settings for dashes that don't fit the defaults.
 */
class DashConfig private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val legacyPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val prefs = encryptedPrefsOrFallback()

    /** Broadest match across Tripper variants; rider-overridable. */
    var ssidPrefix: String
        get() = prefs.getString(KEY_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
        set(v) = prefs.edit().putString(KEY_PREFIX, v).apply()

    /** The exact SSID once learned/entered. Empty = not yet known → discover by prefix. */
    var ssid: String
        get() = prefs.getString(KEY_SSID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SSID, v).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, DEFAULT_PASSWORD) ?: DEFAULT_PASSWORD
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    var mode: DashMode
        get() = runCatching { DashMode.valueOf(prefs.getString(KEY_MODE, DashMode.DIGITAL.name)!!) }
            .getOrDefault(DashMode.DIGITAL)
        set(v) = prefs.edit().putString(KEY_MODE, v.name).apply()

    var musicApp: String
        get() = prefs.getString(KEY_MUSIC_APP, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MUSIC_APP, v).apply()

    var visibleCustomSlots: Set<String>
        get() = prefs.getStringSet(KEY_VISIBLE_SLOTS, null) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_VISIBLE_SLOTS, v).apply()

    var themeMode: String
        get() = prefs.getString(KEY_THEME, "AUTO") ?: "AUTO"
        set(v) = prefs.edit().putString(KEY_THEME, v).apply()

    var speedAlertKmh: Int
        get() = prefs.getString(KEY_SPEED_ALERT, "0")?.toIntOrNull() ?: 0
        set(v) = prefs.edit().putString(KEY_SPEED_ALERT, v.toString()).apply()

    var googlePlacesApiKey: String
        get() = prefs.getString(KEY_GOOGLE_PLACES, "") ?: ""
        set(v) = prefs.edit().putString(KEY_GOOGLE_PLACES, v).apply()

    /**
     * When true, the dash projects the idle wallpaper while not navigating (OpenDash behavior,
     * replaces the native RPM screen). When false (default), the dash keeps its own RPM/instrument
     * screen when idle and only shows projection during navigation.
     */
    var projectWhenIdle: Boolean
        get() = prefs.getBoolean(KEY_PROJECT_IDLE, false)
        set(v) = prefs.edit().putBoolean(KEY_PROJECT_IDLE, v).apply()

    /** True until a specific dash has been identified — connect by prefix discovery. */
    val needsDiscovery: Boolean get() = ssid.isBlank()

    /** Forget the learned dash so the next connect re-runs prefix discovery. */
    fun forgetDash() { ssid = "" }

    private fun encryptedPrefsOrFallback(): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { migrateLegacyValues(it) }
        }.getOrElse { error ->
            DebugLog.w(TAG) { "Encrypted dash_config unavailable; using fallback prefs (${error.javaClass.simpleName})" }
            showEncryptionWarning()
            legacyPrefs
        }
    }

    private fun migrateLegacyValues(encryptedPrefs: SharedPreferences) {
        val legacyValues = listOf(KEY_PREFIX, KEY_SSID, KEY_PASSWORD)
            .mapNotNull { key -> legacyPrefs.getString(key, null)?.let { key to it } }
        if (legacyValues.isEmpty()) return

        encryptedPrefs.edit().apply {
            legacyValues.forEach { (key, value) -> putString(key, value) }
        }.apply()
        legacyPrefs.edit().apply {
            legacyValues.forEach { (key, _) -> remove(key) }
        }.apply()
    }

    private fun showEncryptionWarning() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                appContext,
                "Dash WiFi settings are using fallback storage.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    companion object {
        private const val TAG = "DashConfig"
        private const val PREFS_NAME = "dash_config"
        private const val KEY_PREFIX   = "ssid_prefix"
        private const val KEY_SSID     = "ssid"
        private const val KEY_PASSWORD = "password"
        private const val KEY_MODE     = "dash_mode"
        private const val KEY_MUSIC_APP = "music_app"
        private const val KEY_VISIBLE_SLOTS = "visible_custom_slots"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_SPEED_ALERT = "speed_alert_kmh"
        private const val KEY_GOOGLE_PLACES = "google_places_key"
        private const val KEY_PROJECT_IDLE = "project_when_idle"
        const val DEFAULT_PREFIX   = "RE_"
        const val DEFAULT_PASSWORD = "12345678"

        @Volatile private var instance: DashConfig? = null
        fun get(context: Context): DashConfig =
            instance ?: synchronized(this) {
                instance ?: DashConfig(context).also { instance = it }
            }
    }
}
