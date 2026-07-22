package com.recon.dash.ui.telemetry

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private val quickFields = listOf(
    "odometer_km",
    "speed_kmh",
    "rpm",
    "fuel_percent",
    "engine_temp_c",
    "gear",
    "ambient_temp_c",
)

@Composable
fun TelemetryLabScreen(
    onBack: () -> Unit,
    viewModel: TelemetryLabViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedField by remember { mutableStateOf(quickFields[0]) }
    var inputValue by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Telemetry Lab", color = OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = OnSurfaceDim, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.height(12.dp))

        // Status + Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Recording indicator
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (state.isRecording) Color(0xFFFF3B30) else OnSurfaceDim.copy(alpha = 0.3f)),
            )
            Text(
                text = if (state.isRecording) "${state.packetCount} packets, ${state.uniqueSubs.size} types"
                       else "Not recording",
                color = OnSurfaceDim,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )

            if (!state.isRecording) {
                FilledTonalButton(
                    onClick = { viewModel.startRecording() },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Success.copy(alpha = 0.2f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Record", color = Success, fontSize = 13.sp)
                }
            } else {
                FilledTonalButton(
                    onClick = { viewModel.stopRecording() },
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Error.copy(alpha = 0.2f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Stop", color = Error, fontSize = 13.sp)
                }
            }

            FilledTonalButton(
                onClick = {
                    val path = viewModel.exportSession()
                    if (path != null) Toast.makeText(context, "Saved: $path", Toast.LENGTH_LONG).show()
                    else Toast.makeText(context, "Nothing to export", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = GoldAccent.copy(alpha = 0.2f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text("Export", color = GoldAccent, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Manual entry section
        Text("Log known value", color = OnSurfaceDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Field selector
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurface)
                    .clickable {
                        val idx = quickFields.indexOf(selectedField)
                        selectedField = quickFields[(idx + 1) % quickFields.size]
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(selectedField, color = GoldAccent, fontSize = 13.sp)
            }

            // Value input
            OutlinedTextField(
                value = inputValue,
                onValueChange = { inputValue = it },
                modifier = Modifier.weight(1f).height(44.dp),
                textStyle = LocalTextStyle.current.copy(color = OnSurface, fontSize = 14.sp),
                placeholder = { Text("Value", color = OnSurfaceDim.copy(alpha = 0.4f), fontSize = 14.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GoldAccent,
                    unfocusedBorderColor = Separator,
                    cursorColor = GoldAccent,
                ),
                shape = RoundedCornerShape(10.dp),
            )

            // Log button
            IconButton(
                onClick = {
                    if (inputValue.isNotBlank()) {
                        viewModel.logManualEntry(selectedField, inputValue.trim())
                        inputValue = ""
                        Toast.makeText(context, "Logged: $selectedField", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GoldAccent),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = "Log", tint = DarkBackground, modifier = Modifier.size(20.dp))
            }
        }

        // Manual entries summary
        if (state.manualEntries.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.manualEntries.takeLast(3).reversed().forEach { entry ->
                    Text(
                        text = "${entry.field}: ${entry.value}",
                        color = GoldAccent.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Live packet stream
        Text("Live packets", color = OnSurfaceDim, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(DarkSurface)
                .padding(8.dp),
            reverseLayout = true,
        ) {
            items(state.packets.reversed(), key = { "${it.timestampMs}_${it.label}" }) { pkt ->
                PacketRow(pkt, timeFormat)
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PacketRow(pkt: com.recon.dash.dash.TelemetryPacket, timeFormat: SimpleDateFormat) {
    val time = remember(pkt.timestampMs) { timeFormat.format(Date(pkt.timestampMs)) }
    val typeColor = if (pkt.type == 0x0F) Color(0xFFFF9F0A) else Color(0xFF64D2FF)

    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(time, color = OnSurfaceDim.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Text(pkt.label, color = typeColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("${pkt.decrypted?.size ?: pkt.raw.size}B", color = OnSurfaceDim.copy(alpha = 0.4f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Text(
            text = pkt.decHex,
            color = OnSurface.copy(alpha = 0.75f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
        )
    }
}
