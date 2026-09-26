package com.recon.dash.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.ui.map.MapViewComposable
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.DarkSurfaceElevated
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface
import com.recon.dash.ui.theme.OnSurfaceDim

@Composable
fun RoutePreviewScreen(
    onStartNav: () -> Unit,
    onBack: () -> Unit,
    onDownloadRegion: () -> Unit = {},
    viewModel: RoutePreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val geometry by viewModel.selectedGeometry.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Map preview with the selected route drawn + destination pin.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
        ) {
            MapViewComposable(
                modifier = Modifier.fillMaxSize(),
                centerLat = viewModel.destLat,
                centerLng = viewModel.destLng,
                zoom = 12.0,
                routeGeometry = geometry,
                destination = com.recon.dash.dash.nav.GeoPoint(viewModel.destLat, viewModel.destLng),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                is RoutePreviewState.Loading -> LoadingCard()
                is RoutePreviewState.Ready -> ReadyContent(
                    state = s,
                    onStartNav = onStartNav,
                    onSelectAlternative = { viewModel.selectAlternative(it) },
                    onToggleAvoidTolls = { viewModel.toggleAvoidTolls() },
                    onSetMode = { viewModel.setMode(it) },
                    onDownloadRegion = onDownloadRegion,
                )
                is RoutePreviewState.Error -> ErrorCard(s.message, onRetry = { viewModel.retry() })
                is RoutePreviewState.NoGraph -> NoGraphCard(onDownload = onDownloadRegion)
                is RoutePreviewState.RegionMissing -> RegionMissingCard(
                    state = s,
                    onDownload = onDownloadRegion,
                    onRetry = { viewModel.retry() },
                )
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = GoldAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Calculating route",
                    color = OnSurface.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: RoutePreviewState.Ready,
    onStartNav: () -> Unit,
    onSelectAlternative: (Int) -> Unit,
    onToggleAvoidTolls: () -> Unit,
    onSetMode: (com.recon.dash.dash.nav.RideMode) -> Unit,
    onDownloadRegion: () -> Unit = {},
) {
    Column {
        // Offline download suggestion: shown whenever this origin's bundle isn't installed
        // (you're navigating a region you don't have offline), regardless of whether the route
        // itself came from online fallback or a neighbouring installed bundle.
        if (state.downloadableRegionName != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDownloadRegion),
                colors = CardDefaults.cardColors(containerColor = GoldAccent.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        tint = GoldAccent,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Download ${state.downloadableRegionName}",
                            color = GoldAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (state.isOnlineRoute)
                                "This route was calculated online. Download for offline navigation."
                            else
                                "You don't have this region offline yet. Download for offline navigation.",
                            color = OnSurface.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Route info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = state.destinationName,
                    color = OnSurface,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatItem(label = "Distance", value = state.distanceText)
                    StatItem(label = "ETA", value = state.etaText)
                    StatItem(label = "Turns", value = state.turnCount.toString())
                }
            }
        }

        // Alternative routes (only show if more than one)
        if (state.alternatives.size > 1) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.alternatives.forEachIndexed { index, alt ->
                    AlternativeCard(
                        index = index,
                        choice = alt,
                        onClick = { onSelectAlternative(index) },
                    )
                }
            }
        }

        // Ride mode presets (Valhalla motorcycle costing bundles). Horizontally scrollable so all
        // presets fit on the narrow screen; the picked one recomputes the route.
        Spacer(Modifier.height(16.dp))
        Text("Ride mode", color = OnSurface.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.recon.dash.dash.nav.RideMode.entries.forEach { m ->
                ToggleChip(
                    label = m.label,
                    selected = state.mode == m,
                    onClick = { onSetMode(m) },
                )
            }
        }

        // Independent toll toggle (applies on top of any mode).
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleChip(
                label = "Avoid tolls",
                selected = state.avoidTolls,
                onClick = onToggleAvoidTolls,
            )
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onStartNav,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GoldAccent,
                contentColor = DarkBackground,
            ),
        ) {
            Text(
                text = "Start Navigation",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (state.maneuvers.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Directions",
                color = OnSurface.copy(alpha = 0.5f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.maneuvers.take(10).forEach { step ->
                    Text(
                        text = step,
                        color = OnSurface.copy(alpha = 0.75f),
                        fontSize = 13.sp,
                    )
                }
                if (state.maneuvers.size > 10) {
                    Text(
                        text = "+${state.maneuvers.size - 10} more",
                        color = OnSurface.copy(alpha = 0.35f),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AlternativeCard(
    index: Int,
    choice: RouteChoice,
    onClick: () -> Unit,
) {
    val borderColor = if (choice.isSelected) GoldAccent else Color.Transparent
    val bgColor = if (choice.isSelected) DarkSurfaceElevated else DarkSurface

    Card(
        modifier = Modifier
            .width(140.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Route ${index + 1}",
                color = if (choice.isSelected) GoldAccent else OnSurfaceDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = choice.distanceText,
                color = OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = choice.etaText,
                color = OnSurfaceDim,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (selected) GoldAccent.copy(alpha = 0.15f) else DarkSurface
    val borderColor = if (selected) GoldAccent.copy(alpha = 0.5f) else OnSurfaceDim.copy(alpha = 0.2f)
    val textColor = if (selected) GoldAccent else OnSurfaceDim

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = GoldAccent,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = OnSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = OnSurface.copy(alpha = 0.45f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Route failed",
                color = Color(0xFFEF4444),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = OnSurface.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onRetry) {
                Text("Retry", color = GoldAccent)
            }
        }
    }
}

@Composable
private fun NoGraphCard(onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Routing data not available",
                color = OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Download your region's map data to enable offline routing.",
                color = OnSurface.copy(alpha = 0.5f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDownload) {
                Text("Download Region", color = GoldAccent)
            }
        }
    }
}

@Composable
private fun RegionMissingCard(
    state: RoutePreviewState.RegionMissing,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "${state.regionName} map not downloaded",
                color = OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.available) {
                    "You're in ${state.regionName}, but its offline map isn't installed. " +
                        "Download it (${state.sizeMb} MB) to route here."
                } else {
                    "You're in ${state.regionName}, but its offline map isn't available yet. " +
                        "Routing here needs it installed."
                },
                color = OnSurface.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.available) {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GoldAccent, contentColor = Color.Black,
                        ),
                    ) {
                        Text("Download ${state.regionName}", fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = onRetry) {
                    Text("Retry", color = OnSurface.copy(alpha = 0.7f))
                }
            }
        }
    }
}
