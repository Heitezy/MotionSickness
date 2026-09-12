// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-License-Identifier: GPL-3.0-only

package dev.davidv.motionsickness.motion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reports the device's current ground speed (m/s) via Play Services' fused location provider,
 * purely so [MotionEstimator] can scale yaw-driven turn cues by actual vehicle speed instead of
 * angular rate alone (see [MotionEstimator.speedFactor]) — the same fixed yaw rate means a
 * gentle parking-lot turn at low speed and a sharp highway curve at high speed, and only a
 * speed source can tell those apart.
 *
 * Entirely optional and off by default: gated behind `CueSettings.speedScaledTurnCues` and its
 * own runtime location permission (never requested unless the person explicitly enables this
 * setting), and every consumer treats [speedMps] == null as "speed unknown," falling back to
 * the pre-existing, speed-independent cue behavior. Nothing about the app's core purpose
 * depends on this being available.
 */
class SpeedProvider(context: Context) {

    private val appContext = context.applicationContext
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    private val _speedMps = MutableStateFlow<Float?>(null)
    val speedMps: StateFlow<Float?> = _speedMps.asStateFlow()

    private var requestingUpdates = false

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            // hasSpeed()/getSpeed() is the GPS chip's own Doppler-derived speed when
            // available — far more stable than differentiating consecutive fixes ourselves.
            // If the fix doesn't carry a speed (e.g. a stale network-based location slipped
            // through), report unknown rather than a fabricated zero.
            _speedMps.value = if (location.hasSpeed()) location.speed else null
        }
    }

    /** No-ops without permission — callers should check [hasPermission] before offering this. */
    @SuppressLint("MissingPermission") // guarded by the hasPermission() check below
    fun start() {
        if (requestingUpdates || !hasPermission()) return
        requestingUpdates = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .build()
        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stop() {
        if (!requestingUpdates) return
        requestingUpdates = false
        fusedClient.removeLocationUpdates(callback)
        _speedMps.value = null
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 1000L
    }
}
