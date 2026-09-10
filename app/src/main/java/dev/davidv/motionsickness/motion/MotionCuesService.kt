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

    private lateinit var windowManager: WindowManager
    private var overlayView: CueOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private lateinit var motionEstimator: MotionEstimator
    private lateinit var settingsRepository: CueSettingsRepository

    private val scope = CoroutineScope(Dispatchers.Main)
    private var collectJob: Job? = null
    private var settingsJob: Job? = null
    private var dialogJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        motionEstimator = MotionEstimator(this)
        settingsRepository = CueSettingsRepository(this)
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
        attachOverlay()
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
                motionEstimator.fusionMode = settings.motionFusionMode
                overlayView?.applySettings(settings, cueColorPalette(this@MotionCuesService))
            }
        }
        dialogJob?.cancel()
        dialogJob = scope.launch {
            // DialogWatcherService (an opt-in accessibility service) flips this when a
            // system/app dialog appears, so its buttons stay tappable underneath us.
            _dialogVisible.collectLatest { dialogShowing ->
                if (dialogShowing) hideOverlayForDialog() else restoreOverlayAfterDialog()
            }
        }
        _isRunning.value = true
        notifyTileOfStateChange(this)
        return START_STICKY
    }

    private fun attachOverlay() {
        if (overlayView != null) return
        val view = CueOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alpha = 0.79f
            }
        }
        windowManager.addView(view, params)
        overlayView = view
        overlayParams = params
    }

    /**
     * Pulls the overlay's window entirely (rather than just toggling touchability or alpha)
     * while a dialog is showing — see [DialogWatcherService] for why that's necessary. The
     * [overlayView] instance and its [overlayParams] are kept around so [restoreOverlayAfterDialog]
     * can put the same view back once the dialog is gone, instead of recreating it.
     */
    private fun hideOverlayForDialog() {
        overlayView?.let { view -> runCatching { windowManager.removeViewImmediate(view) } }
    }

    private fun restoreOverlayAfterDialog() {
        val view = overlayView ?: return
        val params = overlayParams ?: return
        runCatching { windowManager.addView(view, params) }
    }

    private fun detachOverlay() {
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
            overlayView = null
        }
        overlayParams = null
    }

    private fun stopSelfCleanly() {
        collectJob?.cancel()
        collectJob = null
        settingsJob?.cancel()
        settingsJob = null
        dialogJob?.cancel()
        dialogJob = null
        motionEstimator.stop()
        detachOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _isRunning.value = false
        notifyTileOfStateChange(this)
        scope.cancel()
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
            startForeground(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "dev.davidv.motionsickness.ACTION_STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        // Set by DialogWatcherService (if the user has enabled it) whenever a system/app
        // dialog is showing, so the running instance can pull its overlay window out of the
        // way. A plain top-level flow, same pattern as [isRunning] — there's only ever one
        // instance of this service, so it doesn't need to be routed through a bound interface.
        private val _dialogVisible = MutableStateFlow(false)
        fun setDialogVisible(visible: Boolean) { _dialogVisible.value = visible }

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
