package com.recon.dash.util

import android.content.Context
import android.location.LocationManager
import com.recon.dash.dash.nav.GeoPoint

object LocationHelper {
    fun getLastKnown(context: Context): GeoPoint? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            @Suppress("MissingPermission")
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            loc?.let { GeoPoint(it.latitude, it.longitude) }
        } catch (e: SecurityException) {
            null
        }
    }
}
