package com.valhalla.valhalla

/**
 * JNI binding to the Valhalla routing engine (libvalhalla-wrapper.so).
 *
 * The native symbol `Java_com_valhalla_valhalla_ValhallaKotlin_route` in the
 * prebuilt .so binds by this exact class name + package, so both must stay
 * unchanged. The .so is sourced from Rallista/valhalla-mobile 0.5.1 (MIT).
 *
 * [route] takes a Valhalla route request JSON string and the absolute path to a
 * valhalla.json config file, and returns the response JSON. All request building
 * and response parsing is done in Kotlin (see Router), so we don't depend on any
 * generated model classes.
 */
class ValhallaKotlin {
    external fun route(request: String, configPath: String): String

    companion object {
        init {
            System.loadLibrary("valhalla-wrapper")
        }
    }
}
