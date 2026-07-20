package com.recon.dash.data

import android.location.Location
import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.dash.nav.PolylineCodec
import com.recon.dash.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class RideStats(
    val distanceMeters: Double = 0.0,
    val durationSeconds: Long = 0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val pointCount: Int = 0,
)

@Singleton
class RideRecorder @Inject constructor(
    private val dao: RideRecordDao,
) {
    companion object {
        private const val TAG = "RideRecorder"
        private const val MIN_RECORD_DISTANCE_M = 5.0
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _stats = MutableStateFlow(RideStats())
    val stats = _stats.asStateFlow()

    private var startTime: Long = 0
    private var currentRecordId: Long = 0
    private var destinationName: String = ""
    private var totalDistance: Double = 0.0
    private var maxSpeed: Float = 0f
    private var lastLocation: Location? = null
    private val trackPoints = ArrayList<GeoPoint>()

    suspend fun start(destination: String, startLat: Double, startLng: Double): Long {
        if (_isRecording.value) return currentRecordId

        startTime = System.currentTimeMillis()
        destinationName = destination
        totalDistance = 0.0
        maxSpeed = 0f
        lastLocation = null
        trackPoints.clear()
        trackPoints.add(GeoPoint(startLat, startLng))

        val record = RideRecord(
            startTime = startTime,
            destinationName = destination,
            startLat = startLat,
            startLng = startLng,
        )
        currentRecordId = dao.insert(record)
        _isRecording.value = true
        _stats.value = RideStats()
        DebugLog.i(TAG) { "Recording started — id=$currentRecordId dest=$destination" }
        return currentRecordId
    }

    fun addPoint(location: Location) {
        if (!_isRecording.value) return

        val point = GeoPoint(location.latitude, location.longitude)
        val last = lastLocation

        if (last != null) {
            val dist = last.distanceTo(location).toDouble()
            if (dist < MIN_RECORD_DISTANCE_M) return
            totalDistance += dist
        }

        trackPoints.add(point)
        lastLocation = location

        val speedKmh = location.speed * 3.6f
        if (speedKmh > maxSpeed) maxSpeed = speedKmh

        val elapsed = (System.currentTimeMillis() - startTime) / 1000L
        val avgSpeed = if (elapsed > 0) (totalDistance / 1000.0) / (elapsed / 3600.0) else 0.0

        _stats.value = RideStats(
            distanceMeters = totalDistance,
            durationSeconds = elapsed,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed.toDouble(),
            pointCount = trackPoints.size,
        )
    }

    suspend fun stop(): RideRecord? {
        if (!_isRecording.value) return null
        _isRecording.value = false

        val endTime = System.currentTimeMillis()
        val duration = (endTime - startTime) / 1000L
        val avgSpeed = if (duration > 0) (totalDistance / 1000.0) / (duration / 3600.0) else 0.0
        val endPoint = trackPoints.lastOrNull()
        val encodedTrack = PolylineCodec.encode(trackPoints)

        val record = RideRecord(
            id = currentRecordId,
            startTime = startTime,
            endTime = endTime,
            distanceMeters = totalDistance,
            durationSeconds = duration,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed.toDouble(),
            destinationName = destinationName,
            startLat = trackPoints.firstOrNull()?.lat ?: 0.0,
            startLng = trackPoints.firstOrNull()?.lng ?: 0.0,
            endLat = endPoint?.lat ?: 0.0,
            endLng = endPoint?.lng ?: 0.0,
            encodedPolyline = encodedTrack,
        )
        dao.update(record)

        DebugLog.i(TAG) { "Recording stopped — ${totalDistance.toInt()}m, ${trackPoints.size} points, ${duration}s" }
        trackPoints.clear()
        return record
    }

    suspend fun discard() {
        if (!_isRecording.value) return
        _isRecording.value = false
        dao.deleteById(currentRecordId)
        trackPoints.clear()
        DebugLog.i(TAG) { "Recording discarded — id=$currentRecordId" }
    }
}
