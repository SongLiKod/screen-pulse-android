package com.screenpulse.floatingwindow

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.screenpulse.R
import com.screenpulse.service.ScreenRecordService
import com.screenpulse.viewmodel.RecordingState
import com.screenpulse.util.LogManager
import com.screenpulse.util.OverlayRecordingStarter

class FloatingWindowService : Service() {

    companion object {
        const val ACTION_UPDATE_STATE = "com.screenpulse.floating.ACTION_UPDATE_STATE"
        const val EXTRA_STATE = "state"
        const val ACTION_UPDATE_DURATION = "com.screenpulse.floating.ACTION_UPDATE_DURATION"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val ACTION_UPDATE_COUNTDOWN = "com.screenpulse.floating.ACTION_UPDATE_COUNTDOWN"
        const val EXTRA_COUNTDOWN_REMAINING = "countdown_remaining"
        const val ACTION_HIDE = "com.screenpulse.floating.ACTION_HIDE"
        const val ACTION_SHOW = "com.screenpulse.floating.ACTION_SHOW"
        const val ACTION_SHOW_PERSISTENT = "com.screenpulse.floating.ACTION_SHOW_PERSISTENT"
        const val ACTION_HIDE_FOR_CAPTURE = "com.screenpulse.floating.ACTION_HIDE_FOR_CAPTURE"
        const val ACTION_RESTORE_AFTER_CAPTURE = "com.screenpulse.floating.ACTION_RESTORE_AFTER_CAPTURE"
        const val EXTRA_PERSISTENT = "persistent"

        private const val CHANNEL_ID = "screen_pulse_floating"
        private const val NOTIFICATION_ID = 200
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false
    private var currentState = RecordingState.IDLE
    private var isPersistentMode = false
    private var isCollapsed = false
    private var isForeground = false
    private var hideForCapture = false

    // Drag state: store the initial layout position when drag starts
    private var dragStartX = 0
    private var dragStartY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Start foreground service IMMEDIATELY on Android 14+.
        // The system kills services that don't call startForeground() within ~5 seconds
        // of onStartCommand(). This must be called before any other work.
        startForegroundIfNeeded()

        when (intent?.action) {
            ACTION_UPDATE_STATE -> {
                val stateValue = intent.getIntExtra(EXTRA_STATE, 0)
                currentState = RecordingState.entries[stateValue]
                LogManager.log(LogManager.TAG_FLOAT, "state update -> $currentState")
                updateFloatingIcon()
            }
            ACTION_HIDE -> {
                LogManager.log(LogManager.TAG_FLOAT, "hide floating window")
                removeFloatingView()
                // Do NOT stop foreground here — keep the service alive so it can be
                // re-shown quickly. The service will be stopped by ScreenRecordService
                // when recording finishes, or by the system when the app is truly done.
            }
            ACTION_HIDE_FOR_CAPTURE -> {
                hideForCapture = true
                LogManager.log(LogManager.TAG_FLOAT, "hide floating window for capture")
                removeFloatingView()
            }
            ACTION_RESTORE_AFTER_CAPTURE -> {
                hideForCapture = false
                LogManager.log(LogManager.TAG_FLOAT, "restore floating window after capture")
                if (floatingView == null) {
                    createFloatingView()
                } else {
                    addFloatingView()
                }
                updateFloatingIcon()
            }
            ACTION_UPDATE_DURATION -> {
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                updateDuration(durationMs)
            }
            ACTION_UPDATE_COUNTDOWN -> {
                val remaining = intent.getIntExtra(EXTRA_COUNTDOWN_REMAINING, 0)
                updateCountdown(remaining)
            }
            ACTION_SHOW -> {
                LogManager.log(LogManager.TAG_FLOAT, "show floating window hideForCapture=$hideForCapture")
                if (hideForCapture) {
                    // Keep the service alive but do not put the ball back on screen
                    // while recording/countdown is capturing frames.
                } else if (floatingView == null) {
                    createFloatingView()
                } else {
                    addFloatingView()
                }
            }
            ACTION_SHOW_PERSISTENT -> {
                isPersistentMode = intent.getBooleanExtra(EXTRA_PERSISTENT, false)
                LogManager.log(LogManager.TAG_FLOAT, "show persistent floating window, persistent=$isPersistentMode hideForCapture=$hideForCapture")
                if (hideForCapture) {
                    // Recording capture in progress; restore after stop.
                } else if (floatingView == null) {
                    createFloatingView()
                } else {
                    addFloatingView()
                }
                updateFloatingIcon()
            }
            else -> LogManager.log(LogManager.TAG_FLOAT, "onStartCommand action=${intent?.action}")
        }

        if (!hideForCapture && floatingView == null) {
            createFloatingView()
        }

        if (!hideForCapture) {
            updateFloatingIcon()
        }

        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        floatingView = View.inflate(this, R.layout.layout_floating_window, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        // Use DraggableLinearLayout for drag handling — clicks go to children automatically
        val dragRoot = floatingView?.findViewById<DraggableLinearLayout>(R.id.floating_root)
        dragRoot?.onDragStart = {
            dragStartX = layoutParams?.x ?: 0
            dragStartY = layoutParams?.y ?: 0
        }
        dragRoot?.onDragListener = { dx, dy ->
            layoutParams?.let { lp ->
                lp.x = dragStartX + dx
                lp.y = dragStartY + dy
                windowManager?.updateViewLayout(floatingView, lp)
            }
        }

        // Pause/resume button click
        floatingView?.findViewById<View>(R.id.floating_pause_btn)?.setOnClickListener {
            handleClick()
        }

        var annotationVisible = false
        floatingView?.findViewById<View>(R.id.floating_annotation_btn)?.setOnClickListener {
            annotationVisible = !annotationVisible
            val intent = Intent(this, FloatingAnnotationService::class.java).apply {
                action = if (annotationVisible) FloatingAnnotationService.ACTION_SHOW
                else FloatingAnnotationService.ACTION_HIDE
            }
            startService(intent)
        }

        floatingView?.findViewById<View>(R.id.floating_stop_btn)?.setOnClickListener {
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
            startService(intent)
        }

        // Collapse/expand button (eye icon)
        floatingView?.findViewById<View>(R.id.floating_hide_btn)?.setOnClickListener {
            toggleCollapse()
        }

        updateFloatingIcon()
        addFloatingView()
    }

    /**
     * Toggle between collapsed and expanded states.
     * Collapsed: only show the main action button (pause/record)
     * Expanded: show all buttons (duration, annotation, stop, collapse)
     */
    private fun toggleCollapse() {
        isCollapsed = !isCollapsed
        val hideBtn = floatingView?.findViewById<ImageView>(R.id.floating_hide_btn)
        val durationView = floatingView?.findViewById<View>(R.id.floating_duration)
        val annotationBtn = floatingView?.findViewById<View>(R.id.floating_annotation_btn)
        val stopBtn = floatingView?.findViewById<View>(R.id.floating_stop_btn)

        if (isCollapsed) {
            hideBtn?.setImageResource(R.drawable.ic_expand)
            hideBtn?.visibility = View.VISIBLE
            durationView?.visibility = View.GONE
            annotationBtn?.visibility = View.GONE
            stopBtn?.visibility = View.GONE
        } else {
            hideBtn?.setImageResource(R.drawable.ic_hide)
            updateFloatingIcon()
        }
    }

    private fun addFloatingView() {
        if (floatingView == null || isAdded) return
        windowManager?.addView(floatingView, layoutParams)
        isAdded = true
    }

    private fun removeFloatingView() {
        if (floatingView == null || !isAdded) return
        windowManager?.removeView(floatingView)
        isAdded = false
    }

    private fun handleClick() {
        when (currentState) {
            RecordingState.IDLE -> {
                LogManager.log(LogManager.TAG_FLOAT, "click: idle -> start recording from overlay")
                OverlayRecordingStarter.start(this)
            }
            RecordingState.RECORDING -> {
                LogManager.log(LogManager.TAG_FLOAT, "click: pause recording")
                startService(Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_PAUSE
                })
            }
            RecordingState.PAUSED -> {
                LogManager.log(LogManager.TAG_FLOAT, "click: resume recording")
                startService(Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_RESUME
                })
            }
            RecordingState.COUNTDOWN -> return
        }
    }

    private fun updateDuration(durationMs: Long) {
        val durationView = floatingView?.findViewById<android.widget.TextView>(R.id.floating_duration) ?: return
        val isRecording = currentState == RecordingState.RECORDING || currentState == RecordingState.PAUSED
        if (isRecording && durationMs > 0 && !isCollapsed) {
            val hours = durationMs / 3600000
            val minutes = (durationMs % 3600000) / 60000
            val seconds = (durationMs % 60000) / 1000
            durationView.text = if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
            durationView.visibility = View.VISIBLE
        } else {
            durationView.visibility = View.GONE
        }
    }

    private fun updateCountdown(remaining: Int) {
        // Countdown displayed by FloatingCountdownService
    }

    private fun updateFloatingIcon() {
        val pauseBtn = floatingView?.findViewById<ImageView>(R.id.floating_pause_btn)

        val iconRes = when (currentState) {
            RecordingState.IDLE -> R.drawable.ic_record
            RecordingState.RECORDING -> R.drawable.ic_pause
            RecordingState.PAUSED -> R.drawable.ic_play
            RecordingState.COUNTDOWN -> R.drawable.ic_record
        }
        pauseBtn?.setImageResource(iconRes)

        val durationView = floatingView?.findViewById<android.widget.TextView>(R.id.floating_duration)
        val isRecording = currentState == RecordingState.RECORDING || currentState == RecordingState.PAUSED
        if (!isRecording && currentState != RecordingState.COUNTDOWN) {
            durationView?.visibility = View.GONE
        }

        // If collapsed, only show main button + expand icon
        if (isCollapsed) {
            val hideBtn = floatingView?.findViewById<ImageView>(R.id.floating_hide_btn)
            hideBtn?.setImageResource(R.drawable.ic_expand)
            hideBtn?.visibility = View.VISIBLE
            durationView?.visibility = View.GONE
            floatingView?.findViewById<View>(R.id.floating_annotation_btn)?.visibility = View.GONE
            floatingView?.findViewById<View>(R.id.floating_stop_btn)?.visibility = View.GONE
            return
        }

        val annotationBtn = floatingView?.findViewById<View>(R.id.floating_annotation_btn)
        val stopBtn = floatingView?.findViewById<View>(R.id.floating_stop_btn)
        val hideBtn = floatingView?.findViewById<View>(R.id.floating_hide_btn)
        if (isRecording) {
            annotationBtn?.visibility = View.VISIBLE
            stopBtn?.visibility = View.VISIBLE
            hideBtn?.visibility = View.VISIBLE
            hideBtn?.let { (it as? ImageView)?.setImageResource(R.drawable.ic_hide) }
        } else if (currentState == RecordingState.COUNTDOWN) {
            annotationBtn?.visibility = View.GONE
            stopBtn?.visibility = View.VISIBLE
            hideBtn?.visibility = View.GONE
        } else if (currentState == RecordingState.IDLE && isPersistentMode) {
            annotationBtn?.visibility = View.GONE
            stopBtn?.visibility = View.GONE
            hideBtn?.visibility = View.GONE
        } else {
            annotationBtn?.visibility = View.GONE
            stopBtn?.visibility = View.GONE
            hideBtn?.visibility = View.GONE
        }
    }

    // ── Foreground service support ──────────────────────────────────────

    private fun startForegroundIfNeeded() {
        if (isForeground) return
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.floating_window_notification))
            .setSmallIcon(R.drawable.ic_record)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isForeground = true
        LogManager.log(LogManager.TAG_FLOAT, "foreground service started")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.floating_window_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Floating window service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        LogManager.log(LogManager.TAG_FLOAT, "floating window destroyed")
        removeFloatingView()
        floatingView = null
        // Stop foreground when service is truly destroyed
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForeground = false
        }
        super.onDestroy()
    }
}