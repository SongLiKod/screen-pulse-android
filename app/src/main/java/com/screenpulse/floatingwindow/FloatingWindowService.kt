package com.screenpulse.floatingwindow

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import com.screenpulse.R
import com.screenpulse.service.ScreenRecordService
import com.screenpulse.viewmodel.RecordingState

class FloatingWindowService : Service() {

    companion object {
        const val ACTION_UPDATE_STATE = "com.screenpulse.floating.ACTION_UPDATE_STATE"
        const val EXTRA_STATE = "state"
        const val ACTION_HIDE = "com.screenpulse.floating.ACTION_HIDE"
        const val ACTION_SHOW = "com.screenpulse.floating.ACTION_SHOW"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false
    private var currentState = RecordingState.IDLE

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_STATE -> {
                val stateValue = intent.getIntExtra(EXTRA_STATE, 0)
                currentState = RecordingState.entries[stateValue]
                updateFloatingIcon()
            }
            ACTION_HIDE -> removeFloatingView()
            ACTION_SHOW -> addFloatingView()
        }

        if (floatingView == null) {
            createFloatingView()
        }

        updateFloatingIcon()

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

        updateFloatingIcon()

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        floatingView?.findViewById<View>(R.id.floating_container)?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams?.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx < 10 && dy < 10) {
                        handleClick()
                    }
                    true
                }
                else -> false
            }
        }

        floatingView?.findViewById<View>(R.id.floating_screenshot_btn)?.setOnClickListener {
            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_SCREENSHOT
            }
            startService(intent)
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

        floatingView?.findViewById<View>(R.id.floating_hide_btn)?.setOnClickListener {
            removeFloatingView()
        }

        addFloatingView()
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
        val intent = when (currentState) {
            RecordingState.IDLE -> Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
            }
            RecordingState.RECORDING -> Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_PAUSE
            }
            RecordingState.PAUSED -> Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_RESUME
            }
            RecordingState.COUNTDOWN -> return
        }
        startService(intent)
    }

    private fun isDarkMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateFloatingIcon() {
        val iconView = floatingView?.findViewById<ImageView>(R.id.floating_icon)

        val iconRes = when (currentState) {
            RecordingState.IDLE -> R.drawable.ic_record
            RecordingState.RECORDING -> R.drawable.ic_pause
            RecordingState.PAUSED -> R.drawable.ic_play
            RecordingState.COUNTDOWN -> R.drawable.ic_record
        }
        iconView?.setImageResource(iconRes)

        val bgRes = if (isDarkMode()) R.drawable.floating_btn_bg_dark else R.drawable.floating_btn_bg
        floatingView?.findViewById<View>(R.id.floating_container)?.setBackgroundResource(bgRes)

        val screenshotBtn = floatingView?.findViewById<View>(R.id.floating_screenshot_btn)
        val annotationBtn = floatingView?.findViewById<View>(R.id.floating_annotation_btn)
        val stopBtn = floatingView?.findViewById<View>(R.id.floating_stop_btn)
        val hideBtn = floatingView?.findViewById<View>(R.id.floating_hide_btn)
        if (currentState == RecordingState.RECORDING || currentState == RecordingState.PAUSED) {
            screenshotBtn?.visibility = View.VISIBLE
            annotationBtn?.visibility = View.VISIBLE
            stopBtn?.visibility = View.VISIBLE
            hideBtn?.visibility = View.VISIBLE
        } else {
            screenshotBtn?.visibility = View.GONE
            annotationBtn?.visibility = View.GONE
            stopBtn?.visibility = View.GONE
            hideBtn?.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        removeFloatingView()
        floatingView = null
        super.onDestroy()
    }
}
