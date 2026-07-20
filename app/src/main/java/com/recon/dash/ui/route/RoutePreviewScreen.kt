package com.recon.dash.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface

@Composable
fun RoutePreviewScreen(
    onStartNav: () -> Unit,
    onBack: () -> Unit,
    onDownloadRegion: () -> Unit = {},
    viewModel: RoutePreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
        ) {
            Text(
                text = "Back",
                color = GoldAccent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            Text(
                text = "Route",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(24.dp))

        when (val s = state) {
            is RoutePreviewState.Loading -> LoadingCard()
            is RoutePreviewState.Ready -> ReadyCard(s, onStartNav)
            is RoutePreviewState.Error -> ErrorCard(s.message, onRetry = { viewModel.retry() })
            is RoutePreviewState.NoGraph -> NoGraphCard(onDownload = onDownloadRegion)
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
private fun ReadyCard(state: RoutePreviewState.Ready, onStartNav: () -> Unit) {
    Column {
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
