// SPDX-FileCopyrightText: 2026 Heitezy
// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

package dev.davidv.motionsickness.motion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import dev.davidv.motionsickness.MainActivity
import dev.davidv.motionsickness.R
import dev.davidv.motionsickness.data.CueSettings
import dev.davidv.motionsickness.data.CueSettingsRepository
import dev.davidv.motionsickness.theme.cueColorPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hosts the motion-cue overlay for as long as the user wants it visible. Lives as a foreground
 * service because we need the overlay and sensor stream to survive when the launcher activity
 * is no longer in the foreground (the whole point is to draw *over* other apps).
 */
class MotionCuesService : Service() {

    private var currentWindowManager: WindowManager? = null
    private var overlayView: CueOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var currentSettings: CueSettings? = null
    private lateinit var motionEstimator: MotionEstimator
    private lateinit var settingsRepository: CueSettingsRepository
    private lateinit var speedProvider: SpeedProvider

    private val scope = CoroutineScope(Dispatchers.Main)
    private var collectJob: Job? = null
    private var settingsJob: Job? = null
    private var accessibilityJob: Job? = null
    private var speedJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        motionEstimator = MotionEstimator(this)
        settingsRepository = CueSettingsRepository(this)
        speedProvider = SpeedProvider(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()
        motionEstimator.start()
        collectJob?.cancel()
        collectJob = scope.launch {
            motionEstimator.motion.collectLatest { overlayView?.setMotion(it) }
        }
        settingsJob?.cancel()
        settingsJob = scope.launch {
            // Palette is resolved fresh alongside every settings change (rather than cached)
            // since dark/light mode can flip while the overlay is running.
            settingsRepository.settings.collectLatest { settings ->
                currentSettings = settings
                motionEstimator.fusionMode = settings.motionFusionMode
                overlayView?.applySettings(settings, cueColorPalette(this@MotionCuesService))

                // Speed scaling is opt-in and permission-gated (see SpeedProvider), so this
                // only ever starts requesting location updates when the person has explicitly
                // enabled the setting *and* already granted the permission — never as a side
                // effect of some other setting changing.
                val wantsSpeed = settings.speedScaledTurnCues && speedProvider.hasPermission()
                if (wantsSpeed && speedJob == null) {
                    // Re-declare the foreground service type now that we know location is
                    // actually permitted, then start collecting updates. See
                    // startForegroundWithNotification() for why the location type can't just
                    // be included unconditionally at startup.
                    startForegroundWithNotification()
                    speedProvider.start()
                    speedJob = scope.launch {
                        speedProvider.speedMps.collectLatest { motionEstimator.speedMps = it }
                    }
                } else if (!wantsSpeed && speedJob != null) {
                    speedJob?.cancel()
                    speedJob = null
                    speedProvider.stop()
                    motionEstimator.speedMps = null
                    // Drop back to the non-location type now that we've stopped using it.
                    startForegroundWithNotification()
                }
            }
        }
        accessibilityJob?.cancel()
        accessibilityJob = scope.launch {
            // If the accessibility service is granted/enabled while we're running,
            // we want to "upgrade" to its overlay type.
            AccessibilityOverlayService.instance.collectLatest {
                detachOverlay()
                attachOverlay()
            }
        }
        _isRunning.value = true
        notifyTileOfStateChange(this)
        return START_STICKY
    }

    private fun attachOverlay() {
        if (overlayView != null) return

        val accessibilityOverlay = AccessibilityOverlayService.instance.value
        val hostContext = accessibilityOverlay ?: this
        val wm = hostContext.getSystemService(WindowManager::class.java)
        currentWindowManager = wm

        val type = if (accessibilityOverlay != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        _isUsingAccessibilityOverlay.value = (accessibilityOverlay != null)

        val view = CueOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+ (API 31) only tags touches as "obscured" — which security-sensitive
            // views reject — if the overlay window's alpha is above ~0.8. Setting it just below
            // that threshold lets taps fall through to apps below. The slight dimming is barely
            // noticeable in practice.
            // This is not needed for TYPE_ACCESSIBILITY_OVERLAY.
            if (accessibilityOverlay == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alpha = 0.79f
            }
        }
        wm.addView(view, params)
        overlayView = view
        overlayParams = params

        // Immediately apply current settings to the new view
        currentSettings?.let { view.applySettings(it, cueColorPalette(this)) }
    }

    private fun detachOverlay() {
        overlayView?.let {
            runCatching { currentWindowManager?.removeViewImmediate(it) }
            overlayView = null
        }
        overlayParams = null
        currentWindowManager = null
    }

    private fun stopSelfCleanly() {
        collectJob?.cancel()
        collectJob = null
        settingsJob?.cancel()
        settingsJob = null
        accessibilityJob?.cancel()
        accessibilityJob = null
        speedJob?.cancel()
        speedJob = null
        speedProvider.stop()
        motionEstimator.stop()
        detachOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _isRunning.value = false
        notifyTileOfStateChange(this)
        scope.cancel()
        accessibilityJob?.cancel()
        accessibilityJob = null
        speedProvider.stop()
        motionEstimator.stop()
        detachOverlay()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val channelId = "motion_cues"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.motion_cues_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
        val openAppPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, MotionCuesService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.motion_cues_notification_title))
            .setContentText(getString(R.string.motion_cues_notification_text))
            .setContentIntent(openAppPi)
            .addAction(0, getString(R.string.motion_cues_notification_stop), stopPi)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // FOREGROUND_SERVICE_TYPE_LOCATION may only be included when the app currently
            // holds the location permission: Android checks this at the moment
            // startForeground() is called, not just what's declared in the manifest. Since
            // speed-scaled turn cues are off by default and the location permission is only
            // ever requested when the person explicitly opts in (see SpeedProvider), most
            // installs never hold it — including it unconditionally here throws a
            // SecurityException on every single service start.
            //
            // This method is called again whenever speedProvider.hasPermission() combined
            // with the speedScaledTurnCues setting flips (see the settings collector above),
            // so the declared type stays in sync with whether we're actually consuming
            // location right now.
            val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                speedProvider.hasPermission()
            ) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, notif, types)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "dev.davidv.motionsickness.ACTION_STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _isUsingAccessibilityOverlay = MutableStateFlow(false)
        val isUsingAccessibilityOverlay: StateFlow<Boolean> = _isUsingAccessibilityOverlay.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, MotionCuesService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MotionCuesService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        /**
         * [CuesTileService] is declared as an "Active tile" (ACTIVE_TILE metadata), which per
         * Android's docs means the *app* — not the system — is responsible for prompting a
         * refresh whenever the underlying state changes outside of the tile itself being tapped
         * (from the notification's Stop action, the app's own Start/Stop button, or vehicle
         * auto-detection). Without this, the tile only ever picks up the current state when the
         * user happens to already have the Quick Settings panel open.
         */
        private fun notifyTileOfStateChange(context: Context) {
            TileService.requestListeningState(context, ComponentName(context, CuesTileService::class.java))
        }
    }
}
