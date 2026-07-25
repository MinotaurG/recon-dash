package com.recon.dash.util

import android.content.Context
import android.util.Log
import com.recon.dash.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Logs to logcat AND (once [init]ed) to a persistent file on the device, so a ride's nav trace
 * survives the rolling logcat buffer and can be pulled afterward without USB attached during
 * the ride. File lives at: Android/data/<pkg>/files/logs/session-*.log
 *
 * Pull the latest:
 *   adb exec-out run-as com.recon.dash.debug cat files/logs/current.log   (or from external dir)
 */
object DebugLog {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "DebugLog").apply { isDaemon = true } }
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var logFile: File? = null
    // Small in-memory tail so the Telemetry/Test screen can show recent lines if we want later.
    private val recent = ConcurrentLinkedQueue<String>()
    private const val MAX_FILE_BYTES = 5_000_000L   // rotate at ~5 MB
    private const val RECENT_MAX = 500

    /** Call once at app start (Application.onCreate). Safe to call when not debuggable — no-op. */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
            val f = File(dir, "current.log")
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                File(dir, "previous.log").also { it.delete() }; f.renameTo(File(dir, "previous.log"))
            }
            logFile = File(dir, "current.log")
            writeLine("==== log session ${ts.format(Date())} (v${BuildConfig.VERSION_NAME}) ====")
        }
    }

    fun d(tag: String, message: () -> String) = log('D', tag, message)
    fun i(tag: String, message: () -> String) = log('I', tag, message)
    fun w(tag: String, message: () -> String) = log('W', tag, message)
    fun e(tag: String, message: () -> String, error: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val msg = runCatching(message).getOrElse { "?" }
        runCatching { if (error == null) Log.e(tag, msg) else Log.e(tag, msg, error) }
        writeLine("${ts.format(Date())} E/$tag: $msg${error?.let { " :: ${it.stackTraceToString()}" } ?: ""}")
    }

    private inline fun log(level: Char, tag: String, message: () -> String) {
        if (!BuildConfig.DEBUG) return
        val msg = runCatching(message).getOrElse { return }
        runCatching {
            when (level) { 'D' -> Log.d(tag, msg); 'I' -> Log.i(tag, msg); 'W' -> Log.w(tag, msg) }
        }
        writeLine("${ts.format(Date())} $level/$tag: $msg")
    }

    private fun writeLine(line: String) {
        val f = logFile ?: return
        io.execute { runCatching { f.appendText(line + "\n") } }
        recent.add(line); while (recent.size > RECENT_MAX) recent.poll()
    }
}
