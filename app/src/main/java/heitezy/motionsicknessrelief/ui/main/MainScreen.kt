// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package heitezy.motionsicknessrelief.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation3.runtime.NavKey
import heitezy.motionsicknessrelief.About as AboutKey
import heitezy.motionsicknessrelief.R
import heitezy.motionsicknessrelief.Settings as SettingsKey
import heitezy.motionsicknessrelief.motion.AccessibilityOverlayService
import heitezy.motionsicknessrelief.motion.MotionCuesService
import heitezy.motionsicknessrelief.theme.MyApplicationTheme
import androidx.core.net.toUri

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var accessibilityOverlayGranted by remember { mutableStateOf(isAccessibilityOverlayEnabled(context)) }
    val cuesRunning by MotionCuesService.isRunning.collectAsStateWithLifecycle()

    // Permissions can change while we're backgrounded (user toggled them in Settings).
    // Re-query on resume so the UI stays honest.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                notifGranted = hasNotificationPermission(context)
                accessibilityOverlayGranted = isAccessibilityOverlayEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notifGranted = granted }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.main_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.main_subtitle), style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(8.dp))

        if (!overlayGranted) {
            Button(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        "package:${context.packageName}".toUri(),
                    )
                    overlayLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.main_grant_overlay)) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifGranted) {
            Button(
                onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(),
            ) { Text("Allow notifications") }
        }

        if (!accessibilityOverlayGranted) {
            Button(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(),
            ) { Text(stringResource(R.string.main_grant_accessibility_overlay)) }
        }

        Spacer(Modifier.height(8.dp))

        if (!cuesRunning) {
            Button(
                onClick = { MotionCuesService.start(context) },
                enabled = overlayGranted,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.main_start)) }
        } else {
            Button(
                onClick = { MotionCuesService.stop(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.main_stop)) }
        }

        Button(
            onClick = { onItemClick(SettingsKey) },
            colors = ButtonDefaults.outlinedButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.main_open_settings)) }

        Button(
            onClick = { onItemClick(AboutKey) },
            colors = ButtonDefaults.outlinedButtonColors(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.main_open_about)) }
    }
}

private fun hasNotificationPermission(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * Accessibility services aren't requested through the normal runtime-permission APIs — the
 * only way to check whether ours is enabled is to look it up by component name in the list
 * Android reports as currently active.
 */
private fun isAccessibilityOverlayEnabled(context: android.content.Context): Boolean {
    val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
    val target = android.content.ComponentName(context, AccessibilityOverlayService::class.java).flattenToString()
    return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.id == target }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    MyApplicationTheme { MainScreen(onItemClick = {}) }
}
