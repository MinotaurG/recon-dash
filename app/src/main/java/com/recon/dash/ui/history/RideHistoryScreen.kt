package com.recon.dash.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.data.RideRecord
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RideHistoryScreen(
    onBack: () -> Unit,
    onRideTap: (Long) -> Unit = {},
    viewModel: RideHistoryViewModel = hiltViewModel(),
) {
    val rides by viewModel.rides.collectAsStateWithLifecycle()
    val totalKm by viewModel.totalKm.collectAsStateWithLifecycle()

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
                text = "Ride History",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurface)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatBlock(value = "${rides.size}", label = "Rides")
            StatBlock(value = "%.0f km".format(totalKm), label = "Total")
        }

        Spacer(Modifier.height(20.dp))

        if (rides.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No rides recorded yet",
                    color = OnSurface.copy(alpha = 0.3f),
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rides, key = { it.id }) { ride ->
                    RideCard(ride, onClick = { onRideTap(ride.id) })
                }
            }
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text = label, color = OnSurface.copy(alpha = 0.45f), fontSize = 12.sp)
    }
}

@Composable
private fun RideCard(ride: RideRecord, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = ride.destinationName.ifBlank { "Ride" },
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = dateFormat.format(Date(ride.startTime)),
                color = OnSurface.copy(alpha = 0.4f),
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MiniStat("%.1f km".format(ride.distanceMeters / 1000.0))
            MiniStat(formatDuration(ride.durationSeconds))
            MiniStat("%.0f km/h avg".format(ride.avgSpeedKmh))
        }
    }
}

@Composable
private fun MiniStat(text: String) {
    Text(text = text, color = OnSurface.copy(alpha = 0.6f), fontSize = 12.sp)
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
