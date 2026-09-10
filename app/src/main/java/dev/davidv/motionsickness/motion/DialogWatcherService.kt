// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-License-Identifier: GPL-3.0-only

package dev.davidv.motionsickness.motion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Watches for system/app dialogs appearing in the foreground (identified by the standard
 * framework button IDs AlertDialog assigns its buttons) and tells [MotionCuesService] to pull
 * the overlay window entirely while one is showing, so dialog buttons stay tappable.
 *
 * The overlay already uses FLAG_NOT_TOUCHABLE so it never intercepts touches itself, but some
 * dialogs — and any view with `filterTouchesWhenObscured` set, which security-sensitive buttons
 * often have — reject touches whenever *any* window visually overlaps them, touchable or not.
 * Nudging the overlay's alpha below Android 12's "obscured" threshold (see the comment in
 * [MotionCuesService.attachOverlay]) covers most of this already, but not every dialog respects
 * that. Removing the overlay's window outright while a dialog is up is the only thing that
 * reliably works for all of them.
 *
 * This is an opt-in accessibility service: the user has to enable it explicitly in Android's
 * Accessibility settings, since accessibility services can read on-screen content from other
 * apps system-wide. [dev.davidv.motionsickness.ui.main.MainScreen] surfaces a button that jumps
 * there, and only shows it until the service is enabled.
 */
class DialogWatcherService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        // This fires on every window/content change system-wide, for as long as the service is
        // enabled — including while cues aren't running at all. Skip the node search entirely
        // in that case; it's the expensive part and there's nothing to protect anyway.
        if (!MotionCuesService.isRunning.value) return

        val root = rootInActiveWindow
        val hasDialogButtons = root != null && (
            root.findAccessibilityNodeInfosByViewId(DIALOG_BUTTON_1_ID).isNotEmpty() ||
                root.findAccessibilityNodeInfosByViewId(DIALOG_BUTTON_2_ID).isNotEmpty() ||
                root.findAccessibilityNodeInfosByViewId(DIALOG_BUTTON_3_ID).isNotEmpty()
        )
        MotionCuesService.setDialogVisible(hasDialogButtons)
    }

    override fun onInterrupt() {
        MotionCuesService.setDialogVisible(false)
    }

    override fun onDestroy() {
        // Covers the user disabling the service (in Settings, or by revoking the permission)
        // while a dialog happened to be up — don't leave the overlay permanently hidden.
        MotionCuesService.setDialogVisible(false)
        super.onDestroy()
    }

    companion object {
        // Standard framework IDs AlertDialog assigns its positive/negative/neutral buttons —
        // stable across Android versions since they're part of the platform's own dialog
        // layout, not something individual apps set themselves.
        private const val DIALOG_BUTTON_1_ID = "android:id/button1"
        private const val DIALOG_BUTTON_2_ID = "android:id/button2"
        private const val DIALOG_BUTTON_3_ID = "android:id/button3"
    }
}
