// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-License-Identifier: GPL-3.0-only

package dev.davidv.motionsickness.motion

import android.accessibilityservice.AccessibilityService
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An accessibility service that allows the app to use [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 *
 * Overlays of this type are exempt from Android's security-sensitive "obscured touch" checks,
 * meaning they don't interfere with interacting with other apps (like tapping dialog buttons).
 *
 * If the service is NOT enabled, [MotionCuesService] falls back to [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY].
 */
class AccessibilityOverlayService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op. We only exist to host the TYPE_ACCESSIBILITY_OVERLAY window.
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onDestroy() {
        _instance.value = null
        super.onDestroy()
    }

    companion object {
        private val _instance = MutableStateFlow<AccessibilityOverlayService?>(null)
        val instance = _instance.asStateFlow()
    }
}
