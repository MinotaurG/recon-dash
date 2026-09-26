package com.recon.dash.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.recon.dash.data.CUSTOM_SLOTS
import com.recon.dash.data.FavoritePlace
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.data.PlaceIcons
import com.recon.dash.data.defaultIconKey
import com.recon.dash.data.presetName
import com.recon.dash.ui.theme.*

@Composable
fun ManagePlacesScreen(
    onBack: () -> Unit,
    onSetPlace: (FavoriteSlot) -> Unit,
    viewModel: SavedPlacesViewModel = hiltViewModel(),
) {
    val places by viewModel.allPlaces.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<FavoriteSlot?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Saved Places", color = OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Close, "Close", tint = OnSurfaceDim, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.height(24.dp))

        Text("Pinned", color = OnSurface.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DarkSurface).padding(vertical = 4.dp),
        ) {
            PlaceRow(FavoriteSlot.HOME, places, onSetPlace, viewModel::clear) { editing = it }
            PlaceRow(FavoriteSlot.OFFICE, places, onSetPlace, viewModel::clear) { editing = it }
        }

        Spacer(Modifier.height(20.dp))

        Text("Custom", color = OnSurface.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DarkSurface).padding(vertical = 4.dp),
        ) {
            for (slot in CUSTOM_SLOTS) {
                PlaceRow(slot, places, onSetPlace, viewModel::clear) { editing = it }
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    editing?.let { slot ->
        val place = places[slot]
        if (place != null) {
            EditPlaceDialog(
                place = place,
                onDismiss = { editing = null },
                onSave = { name, icon ->
                    viewModel.updateNameAndIcon(slot, name, icon)
                    editing = null
                },
            )
        } else {
            editing = null // nothing to edit until a location is set
        }
    }
}

@Composable
private fun PlaceRow(
    slot: FavoriteSlot,
    places: Map<FavoriteSlot, FavoritePlace>,
    onSetPlace: (FavoriteSlot) -> Unit,
    onClear: (FavoriteSlot) -> Unit,
    onEdit: (FavoriteSlot) -> Unit,
) {
    val place = places[slot]
    val icon = place?.let { PlaceIcons.forPlace(it) } ?: PlaceIcons.forSlot(slot)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSetPlace(slot) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(DarkBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = GoldAccent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = place?.name ?: slot.presetName(), color = OnSurface, fontSize = 15.sp)
            Text(
                text = place?.address?.ifBlank { "Tap to set" } ?: "Tap to set",
                color = OnSurface.copy(alpha = 0.45f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (place != null) {
            IconButton(onClick = { onEdit(slot) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Edit, "Edit", tint = OnSurface.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }
            Text(
                text = "Clear",
                color = Color(0xFFFF453A).copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.clickable { onClear(slot) }.padding(8.dp),
            )
        }
    }
}

@Composable
private fun EditPlaceDialog(
    place: FavoritePlace,
    onDismiss: () -> Unit,
    onSave: (name: String, iconKey: String) -> Unit,
) {
    var name by remember { mutableStateOf(place.name) }
    var iconKey by remember { mutableStateOf(place.icon.ifBlank { place.slot.defaultIconKey() }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), iconKey) }) {
                Text("Save", color = GoldAccent)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = OnSurface.copy(alpha = 0.6f)) } },
        title = { Text("Edit place", color = OnSurface) },
        containerColor = DarkSurface,
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text("Icon", color = OnSurface.copy(alpha = 0.6f), fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(PlaceIcons.all) { (key, vector) ->
                        val selected = key == iconKey
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(if (selected) GoldAccent.copy(alpha = 0.25f) else DarkBackground)
                                .clickable { iconKey = key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                vector, contentDescription = key,
                                tint = if (selected) GoldAccent else OnSurface.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}
