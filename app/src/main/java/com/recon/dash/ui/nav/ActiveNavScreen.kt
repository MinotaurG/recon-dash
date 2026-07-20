package com.recon.dash.ui.nav

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        DashStatusBadge(dashState)

        Spacer(Modifier.height(40.dp))

        // Main ETA / distance glance
        Text(
            text = navState.etaText,
            color = Color(0xFF7ED957),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = navState.remainingText,
            color = OnSurface.copy(alpha = 0.6f),
            fontSize = 16.sp,
        )

        Spacer(Modifier.height(48.dp))

        // Next maneuver card
        if (navState.nextInstruction != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = navState.distToTurnText,
                        color = GoldAccent,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = navState.nextInstruction ?: "",
                        color = OnSurface,
                        fontSize = 15.sp,
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Destination
        Text(
            text = navState.destinationName,
            color = OnSurface.copy(alpha = 0.4f),
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3A2020),
                contentColor = Color(0xFFCC6666),
            ),
        ) {
            Text(
                text = "End Navigation",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DashStatusBadge(status: String) {
    val isConnected = status == "Streaming"
    val color = if (isConnected) Color(0xFF10B981) else OnSurface.copy(alpha = 0.3f)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
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
