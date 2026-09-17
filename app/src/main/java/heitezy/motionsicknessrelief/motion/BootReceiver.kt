// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-License-Identifier: GPL-3.0-only

package heitezy.motionsicknessrelief.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import heitezy.motionsicknessrelief.data.CueSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val autoStartEnabled = runBlocking { CueSettingsRepository(context).settings.first().autoStart }
        if (autoStartEnabled) VehicleDetection.start(context)
    }
}
