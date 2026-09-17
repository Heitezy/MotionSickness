// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-License-Identifier: GPL-3.0-only

package heitezy.motionsicknessrelief.motion

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/**
 * Wraps Play Services' Activity Recognition transition API to start/stop the overlay
 * automatically when the phone enters or exits a moving vehicle — the same "Start in a moving
 * vehicle" behavior Android's own Motion Assist and iOS's "Automatic" mode offer, so the person
 * never has to remember to toggle the overlay by hand.
 */
object VehicleDetection {

    const val ACTION_VEHICLE_TRANSITION = "heitezy.motionsicknessrelief.ACTION_VEHICLE_TRANSITION"

    fun hasPermission(context: Context): Boolean {
        // ACTIVITY_RECOGNITION only exists (and is required) from Android 10 onward; below
        // that, activity recognition doesn't need a runtime permission at all.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Subscribes to vehicle-entry/exit transitions. No-ops (and returns false) without permission. */
    fun start(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val request = ActivityTransitionRequest(
            listOf(
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build(),
                ActivityTransition.Builder()
                    .setActivityType(DetectedActivity.IN_VEHICLE)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                    .build(),
            ),
        )
        return runCatching {
            ActivityRecognition.getClient(context)
                .requestActivityTransitionUpdates(request, pendingIntent(context))
        }.isSuccess
    }

    fun stop(context: Context) {
        ActivityRecognition.getClient(context).removeActivityTransitionUpdates(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, VehicleDetectionReceiver::class.java).setAction(ACTION_VEHICLE_TRANSITION)
        // MUTABLE is required here: Play Services fills the ActivityTransitionResult into the
        // intent's extras when it fires this PendingIntent.
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
