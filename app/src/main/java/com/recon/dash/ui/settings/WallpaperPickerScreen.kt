package com.recon.dash.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.data.Wallpaper
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.DarkSurfaceElevated
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface
import com.recon.dash.ui.theme.OnSurfaceDim

@Composable
fun WallpaperPickerScreen(
    onBack: () -> Unit,
    viewModel: WallpaperPickerViewModel = hiltViewModel(),
) {
    val wallpapers by viewModel.wallpapers.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) viewModel.importWallpaper(uri) }

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
                text = "Dash Wallpaper",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "Shown on dash when not navigating. Long press your own image to remove it.",
            color = OnSurface.copy(alpha = 0.4f),
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(20.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // "None" — no wallpaper, dash shows a plain dark background.
            item(key = "__none__") {
                NoneTile(
                    isSelected = selected.isBlank(),
                    onClick = { viewModel.selectNone() },
                )
            }

            // "Upload" — pick an image from the phone.
            item(key = "__upload__") {
                UploadTile(
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
            }

            items(wallpapers, key = { it.fileName }) { wp ->
                WallpaperTile(
                    wallpaper = wp,
                    isSelected = wp.fileName == selected,
                    thumbnail = remember(wp.fileName) { viewModel.loadThumbnail(wp.fileName) },
                    onClick = { viewModel.select(wp.fileName) },
                    onLongPress = { viewModel.deleteWallpaper(wp) },
                )
            }
        }
    }
}

@Composable
private fun NoneTile(isSelected: Boolean, onClick: () -> Unit) {
    TileBox(isSelected = isSelected, onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.Block,
                contentDescription = "No wallpaper",
                tint = if (isSelected) GoldAccent else OnSurfaceDim,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "None",
                color = if (isSelected) GoldAccent else OnSurfaceDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun UploadTile(onClick: () -> Unit) {
    TileBox(isSelected = false, onClick = onClick, background = DarkSurfaceElevated) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = "Upload image",
                tint = GoldAccent,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Upload image",
                color = GoldAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WallpaperTile(
    wallpaper: Wallpaper,
    isSelected: Boolean,
    thumbnail: android.graphics.Bitmap?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(526f / 300f)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isSelected) Modifier.border(2.dp, GoldAccent, RoundedCornerShape(10.dp))
                else Modifier
            )
            .background(DarkSurface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (wallpaper.isCustom) onLongPress else null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = wallpaper.id,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = wallpaper.id,
                color = OnSurface.copy(alpha = 0.3f),
                fontSize = 11.sp,
            )
        }
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(GoldAccent)
                    .padding(3.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = DarkBackground,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun TileBox(
    isSelected: Boolean,
    onClick: () -> Unit,
    background: androidx.compose.ui.graphics.Color = DarkSurface,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(526f / 300f)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (isSelected) Modifier.border(2.dp, GoldAccent, RoundedCornerShape(10.dp))
                else Modifier
            )
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}
