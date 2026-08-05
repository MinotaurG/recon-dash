package com.recon.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.dash.DashMode
import com.recon.dash.dash.DashState
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface

@Composable
fun TestScreen(
    onTelemetryLabTap: () -> Unit = {},
    viewModel: DashViewModel = hiltViewModel(),
) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val log by viewModel.protocolLog.collectAsStateWithLifecycle()
    val pendingPairing by viewModel.pendingPairingSsid.collectAsStateWithLifecycle()
    val glyphProbeRunning by viewModel.glyphProbeRunning.collectAsStateWithLifecycle()
    val glyphProbeCode by viewModel.glyphProbeCode.collectAsStateWithLifecycle()

    val isIdle = state == DashState.IDLE || state == DashState.ERROR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 24.dp)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text = "RECON DASH",
            color = OnSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )

        Spacer(Modifier.height(20.dp))

        StateChip(state)

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Mode",
            color = OnSurface.copy(alpha = 0.6f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))

        ModeSelector(
            selected = mode,
            enabled = isIdle,
            onSelect = { viewModel.setMode(it) },
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { if (isIdle) viewModel.connect() else viewModel.disconnect() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isIdle) GoldAccent else Color(0xFF3A2020),
                contentColor = if (isIdle) DarkBackground else Color(0xFFCC6666),
            ),
        ) {
            Text(
                text = if (isIdle) "Connect" else "Disconnect",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // First-time pairing prompt: confirm the discovered dash is the rider's bike.
        pendingPairing?.let { ssid ->
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .padding(16.dp),
            ) {
                Text("Dash found", color = GoldAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Pair with \"$ssid\"?",
                    color = OnSurface,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { viewModel.confirmPairing() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GoldAccent, contentColor = DarkBackground),
                    ) { Text("Pair", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = { viewModel.rejectPairing() },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurface.copy(alpha = 0.7f)),
                    ) { Text("Cancel", fontSize = 14.sp) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onTelemetryLabTap,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GoldAccent),
        ) {
            Text("Telemetry Lab", fontSize = 14.sp)
        }

        // Glyph probe: sweeps maneuver codes 0x00..0x40 to the dash (5s each) so we can
        // photograph each turn glyph and build the code map. Self-labeling: writes a CSV
        // (filesDir/glyph-probe/) + greppable GLYPHMAP logcat lines to anchor code<->frame
        // exactly, no alignment guesswork. Only useful while streaming.
        if (state == DashState.STREAMING) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    if (glyphProbeRunning) viewModel.stopGlyphProbe()
                    else viewModel.startGlyphProbe()
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (glyphProbeRunning) Color(0xFFCC6666) else GoldAccent,
                ),
            ) {
                val label = when {
                    glyphProbeRunning && glyphProbeCode != null ->
                        "Stop probe  (showing 0x%02X)".format(glyphProbeCode)
                    glyphProbeRunning -> "Stop glyph probe"
                    else -> "Start glyph probe (0x00-0x40)"
                }
                Text(label, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Protocol Log",
            color = OnSurface.copy(alpha = 0.6f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurface)
                .padding(12.dp),
        ) {
            items(log) { entry ->
                Text(
                    text = entry,
                    color = OnSurface.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StateChip(state: DashState) {
    val (label, color) = when (state) {
        DashState.IDLE -> "Idle" to Color(0xFF6B7280)
        DashState.CONNECTING -> "Connecting" to Color(0xFFF59E0B)
        DashState.AUTHENTICATING -> "Authenticating" to Color(0xFFF59E0B)
        DashState.READY -> "Ready" to Color(0xFF10B981)
        DashState.STREAMING -> "Streaming" to Color(0xFF10B981)
        DashState.ERROR -> "Error" to Color(0xFFEF4444)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ModeSelector(
    selected: DashMode,
    enabled: Boolean,
    onSelect: (DashMode) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DarkSurface)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DashMode.entries.forEach { mode ->
            val isSelected = mode == selected
            val label = when (mode) {
                DashMode.ANALOGUE -> "Analogue"
                DashMode.DIGITAL -> "Digital"
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (isSelected) Modifier.background(GoldAccent.copy(alpha = 0.2f))
                        else Modifier
                    )
                    .clickable(enabled = enabled) { onSelect(mode) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) GoldAccent
                            else if (enabled) OnSurface.copy(alpha = 0.5f)
                            else OnSurface.copy(alpha = 0.2f),
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
