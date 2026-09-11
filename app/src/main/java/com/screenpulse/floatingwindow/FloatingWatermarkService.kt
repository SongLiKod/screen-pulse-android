package com.screenpulse.floatingwindow

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class FloatingWatermarkService : Service() {

    companion object {
        const val ACTION_SHOW = "com.screenpulse.watermark.ACTION_SHOW"
        const val ACTION_HIDE = "com.screenpulse.watermark.ACTION_HIDE"
        const val EXTRA_TEXT = "text"
    }

    private var windowManager: WindowManager? = null
    private var watermarkView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                showWatermark(text)
            }
            ACTION_HIDE -> hideWatermark()
        }
        return START_STICKY
    }

    private fun showWatermark(text: String) {
        if (watermarkView != null || text.isEmpty()) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 32
            y = 32
        }

        watermarkView = TextView(this).apply {
            this.text = text
            setTextColor(Color.argb(128, 255, 255, 255))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(16, 8, 16, 8)
        }

        windowManager?.addView(watermarkView, layoutParams)
        isAdded = true
    }

    private fun hideWatermark() {
        if (watermarkView != null && isAdded) {
            windowManager?.removeView(watermarkView)
            isAdded = false
        }
        watermarkView = null
    }

    override fun onDestroy() {
        hideWatermark()
        super.onDestroy()
    }
}
