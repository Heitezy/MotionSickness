// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-License-Identifier: GPL-3.0-only

package heitezy.motionsicknessrelief.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.provider.Settings
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Receives vehicle enter/exit events from [VehicleDetection] and starts/stops the overlay
 * service accordingly. Registered for an explicit action only — Play Services delivers this via
 * the exact `PendingIntent` we handed it, so the receiver doesn't need to be exported.
 */
class VehicleDetectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VehicleDetection.ACTION_VEHICLE_TRANSITION) return
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        handle(context, result)
    }

    companion object {
        /**
         * Applies a transition result — real or synthetic — to the overlay. Factored out of
         * [onReceive] so it can be exercised directly (see [simulateTransition]) without needing
         * to reproduce Play Services' internal, undocumented `Intent` extras just to get past
         * `ActivityTransitionResult.hasResult()`.
         */
        private fun handle(context: Context, result: ActivityTransitionResult) {
            for (event in result.transitionEvents) {
                if (event.activityType != DetectedActivity.IN_VEHICLE) continue
                when (event.transitionType) {
                    ActivityTransition.ACTIVITY_TRANSITION_ENTER -> {
                        if (Settings.canDrawOverlays(context)) MotionCuesService.start(context)
                    }
                    ActivityTransition.ACTIVITY_TRANSITION_EXIT -> {
                        MotionCuesService.stop(context)
                    }
                }
            }
        }

        /**
         * Debug-only test hook: builds a synthetic [ActivityTransitionResult] exactly like the
         * one Play Services would deliver on a real vehicle enter/exit, and runs it through the
         * same [handle] path the real receiver uses. Lets "start in a moving vehicle" be
         * verified without an actual car, an emulator route, or waiting for Play Services'
         * classifier to become confident.
         *
         * Gated on [ApplicationInfo.FLAG_DEBUGGABLE] (true for debug builds, false for anything
         * signed for release) rather than a UI-level check, so it's structurally impossible to
         * ship this control in a release build regardless of what screen calls it.
         */
        fun simulateTransition(context: Context, entering: Boolean) {
            check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                "simulateTransition() is a debug-only test hook and must not be called from a release build"
            }
            val transitionType = if (entering) {
                ActivityTransition.ACTIVITY_TRANSITION_ENTER
            } else {
                ActivityTransition.ACTIVITY_TRANSITION_EXIT
            }
            val event = ActivityTransitionEvent(
                DetectedActivity.IN_VEHICLE,
                transitionType,
                SystemClock.elapsedRealtimeNanos(),
            )
            handle(context, ActivityTransitionResult(listOf(event)))
        }
    }
}
