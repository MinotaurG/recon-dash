package com.recon.dash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.data.DownloadState
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface
import com.recon.dash.ui.theme.OnSurfaceDim

@Composable
fun RegionDownloadScreen(
    onBack: () -> Unit,
    viewModel: RegionDownloadViewModel = hiltViewModel(),
) {
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val installedSizeMb by viewModel.installedSizeMb.collectAsStateWithLifecycle()
    val routingInstalled by viewModel.routingInstalled.collectAsStateWithLifecycle()
    val routingUpdateAvailable by viewModel.routingUpdateAvailable.collectAsStateWithLifecycle()
    val indiaMapInstalled by viewModel.indiaMapInstalled.collectAsStateWithLifecycle()
    val mapUpdateAvailable by viewModel.mapUpdateAvailable.collectAsStateWithLifecycle()

    val busy = downloadState is DownloadState.Downloading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Offline Maps", color = OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = OnSurfaceDim, modifier = Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Download India once — the map you see and the routing behind it. Both work fully offline, anywhere in the country.",
            color = OnSurface.copy(alpha = 0.4f), fontSize = 12.sp,
        )
        Spacer(Modifier.height(16.dp))

        // Live download status
        when (val s = downloadState) {
            is DownloadState.Downloading -> { DownloadProgressCard(s); Spacer(Modifier.height(16.dp)) }
            is DownloadState.Complete -> { StatusCard("${s.regionName} installed", Color(0xFF10B981)); Spacer(Modifier.height(16.dp)) }
            is DownloadState.Failed -> { StatusCard(s.message, Color(0xFFEF4444)); Spacer(Modifier.height(16.dp)) }
            else -> {}
        }

        // Total installed
        if (installedSizeMb > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmtSize(installedSizeMb) + " on device", color = Color(0xFF10B981), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(16.dp))
        }

        // India map (display)
        SectionLabel("Map (what you see)")
        AssetCard(
            title = "India map",
            subtitle = "~${fmtSize(viewModel.indiaMapSizeMb)} · shown on the dash everywhere",
            installed = indiaMapInstalled,
            updateAvailable = mapUpdateAvailable,
            busy = busy,
            deleteTitle = "Delete India map?",
            deleteBody = "Removes the ~2 GB base map. The dash map falls back to online tiles until you re-download.",
            onDownload = { viewModel.downloadIndiaMap() },
            onDelete = { viewModel.clearIndiaMap() },
        )
        Spacer(Modifier.height(20.dp))

        // India routing
        SectionLabel("Routing (how routes are found)")
        AssetCard(
            title = "India routing",
            subtitle = "~${fmtSize(viewModel.routingSizeMb)} · route anywhere in India offline",
            installed = routingInstalled,
            updateAvailable = routingUpdateAvailable,
            busy = busy,
            deleteTitle = "Delete India routing?",
            deleteBody = "Removes the ~4 GB routing data. Navigation falls back to online routing until you re-download.",
            onDownload = { viewModel.downloadIndiaRouting() },
            onDelete = { viewModel.clearRouting() },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun SectionLabel(text: String) {
    Text(text, color = OnSurface.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}

private fun fmtSize(mb: Int): String =
    if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "$mb MB"

/** A single downloadable asset (map or routing) with download / update / delete affordances. */
@Composable
private fun AssetCard(
    title: String,
    subtitle: String,
    installed: Boolean,
    updateAvailable: Boolean,
    busy: Boolean,
    deleteTitle: String,
    deleteBody: String,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DarkSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = OnSurface, fontSize = 14.sp)
                if (updateAvailable) Text("Update available", color = GoldAccent, fontSize = 10.sp)
            }
            Text(subtitle, color = OnSurface.copy(alpha = 0.4f), fontSize = 12.sp)
        }
        when {
            busy -> Text("…", color = OnSurface.copy(alpha = 0.4f), fontSize = 13.sp)
            updateAvailable -> TextButton(onClick = onDownload) { Text("Update", color = GoldAccent, fontSize = 13.sp) }
            installed -> Text("Delete", color = Color(0xFFEF4444), fontSize = 13.sp, modifier = Modifier.clickable { confirm = true })
            else -> TextButton(onClick = onDownload) { Text("Download", color = GoldAccent, fontSize = 13.sp) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(deleteTitle) },
        text = { Text(deleteBody) },
        confirmButton = { TextButton(onClick = { onDelete(); confirm = false }) { Text("Delete", color = Color(0xFFEF4444)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun DownloadProgressCard(state: DownloadState.Downloading) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DarkSurface).padding(16.dp),
    ) {
        Text("Downloading ${state.regionName}", color = OnSurface, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = GoldAccent, trackColor = DarkBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text("${(state.progress * 100).toInt()}%", color = OnSurface.copy(alpha = 0.5f), fontSize = 12.sp)
    }
}

@Composable
private fun StatusCard(message: String, color: Color) {
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.1f)).padding(12.dp)) {
        Text(message, color = color, fontSize = 13.sp)
    }
}
