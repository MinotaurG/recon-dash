package com.recon.dash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.recon.dash.data.RoutingPack
import com.recon.dash.data.RoutingZone
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
    val manifest by viewModel.manifest.collectAsStateWithLifecycle()
    val installedPacks by viewModel.installedPacks.collectAsStateWithLifecycle()
    val installedSizeMb by viewModel.installedSizeMb.collectAsStateWithLifecycle()
    val suggestedStateId by viewModel.suggestedStateId.collectAsStateWithLifecycle()
    val indiaMapInstalled by viewModel.indiaMapInstalled.collectAsStateWithLifecycle()

    val busy = downloadState is DownloadState.Downloading
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

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
            "Base map + the states you ride. Downloaded states stack, so cross-state routes work offline.",
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

        // Total installed + delete-all
        if (installedSizeMb > 0) {
            var showDeleteConfirm by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface).padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(fmtSize(installedSizeMb) + " on device", color = Color(0xFF10B981), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Delete routing", color = Color(0xFFEF4444), fontSize = 13.sp,
                    modifier = Modifier.clickable { showDeleteConfirm = true })
            }
            Spacer(Modifier.height(16.dp))
            if (showDeleteConfirm) AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete all routing packs?") },
                text = { Text("Removes every downloaded state's routing data. The India map stays. You can re-download anytime.") },
                confirmButton = { TextButton(onClick = { viewModel.clearGraph(); showDeleteConfirm = false }) { Text("Delete", color = Color(0xFFEF4444)) } },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
            )
        }

        // India base map (display)
        SectionLabel("Base map (display)")
        IndiaMapCard(indiaMapInstalled, viewModel.indiaMapSizeMb, busy,
            onDownload = { viewModel.downloadIndiaMap() }, onDelete = { viewModel.clearIndiaMap() })
        Spacer(Modifier.height(20.dp))

        // Routing packs
        SectionLabel("Routing")
        val m = manifest
        if (m == null) {
            Text("Loading regions…", color = OnSurface.copy(alpha = 0.4f), fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp))
        } else {
            // Download All India
            val allInstalled = m.allStates.all { installedPacks.contains(it.id) } && installedPacks.contains("base")
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(GoldAccent.copy(alpha = 0.10f))
                    .border(1.dp, GoldAccent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Download all India", color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("~${fmtSize(m.totalMb)} · every state, route anywhere offline",
                        color = OnSurface.copy(alpha = 0.4f), fontSize = 12.sp)
                }
                when {
                    allInstalled -> Text("Installed", color = Color(0xFF10B981), fontSize = 12.sp)
                    busy -> Text("…", color = OnSurface.copy(alpha = 0.4f), fontSize = 13.sp)
                    else -> TextButton(onClick = { viewModel.downloadAll() }) { Text("Download", color = GoldAccent, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(12.dp))

            // Zones (expandable) -> states
            m.zones.forEach { zone ->
                val hasSuggested = zone.states.any { it.id == suggestedStateId }
                val isOpen = expanded[zone.id] ?: hasSuggested
                ZoneHeader(
                    zone = zone,
                    open = isOpen,
                    allInstalled = zone.states.all { installedPacks.contains(it.id) },
                    busy = busy,
                    onToggle = { expanded[zone.id] = !isOpen },
                    onDownloadZone = { viewModel.downloadZone(zone) },
                )
                if (isOpen) {
                    zone.states.forEach { pack ->
                        StatePackRow(
                            pack = pack,
                            installed = installedPacks.contains(pack.id),
                            suggested = pack.id == suggestedStateId,
                            busy = busy,
                            onDownload = { viewModel.downloadPack(pack) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun SectionLabel(text: String) {
    Text(text, color = OnSurface.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
}

private fun fmtSize(mb: Int): String =
    if (mb >= 1024) "%.1f GB".format(mb / 1024f) else "$mb MB"

@Composable
private fun IndiaMapCard(installed: Boolean, sizeMb: Int, busy: Boolean, onDownload: () -> Unit, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DarkSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("India map", color = OnSurface, fontSize = 14.sp)
            Text("~${fmtSize(sizeMb)} · shown on the dash everywhere", color = OnSurface.copy(alpha = 0.4f), fontSize = 12.sp)
        }
        when {
            installed -> Text("Delete", color = Color(0xFFEF4444), fontSize = 13.sp, modifier = Modifier.clickable { confirm = true })
            busy -> Text("…", color = OnSurface.copy(alpha = 0.4f), fontSize = 13.sp)
            else -> TextButton(onClick = onDownload) { Text("Download", color = GoldAccent, fontSize = 13.sp) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete India map?") },
        text = { Text("Removes the ~2 GB base map. The dash map falls back to online tiles until you re-download.") },
        confirmButton = { TextButton(onClick = { onDelete(); confirm = false }) { Text("Delete", color = Color(0xFFEF4444)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun ZoneHeader(zone: RoutingZone, open: Boolean, allInstalled: Boolean, busy: Boolean, onToggle: () -> Unit, onDownloadZone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(DarkSurface)
            .clickable { onToggle() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("${if (open) "▾" else "▸"}  ${zone.name}", color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${zone.states.size} states · ${fmtSize(zone.sizeMb)}", color = OnSurface.copy(alpha = 0.4f), fontSize = 12.sp)
        }
        when {
            allInstalled -> Text("Installed", color = Color(0xFF10B981), fontSize = 12.sp)
            busy -> {}
            else -> TextButton(onClick = onDownloadZone) { Text("Download all", color = GoldAccent, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun StatePackRow(pack: RoutingPack, installed: Boolean, suggested: Boolean, busy: Boolean, onDownload: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (suggested) GoldAccent.copy(alpha = 0.08f) else DarkSurface.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pack.name, color = OnSurface, fontSize = 13.sp, fontWeight = if (suggested) FontWeight.SemiBold else FontWeight.Normal)
                if (suggested) Text("You're here", color = GoldAccent, fontSize = 10.sp)
            }
            Text(fmtSize(pack.sizeMb), color = OnSurface.copy(alpha = 0.4f), fontSize = 11.sp)
        }
        when {
            installed -> Text("✓", color = Color(0xFF10B981), fontSize = 14.sp)
            busy -> {}
            else -> TextButton(onClick = onDownload) { Text("Get", color = GoldAccent, fontSize = 13.sp) }
        }
    }
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
