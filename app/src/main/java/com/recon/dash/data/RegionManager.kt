package com.recon.dash.data

import android.content.Context
import com.recon.dash.util.DebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

data class Region(
    val id: String,
    val name: String,
    val graphUrl: String,
    val tilesUrl: String = "",
    val graphSizeMb: Int,
    val tilesSizeMb: Int = 0,
) {
    val totalSizeMb: Int get() = graphSizeMb + tilesSizeMb
}

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val regionName: String) : DownloadState()
    data class Complete(val regionName: String) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

@Singleton
class RegionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "RegionManager"
        private const val GRAPH_DIR = "graphhopper"
        private const val BUFFER_SIZE = 8192
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState = _downloadState.asStateFlow()

    private val graphDir = File(context.filesDir, GRAPH_DIR)

    val availableRegions: List<Region> = listOf(
        Region("karnataka", "Karnataka", "", "", graphSizeMb = 80, tilesSizeMb = 60),
        Region("tamil_nadu", "Tamil Nadu", "", "", graphSizeMb = 90, tilesSizeMb = 70),
        Region("kerala", "Kerala", "", "", graphSizeMb = 45, tilesSizeMb = 35),
        Region("maharashtra", "Maharashtra", "", "", graphSizeMb = 180, tilesSizeMb = 120),
        Region("delhi_ncr", "Delhi NCR", "", "", graphSizeMb = 35, tilesSizeMb = 25),
        Region("rajasthan", "Rajasthan", "", "", graphSizeMb = 120, tilesSizeMb = 90),
        Region("goa", "Goa", "", "", graphSizeMb = 15, tilesSizeMb = 10),
        Region("himachal", "Himachal Pradesh", "", "", graphSizeMb = 50, tilesSizeMb = 40),
        Region("uttarakhand", "Uttarakhand", "", "", graphSizeMb = 55, tilesSizeMb = 45),
        Region("andhra_pradesh", "Andhra Pradesh", "", "", graphSizeMb = 100, tilesSizeMb = 75),
    )

    fun isGraphInstalled(): Boolean =
        graphDir.exists() && File(graphDir, "properties").exists()

    fun installedSizeMb(): Int {
        if (!graphDir.exists()) return 0
        val bytes = graphDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return (bytes / (1024 * 1024)).toInt()
    }

    suspend fun downloadRegion(region: Region, url: String): Result<Unit> = withContext(Dispatchers.IO) {
        _downloadState.value = DownloadState.Downloading(0f, region.name)

        try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
            }

            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: (region.totalSizeMb * 1024L * 1024L)
            val tempFile = File(context.cacheDir, "region_download.zip")

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        _downloadState.value = DownloadState.Downloading(
                            progress = (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f),
                            regionName = region.name,
                        )
                    }
                }
            }
            conn.disconnect()

            graphDir.deleteRecursively()
            graphDir.mkdirs()
            unzip(tempFile, graphDir)
            tempFile.delete()

            _downloadState.value = DownloadState.Complete(region.name)
            DebugLog.i(TAG) { "Region ${region.name} downloaded and installed" }
            Result.success(Unit)
        } catch (e: Exception) {
            _downloadState.value = DownloadState.Failed("Download failed: ${e.message}")
            DebugLog.e(TAG, { "Download failed: ${e.message}" }, e)
            Result.failure(e)
        }
    }

    fun clearGraph() {
        graphDir.deleteRecursively()
        _downloadState.value = DownloadState.Idle
    }

    private fun unzip(zipFile: File, destDir: File) {
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
