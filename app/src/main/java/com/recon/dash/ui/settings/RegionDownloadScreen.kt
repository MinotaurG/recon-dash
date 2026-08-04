package com.recon.dash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.recon.dash.data.Region
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
    val regions = viewModel.regions
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val installedRegionId by viewModel.installedRegionId.collectAsStateWithLifecycle()
    val installedSizeMb by viewModel.installedSizeMb.collectAsStateWithLifecycle()
    val suggestedId by viewModel.suggestedRegionId.collectAsStateWithLifecycle()
    val indiaMapInstalled by viewModel.indiaMapInstalled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Region Data",
                color = OnSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = OnSurfaceDim, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Download routing data for offline navigation",
            color = OnSurface.copy(alpha = 0.4f),
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(16.dp))

        when (val state = downloadState) {
            is DownloadState.Downloading -> {
                DownloadProgressCard(state)
                Spacer(Modifier.height(16.dp))
            }
            is DownloadState.Complete -> {
                StatusCard("${state.regionName} installed", Color(0xFF10B981))
                Spacer(Modifier.height(16.dp))
            }
            is DownloadState.Failed -> {
                StatusCard(state.message, Color(0xFFEF4444))
                Spacer(Modifier.height(16.dp))
            }
            else -> {}
        }

        if (installed) {
            var showDeleteConfirm by remember { mutableStateOf(false) }
            val name = viewModel.installedRegionName ?: "Offline maps"
            val sizeMb = installedSizeMb
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(text = name, color = Color(0xFF10B981), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = if (sizeMb >= 1024) "%.1f GB on device".format(sizeMb / 1024f)
                               else "$sizeMb MB on device",
                        color = OnSurface.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                }
                Text(
                    text = "Delete",
                    color = Color(0xFFEF4444),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { showDeleteConfirm = true },
                )
            }
            Spacer(Modifier.height(16.dp))

            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("Delete offline maps?") },
                    text = {
                        Text(
                            "This removes $name (" +
                                (if (sizeMb >= 1024) "%.1f GB".format(sizeMb / 1024f) else "$sizeMb MB") +
                                ") from this device. You can download it again anytime. " +
                                "Navigation will fall back to online routing until then."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.clearGraph(); showDeleteConfirm = false }) {
                            Text("Delete", color = Color(0xFFEF4444))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                    },
                )
            }
        }

        // India base map — one download for map DISPLAY everywhere (separate from routing zones).
        Text(
            text = "Base map",
            color = OnSurface.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        var showMapDeleteConfirm by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("India map", color = OnSurface, fontSize = 14.sp)
                Text(
                    text = "~%.1f GB · shown on the dash everywhere".format(viewModel.indiaMapSizeMb / 1024f),
                    color = OnSurface.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                )
            }
            when {
                indiaMapInstalled -> Text(
                    "Delete", color = Color(0xFFEF4444), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { showMapDeleteConfirm = true },
                )
                downloadState is DownloadState.Downloading -> Text(
                    "…", color = OnSurface.copy(alpha = 0.4f), fontSize = 13.sp,
                )
                else -> TextButton(onClick = { viewModel.downloadIndiaMap() }) {
                    Text("Download", color = GoldAccent, fontSize = 13.sp)
                }
            }
        }
        if (showMapDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showMapDeleteConfirm = false },
                title = { Text("Delete India map?") },
                text = { Text("Removes the ~2 GB base map. The dash map will fall back to online tiles until you download it again.") },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearIndiaMap(); showMapDeleteConfirm = false }) {
                        Text("Delete", color = Color(0xFFEF4444))
                    }
                },
                dismissButton = { TextButton(onClick = { showMapDeleteConfirm = false }) { Text("Cancel") } },
            )
        }
        Spacer(Modifier.height(20.dp))

        Text(
            text = "Routing regions",
            color = OnSurface.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))

        val sortedRegions = if (suggestedId != null) {
            val suggested = regions.filter { it.id == suggestedId }
            val rest = regions.filter { it.id != suggestedId }
            suggested + rest
        } else regions

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sortedRegions, key = { it.id }) { region ->
                RegionRow(
                    region = region,
                    isDownloading = downloadState is DownloadState.Downloading,
                    isSuggested = region.id == suggestedId,
                    hasUrl = region.graphUrl.isNotBlank(),
                    isInstalled = installedRegionId == region.id,
                    onDownload = { viewModel.download(region) },
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressCard(state: DownloadState.Downloading) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .padding(16.dp),
    ) {
        Text(
            text = "Downloading ${state.regionName}",
            color = OnSurface,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = GoldAccent,
            trackColor = DarkBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${(state.progress * 100).toInt()}%",
            color = OnSurface.copy(alpha = 0.5f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun StatusCard(message: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(12.dp),
    ) {
        Text(text = message, color = color, fontSize = 13.sp)
    }
}

@Composable
private fun RegionRow(
    region: Region,
    isDownloading: Boolean,
    isSuggested: Boolean = false,
    hasUrl: Boolean = false,
    isInstalled: Boolean = false,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSuggested) GoldAccent.copy(alpha = 0.08f) else DarkSurface)
            .then(if (isSuggested) Modifier.border(1.dp, GoldAccent.copy(alpha = 0.3f), RoundedCornerShape(10.dp)) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = region.name, color = OnSurface, fontSize = 14.sp, fontWeight = if (isSuggested) FontWeight.SemiBold else FontWeight.Normal)
                if (isSuggested) {
                    Text(text = "Suggested", color = GoldAccent, fontSize = 11.sp)
                }
            }
            Text(
                text = "~${region.graphSizeMb} MB routing graph",
                color = OnSurface.copy(alpha = 0.4f),
                fontSize = 12.sp,
            )
        }
        if (isInstalled) {
            Text("Installed", color = Color(0xFF10B981), fontSize = 12.sp)
        } else if (!isDownloading && hasUrl) {
            TextButton(onClick = onDownload) {
                Text("Download", color = GoldAccent, fontSize = 13.sp)
            }
        } else if (!isDownloading && !hasUrl) {
            Text("Coming soon", color = OnSurfaceDim.copy(alpha = 0.4f), fontSize = 12.sp)
        }
    }
}
