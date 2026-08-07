package com.recon.dash.ui.places

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.data.CUSTOM_SLOTS
import com.recon.dash.data.FavoritePlace
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.ui.theme.*

@Composable
fun SavedPlacesScreen(
    onBack: () -> Unit,
    onPlaceTap: (FavoritePlace) -> Unit,
    onAddTap: (FavoriteSlot) -> Unit,
    viewModel: SavedPlacesViewModel = hiltViewModel(),
) {
    val places by viewModel.customPlaces.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Saved Places",
                color = OnSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Close, "Close", tint = OnSurfaceDim, modifier = Modifier.size(22.dp))
            }
        }

        Spacer(Modifier.height(20.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val filledSlots = CUSTOM_SLOTS.filter { places.containsKey(it) }
            items(filledSlots, key = { it.name }) { slot ->
                val place = places[slot]!!
                PlaceTile(
                    label = place.name,
                    subtitle = place.label,
                    icon = com.recon.dash.data.PlaceIcons.forPlace(place),
                    color = slotColor(slot),
                    onClick = { onPlaceTap(place) },
                )
            }

            val nextEmpty = CUSTOM_SLOTS.firstOrNull { !places.containsKey(it) }
            if (nextEmpty != null) {
                item {
                    AddPlaceTile(onClick = { onAddTap(nextEmpty) })
                }
            }
        }
    }
}

@Composable
private fun PlaceTile(
    label: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "tileScale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .scale(scale)
            .shadow(8.dp, RoundedCornerShape(22.dp), spotColor = color.copy(alpha = 0.3f))
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.75f))))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AddPlaceTile(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "addScale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .background(DarkSurface)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "Add place",
                tint = OnSurfaceDim,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text("Add place", color = OnSurfaceDim, fontSize = 12.sp)
        }
    }
}

private fun slotColor(slot: FavoriteSlot): Color = when (slot) {
    FavoriteSlot.GYM -> TileOrange
    FavoriteSlot.FRIEND_1 -> TilePink
    FavoriteSlot.FRIEND_2 -> TileTeal
    FavoriteSlot.FUEL -> Color(0xFF5E5CE6)
    FavoriteSlot.FOOD -> TileGreen
    else -> TileBlue
}
