package com.recon.dash.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.ui.theme.*

@Composable
fun ManagePlacesScreen(
    onBack: () -> Unit,
    onSetPlace: (FavoriteSlot) -> Unit,
    viewModel: SavedPlacesViewModel = hiltViewModel(),
) {
    val places by viewModel.allPlaces.collectAsStateWithLifecycle()

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
            PlaceRow(FavoriteSlot.HOME, "Home", places, onSetPlace, viewModel::clear)
            PlaceRow(FavoriteSlot.OFFICE, "Office", places, onSetPlace, viewModel::clear)
        }

        Spacer(Modifier.height(20.dp))

        Text("Custom", color = OnSurface.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DarkSurface).padding(vertical = 4.dp),
        ) {
            for (slot in listOf(FavoriteSlot.CUSTOM_1, FavoriteSlot.CUSTOM_2, FavoriteSlot.CUSTOM_3, FavoriteSlot.CUSTOM_4)) {
                PlaceRow(slot, customLabel(slot), places, onSetPlace, viewModel::clear)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PlaceRow(
    slot: FavoriteSlot,
    label: String,
    places: Map<FavoriteSlot, com.recon.dash.data.FavoritePlace>,
    onSetPlace: (FavoriteSlot) -> Unit,
    onClear: (FavoriteSlot) -> Unit,
) {
    val place = places[slot]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSetPlace(slot) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = place?.name ?: label, color = OnSurface, fontSize = 15.sp)
            Text(
                text = place?.address?.ifBlank { "Tap to set" } ?: "Tap to set",
                color = OnSurface.copy(alpha = 0.45f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (place != null) {
            Text(
                text = "Clear",
                color = Color(0xFFFF453A).copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.clickable { onClear(slot) }.padding(8.dp),
            )
        }
    }
}

private fun customLabel(slot: FavoriteSlot): String = when (slot) {
    FavoriteSlot.CUSTOM_1 -> "Place 1"
    FavoriteSlot.CUSTOM_2 -> "Place 2"
    FavoriteSlot.CUSTOM_3 -> "Place 3"
    FavoriteSlot.CUSTOM_4 -> "Place 4"
    else -> "Place"
}
