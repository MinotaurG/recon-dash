package com.recon.dash.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.recon.dash.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Listens for incoming-call state via [TelephonyManager]/[TelephonyCallback] and exposes the
 * ringing caller's display name (resolved from contacts, falling back to the number) as a
 * StateFlow. The DashViewModel/NavDashBridge forwards it to the dash's Phone card (05 22 packet),
 * so an incoming call shows on the dash without the rider touching the phone.
 *
 * The dash-side packet (DashCommands.callNotify/callClear + DashSession.updateCall) already
 * existed but nothing drove it — this is the missing phone-side event source.
 *
 * Needs READ_PHONE_STATE (call state) and READ_CONTACTS (name lookup). Degrades gracefully:
 * without READ_PHONE_STATE we simply never fire; without READ_CONTACTS we show the number.
 */
object CallStateListener {

    private const val TAG = "CallStateListener"

    /** Ringing caller (contact name or number), or null when no incoming call is active. */
    private val _incomingCaller = MutableStateFlow<String?>(null)
    val incomingCaller = _incomingCaller.asStateFlow()

    private var telephonyManager: TelephonyManager? = null
    private var callback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var legacyListener: android.telephony.PhoneStateListener? = null
    private var appContext: Context? = null

    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            DebugLog.w(TAG) { "READ_PHONE_STATE not granted — call notifications disabled" }
            return
        }
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        telephonyManager = tm
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = handleState(state)
                }
                callback = cb
                tm.registerTelephonyCallback(ctx.mainExecutor, cb)
            } else {
                @Suppress("DEPRECATION")
                val l = object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        handleState(state, phoneNumber)
                }
                legacyListener = l
                @Suppress("DEPRECATION")
                tm.listen(l, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            }
            DebugLog.i(TAG) { "Started listening for call state" }
        } catch (e: SecurityException) {
            DebugLog.w(TAG) { "Call-state listen denied: ${e.message}" }
        }
    }

    fun stop() {
        val tm = telephonyManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                callback?.let { tm?.unregisterTelephonyCallback(it) }
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let { tm?.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE) }
            }
        } catch (_: Exception) { /* best-effort */ }
        callback = null
        legacyListener = null
        telephonyManager = null
        _incomingCaller.value = null
    }

    // Newer callback gives no number; we resolve nothing to name there and rely on the ringing state
    // alone (many OEMs withhold the number from the number-less callback anyway). The legacy path
    // passes the number so we can resolve a contact name.
    private fun handleState(state: Int, phoneNumber: String? = null) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val display = phoneNumber?.let { resolveContactName(it) ?: it } ?: "Incoming call"
                _incomingCaller.value = display
                DebugLog.i(TAG) { "Incoming call: $display" }
            }
            // Off-hook (answered) or idle (ended/declined) → clear the dash card.
            else -> {
                if (_incomingCaller.value != null) DebugLog.i(TAG) { "Call ended/answered — clearing" }
                _incomingCaller.value = null
            }
        }
    }

    /** Look up a contact display name for [number], or null (no permission / not found). */
    private fun resolveContactName(number: String): String? {
        val ctx = appContext ?: return null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}
