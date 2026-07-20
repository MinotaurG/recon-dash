package com.recon.dash.ui.history

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RideDetailScreen(
    onBack: () -> Unit,
    onNavigateAgain: (Double, Double, String) -> Unit,
    viewModel: RideDetailViewModel = hiltViewModel(),
) {
    val ride by viewModel.ride.collectAsStateWithLifecycle()

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
                text = "Ride Detail",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        val r = ride
        if (r == null) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = "Loading...",
                color = OnSurfaceDim,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            return
        }

        Spacer(Modifier.height(24.dp))

        // Destination + date
        Text(
            text = r.destinationName.ifBlank { "Ride" },
            color = OnSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        val dateFormat = SimpleDateFormat("EEEE, dd MMM yyyy 'at' HH:mm", Locale.getDefault())
        Text(
            text = dateFormat.format(Date(r.startTime)),
            color = OnSurfaceDim,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(24.dp))

        // Stats grid
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DarkSurface)
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatBlock(
                value = "%.1f".format(r.distanceMeters / 1000.0),
                unit = "km",
                label = "Distance",
            )
            StatBlock(
                value = formatDuration(r.durationSeconds),
                unit = "",
                label = "Duration",
            )
            StatBlock(
                value = "%.0f".format(r.avgSpeedKmh),
                unit = "km/h",
                label = "Avg Speed",
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(DarkSurface)
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatBlock(
                value = "%.0f".format(r.maxSpeedKmh),
                unit = "km/h",
                label = "Max Speed",
            )
            StatBlock(
                value = formatTime(r.startTime),
                unit = "",
                label = "Started",
            )
            StatBlock(
                value = if (r.endTime > 0) formatTime(r.endTime) else "--",
                unit = "",
                label = "Ended",
            )
        }

        Spacer(Modifier.weight(1f))

        // Navigate again button
        if (r.endLat != 0.0 && r.endLng != 0.0) {
            Button(
                onClick = { onNavigateAgain(r.endLat, r.endLng, r.destinationName) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldAccent,
                    contentColor = DarkBackground,
                ),
            ) {
                Text(
                    text = "Navigate Again",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        TextButton(
            onClick = { viewModel.delete(); onBack() },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("Delete Ride", color = Error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatBlock(value: String, unit: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = OnSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = unit,
                    color = OnSurfaceDim,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = OnSurfaceDim,
            fontSize = 11.sp,
        )
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun formatTime(millis: Long): String {
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(Date(millis))
}
