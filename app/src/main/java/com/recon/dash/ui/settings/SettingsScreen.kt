package com.recon.dash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
fun SettingsScreen(
    onBack: () -> Unit,
    onWallpaperTap: () -> Unit = {},
    onRegionTap: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ssid by viewModel.ssid.collectAsStateWithLifecycle()
    val voiceMode by viewModel.voiceMode.collectAsStateWithLifecycle()

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
                text = "Settings",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(28.dp))

        SectionHeader("Dash Connection")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                title = "Saved SSID",
                subtitle = ssid.ifBlank { "Not set — will discover by prefix" },
            )
            SettingsRow(
                title = "Forget dash",
                subtitle = "Next connect will rediscover the dash WiFi",
                onClick = { viewModel.forgetDash() },
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Voice Navigation")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                title = "Mode",
                subtitle = when (voiceMode) {
                    "OFF" -> "Silent"
                    "CHIME" -> "Beep before turns"
                    "FULL" -> "Spoken directions"
                    else -> voiceMode
                },
                onClick = { viewModel.cycleVoiceMode() },
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Display")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                title = "Dash wallpaper",
                subtitle = "Shown when not navigating",
                onClick = onWallpaperTap,
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Data")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                title = "Routing graph",
                subtitle = if (viewModel.hasGraph) "Installed" else "Not downloaded",
                onClick = onRegionTap,
            )
            SettingsRow(
                title = "Tile cache",
                subtitle = viewModel.tileCacheSize,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = OnSurface.copy(alpha = 0.5f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            color = OnSurface,
            fontSize = 15.sp,
        )
        Text(
            text = subtitle,
            color = OnSurface.copy(alpha = 0.45f),
            fontSize = 13.sp,
        )
    }
}
