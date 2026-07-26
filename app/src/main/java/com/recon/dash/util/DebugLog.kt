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
    private const val PART_ROLL_BYTES = 20_000_000L  // start a new gzipped part at ~20 MB
    private const val RECENT_MAX = 500
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    @Volatile private var logDir: File? = null

    /**
     * Call once at app start. Each launch opens a NEW timestamped session file — we NEVER
     * overwrite a prior ride's log. When a file grows past [PART_ROLL_BYTES] we gzip it and
     * open a fresh part, so nothing is ever lost during a debugging phase. Pull the whole
     * `logs/` dir after a ride.
     */
    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
            logDir = dir
            // Gzip any leftover uncompressed .log files from previous sessions (keep, don't delete).
            dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { gzipInPlace(it) }
            logFile = File(dir, "session-${fileStamp.format(Date())}.log")
            writeLine("==== log session ${ts.format(Date())} (v${BuildConfig.VERSION_NAME}) ====")
        }
    }

    private fun gzipInPlace(src: File) = runCatching {
        val gz = File(src.parentFile, src.name + ".gz")
        java.util.zip.GZIPOutputStream(gz.outputStream()).use { out -> src.inputStream().use { it.copyTo(out) } }
        src.delete()
    }

    private fun rollIfNeeded() {
        val f = logFile ?: return
        val dir = logDir ?: return
        if (f.length() < PART_ROLL_BYTES) return
        gzipInPlace(f)  // compress the finished part, keep it
        logFile = File(dir, "session-${fileStamp.format(Date())}.log")
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
        if (logFile == null) return
        // Serialize file work on the single io thread (append + roll check off the caller thread).
        io.execute {
            runCatching {
                rollIfNeeded()
                logFile?.appendText(line + "\n")
            }
        }
        recent.add(line); while (recent.size > RECENT_MAX) recent.poll()
    }
}
