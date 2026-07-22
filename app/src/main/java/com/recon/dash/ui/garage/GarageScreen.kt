package com.recon.dash.ui.garage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.search.SearchResult
import com.recon.dash.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarageScreen(
    onBack: () -> Unit,
    onFuelStationTap: (SearchResult) -> Unit = {},
    viewModel: GarageViewModel = hiltViewModel(),
) {
    val odometer by viewModel.odometer.collectAsStateWithLifecycle()
    val services by viewModel.services.collectAsStateWithLifecycle()
    val fuelStats by viewModel.fuelStats.collectAsStateWithLifecycle()
    val showFuelSheet by viewModel.showFuelSheet.collectAsStateWithLifecycle()
    val nearbyFuel by viewModel.nearbyFuel.collectAsStateWithLifecycle()

    if (showFuelSheet) {
        FuelLogSheet(
            currentOdometer = odometer,
            onDismiss = { viewModel.closeFuelSheet() },
            onSubmit = { litres, cost, odo -> viewModel.logFillup(litres, cost, odo) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Garage",
                color = OnSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = OnSurfaceDim,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(20.dp))

            // Odometer display
            OdometerCard(odometer)

            Spacer(Modifier.height(16.dp))

            // Fuel stats card
            FuelStatsCard(
                stats = fuelStats,
                onLogFuel = { viewModel.openFuelSheet() },
                onFindFuel = { viewModel.searchNearbyFuel() },
            )

            // Nearby fuel results
            val fuelState = nearbyFuel
            if (fuelState !is NearbyFuelState.Idle) {
                Spacer(Modifier.height(12.dp))
                NearbyFuelCard(
                    state = fuelState,
                    onStationTap = onFuelStationTap,
                    onDismiss = { viewModel.clearNearbyFuel() },
                )
            }

            Spacer(Modifier.height(20.dp))

            // Service items
            Text(
                text = "Service Tracker",
                color = OnSurface.copy(alpha = 0.5f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))

            var serviceSheetItem by remember { mutableStateOf<ServiceDisplayItem?>(null) }

            serviceSheetItem?.let { item ->
                ServiceDoneSheet(
                    itemName = item.item.name,
                    onDismiss = { serviceSheetItem = null },
                    onSubmit = { cost ->
                        viewModel.markServiceDone(item.item.id, cost)
                        serviceSheetItem = null
                    },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurface)
                    .padding(vertical = 4.dp),
            ) {
                services.forEach { displayItem ->
                    ServiceRow(
                        displayItem = displayItem,
                        onMarkDone = { serviceSheetItem = displayItem },
                    )
                }
                if (services.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Loading service items...",
                            color = OnSurfaceDim,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OdometerCard(odometer: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Speed,
                contentDescription = null,
                tint = GoldAccent,
                modifier = Modifier.size(28.dp),
            )
            Column {
                Text(
                    text = "Odometer",
                    color = OnSurface.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
                Text(
                    text = "%,d km".format(odometer),
                    color = OnSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun FuelStatsCard(
    stats: FuelStats,
    onLogFuel: () -> Unit,
    onFindFuel: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Fuel",
                color = OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Avg mileage",
                        color = OnSurface.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = if (stats.avgKml != null) "%.1f km/l".format(stats.avgKml) else "--",
                        color = OnSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Last 30 days",
                        color = OnSurface.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                    Text(
                        text = if (stats.last30DaysSpend > 0) "%.0f INR".format(stats.last30DaysSpend) else "--",
                        color = OnSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onLogFuel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldAccent,
                        contentColor = DarkBackground,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocalGasStation,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Log fuel", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = onFindFuel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurface),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.NearMe,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Find fuel", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun NearbyFuelCard(
    state: NearbyFuelState,
    onStationTap: (SearchResult) -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Nearby Fuel Stations",
                    color = OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Dismiss",
                        tint = OnSurfaceDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when (state) {
                is NearbyFuelState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = GoldAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                is NearbyFuelState.Results -> {
                    state.stations.forEach { station ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onStationTap(station) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.LocalGasStation,
                                contentDescription = null,
                                tint = GoldAccent,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = station.name,
                                    color = OnSurface,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = station.address,
                                    color = OnSurfaceDim,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.ChevronRight,
                                contentDescription = null,
                                tint = OnSurfaceDim,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                is NearbyFuelState.Error -> {
                    Text(
                        text = state.message,
                        color = OnSurfaceDim,
                        fontSize = 13.sp,
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun ServiceRow(
    displayItem: ServiceDisplayItem,
    onMarkDone: () -> Unit,
) {
    val statusColor = when (displayItem.status) {
        ServiceStatus.OK -> Success
        ServiceStatus.DUE_SOON -> Warning
        ServiceStatus.OVERDUE -> Error
    }
    val statusText = when (displayItem.status) {
        ServiceStatus.OK -> "${displayItem.remainingKm} km remaining"
        ServiceStatus.DUE_SOON -> "${displayItem.remainingKm} km remaining"
        ServiceStatus.OVERDUE -> "Overdue by ${-displayItem.remainingKm} km"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(statusColor),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayItem.item.name,
                color = OnSurface,
                fontSize = 15.sp,
            )
            Text(
                text = statusText,
                color = statusColor.copy(alpha = 0.8f),
                fontSize = 12.sp,
            )
        }
        TextButton(
            onClick = onMarkDone,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = "Mark done",
                color = GoldAccent,
                fontSize = 12.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceDoneSheet(
    itemName: String,
    onDismiss: () -> Unit,
    onSubmit: (cost: Double) -> Unit,
) {
    var cost by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Mark $itemName Done",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(20.dp))

            SheetTextField(
                value = cost,
                onValueChange = { cost = it },
                label = "Cost (INR, optional)",
                placeholder = "e.g. 1500",
            )
            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onSubmit(0.0) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurface),
                ) {
                    Text("Skip cost", fontSize = 14.sp)
                }
                Button(
                    onClick = { onSubmit(cost.toDoubleOrNull() ?: 0.0) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GoldAccent,
                        contentColor = DarkBackground,
                    ),
                    enabled = cost.toDoubleOrNull() != null && cost.toDoubleOrNull()!! > 0,
                ) {
                    Text("Save", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuelLogSheet(
    currentOdometer: Int,
    onDismiss: () -> Unit,
    onSubmit: (litres: Double, cost: Double, odometer: Int) -> Unit,
) {
    var litres by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf(if (currentOdometer > 0) currentOdometer.toString() else "") }

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DarkSurface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = "Log Fuel",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(20.dp))

            SheetTextField(
                value = litres,
                onValueChange = { litres = it },
                label = "Litres",
                placeholder = "e.g. 12.5",
            )
            Spacer(Modifier.height(12.dp))

            SheetTextField(
                value = cost,
                onValueChange = { cost = it },
                label = "Cost (INR)",
                placeholder = "e.g. 1250",
            )
            Spacer(Modifier.height(12.dp))

            SheetTextField(
                value = odo,
                onValueChange = { odo = it },
                label = "Odometer (km)",
                placeholder = "e.g. 5420",
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val l = litres.toDoubleOrNull() ?: return@Button
                    val c = cost.toDoubleOrNull() ?: return@Button
                    val o = odo.toIntOrNull() ?: return@Button
                    onSubmit(l, c, o)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldAccent,
                    contentColor = DarkBackground,
                ),
                enabled = litres.toDoubleOrNull() != null
                    && cost.toDoubleOrNull() != null
                    && odo.toIntOrNull() != null,
            ) {
                Text("Save", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SheetTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
) {
    Column {
        Text(
            text = label,
            color = OnSurface.copy(alpha = 0.6f),
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(placeholder, color = OnSurfaceDim.copy(alpha = 0.4f))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = OnSurface,
                unfocusedTextColor = OnSurface,
                focusedBorderColor = GoldAccent,
                unfocusedBorderColor = OnSurfaceDim.copy(alpha = 0.3f),
                cursorColor = GoldAccent,
            ),
        )
    }
}
