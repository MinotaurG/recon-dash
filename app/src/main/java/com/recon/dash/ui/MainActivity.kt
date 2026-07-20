package com.recon.dash.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.recon.dash.ui.theme.ReconDashTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReconDashTheme {
                var permissionsOk by remember {
                    mutableStateOf(hasRequiredPermissions())
                }
                if (permissionsOk) {
                    AppNavigation()
                } else {
                    com.recon.dash.ui.permissions.PermissionsScreen(
                        onAllGranted = { permissionsOk = true }
                    )
                }
            }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
