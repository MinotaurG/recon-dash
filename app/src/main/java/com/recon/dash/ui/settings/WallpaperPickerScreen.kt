package com.recon.dash.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface

@Composable
fun WallpaperPickerScreen(
    onBack: () -> Unit,
    viewModel: WallpaperPickerViewModel = hiltViewModel(),
) {
    val wallpapers by viewModel.wallpapers.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()

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
            text = "Shown on dash when not navigating",
            color = OnSurface.copy(alpha = 0.4f),
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(20.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(wallpapers, key = { it.fileName }) { wp ->
                val isSelected = wp.fileName == selected
                val thumbnail = remember(wp.fileName) { viewModel.loadThumbnail(wp.fileName) }

                Box(
                    modifier = Modifier
                        .aspectRatio(526f / 300f)
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isSelected) Modifier.border(2.dp, GoldAccent, RoundedCornerShape(10.dp))
                            else Modifier
                        )
                        .background(DarkSurface)
                        .clickable { viewModel.select(wp.fileName) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = wp.id,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = wp.id,
                            color = OnSurface.copy(alpha = 0.3f),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}
