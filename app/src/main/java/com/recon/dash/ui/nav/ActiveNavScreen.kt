package com.recon.dash.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.ui.map.MapViewComposable
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface

@Composable
fun ActiveNavScreen(
    onStop: () -> Unit,
    viewModel: ActiveNavViewModel = hiltViewModel(),
) {
    val navState by viewModel.navState.collectAsStateWithLifecycle()
    val dashState by viewModel.dashStatus.collectAsStateWithLifecycle()
    val riderPosition by viewModel.riderPosition.collectAsStateWithLifecycle()
    val riderBearing by viewModel.riderBearing.collectAsStateWithLifecycle()
    val travelledGeometry by viewModel.travelledGeometry.collectAsStateWithLifecycle()
    val aheadGeometry by viewModel.aheadGeometry.collectAsStateWithLifecycle()

    val alertBorderColor by animateColorAsState(
        targetValue = if (navState.speedAlertActive) Color(0xFFFF453A) else Color.Transparent,
        animationSpec = if (navState.speedAlertActive) {
            repeatable(Int.MAX_VALUE, tween(500), RepeatMode.Reverse)
        } else tween(300),
        label = "speedAlert",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .border(
                width = if (navState.speedAlertActive) 3.dp else 0.dp,
                color = alertBorderColor,
            ),
    ) {
        // Map fills the screen with the route drawn
        MapViewComposable(
            modifier = Modifier.fillMaxSize(),
            centerLat = viewModel.destination.lat,
            centerLng = viewModel.destination.lng,
            zoom = 12.0,
            travelledGeometry = travelledGeometry,
            aheadGeometry = aheadGeometry,
            followRider = true,
            destination = viewModel.destination,
            riderLocation = riderPosition,
            riderBearing = riderBearing,
        )

        // Top overlay: maneuver / ETA
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
        ) {
            DashStatusBadge(dashState)
            Spacer(Modifier.height(8.dp))

            if (navState.speedAlertActive) {
                Text("SLOW DOWN", color = Color(0xFFFF453A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (navState.nextInstruction != null) {
                            Text(
                                text = navState.distToTurnText,
                                color = GoldAccent,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = navState.nextInstruction ?: "",
                                color = OnSurface,
                                fontSize = 14.sp,
                            )
                        } else {
                            Text(
                                text = navState.destinationName,
                                color = OnSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = navState.etaText,
                            color = Color(0xFF7ED957),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = navState.remainingText,
                            color = OnSurface.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }

        // Bottom: end navigation
        Button(
            onClick = onStop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(20.dp)
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3A2020),
                contentColor = Color(0xFFCC6666),
            ),
        ) {
            Text("End Navigation", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DashStatusBadge(status: String) {
    val isConnected = status == "Streaming"
    val color = if (isConnected) Color(0xFF10B981) else OnSurface.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DarkSurface.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (isConnected) "Dash connected" else "Dash not connected",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
