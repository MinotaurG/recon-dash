package com.recon.dash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@Composable
fun RegionDownloadScreen(
    onBack: () -> Unit,
    viewModel: RegionDownloadViewModel = hiltViewModel(),
) {
    val regions by viewModel.regions.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Back",
                color = GoldAccent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            Text(
                text = "Region Data",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Graph installed", color = Color(0xFF10B981), fontSize = 13.sp)
                Text(
                    text = "Clear",
                    color = Color(0xFFEF4444).copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { viewModel.clearGraph() },
                )
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(
            text = "Available regions",
            color = OnSurface.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(regions, key = { it.id }) { region ->
                RegionRow(
                    region = region,
                    isDownloading = downloadState is DownloadState.Downloading,
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
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(text = region.name, color = OnSurface, fontSize = 14.sp)
            Text(
                text = "~${region.sizeMb} MB",
                color = OnSurface.copy(alpha = 0.4f),
                fontSize = 12.sp,
            )
        }
        if (!isDownloading) {
            TextButton(onClick = onDownload) {
                Text("Download", color = GoldAccent, fontSize = 13.sp)
            }
        }
    }
}
