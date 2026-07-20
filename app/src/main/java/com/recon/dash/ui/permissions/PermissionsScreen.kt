package com.recon.dash.ui.permissions

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface

data class PermissionItem(
    val title: String,
    val description: String,
    val granted: Boolean,
    val action: () -> Unit,
)

@Composable
fun PermissionsScreen(
    onAllGranted: () -> Unit,
) {
    val context = LocalContext.current
    var locationGranted by remember { mutableStateOf(false) }
    var bgLocationGranted by remember { mutableStateOf(false) }
    var notifListenerGranted by remember { mutableStateOf(false) }
    var notifPostGranted by remember { mutableStateOf(true) }

    fun checkPermissions() {
        locationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        bgLocationGranted = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        notifListenerGranted = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        )?.contains(context.packageName) == true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPostGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    LaunchedEffect(Unit) { checkPermissions() }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkPermissions() }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { checkPermissions() }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { checkPermissions() }

    val allGranted = locationGranted && bgLocationGranted && notifListenerGranted && notifPostGranted

    LaunchedEffect(allGranted) {
        if (allGranted) onAllGranted()
    }

    val items = buildList {
        add(PermissionItem(
            title = "Location",
            description = "GPS position for navigation and dash map",
            granted = locationGranted,
            action = {
                locationLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ))
            },
        ))
        add(PermissionItem(
            title = "Background location",
            description = "Keep streaming to dash with screen off",
            granted = bgLocationGranted,
            action = {
                if (locationGranted) {
                    bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    locationLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                }
            },
        ))
        add(PermissionItem(
            title = "Notification listener",
            description = "Read music metadata from Spotify, YT Music",
            granted = notifListenerGranted,
            action = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
        ))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(PermissionItem(
                title = "Notifications",
                description = "Show streaming status while riding",
                granted = notifPostGranted,
                action = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            ))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(48.dp))

        Text(
            text = "RECON DASH",
            color = OnSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Permissions needed to ride",
            color = OnSurface.copy(alpha = 0.5f),
            fontSize = 14.sp,
        )

        Spacer(Modifier.height(32.dp))

        items.forEach { item ->
            PermissionRow(item)
            Spacer(Modifier.height(12.dp))
        }

        Spacer(Modifier.weight(1f))

        if (allGranted) {
            Button(
                onClick = onAllGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GoldAccent,
                    contentColor = DarkBackground,
                ),
            ) {
                Text("Continue", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text(
                text = "Grant all permissions to continue",
                color = OnSurface.copy(alpha = 0.35f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PermissionRow(item: PermissionItem) {
    val statusColor = if (item.granted) Color(0xFF10B981) else Color(0xFFF59E0B)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = item.description,
                color = OnSurface.copy(alpha = 0.45f),
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (item.granted) {
            Text(
                text = "Granted",
                color = statusColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            TextButton(onClick = item.action) {
                Text("Grant", color = GoldAccent, fontSize = 13.sp)
            }
        }
    }
}
