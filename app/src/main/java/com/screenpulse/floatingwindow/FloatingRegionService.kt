package com.screenpulse.floatingwindow

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.screenpulse.util.LogManager

class FloatingRegionService : Service() {

    companion object {
        const val ACTION_SHOW = "com.screenpulse.region.ACTION_SHOW"
        const val ACTION_HIDE = "com.screenpulse.region.ACTION_HIDE"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
    }

    private var windowManager: WindowManager? = null
    private var regionView: RegionBorderView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WrongConstant")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val x = intent.getIntExtra(EXTRA_X, 0)
                val y = intent.getIntExtra(EXTRA_Y, 0)
                val w = intent.getIntExtra(EXTRA_WIDTH, 0)
                val h = intent.getIntExtra(EXTRA_HEIGHT, 0)
                LogManager.log(LogManager.TAG_RECORD, "Region overlay show: $x,$y ${w}x$h")
                showRegionOverlay(x, y, w, h)
            }
            ACTION_HIDE -> hideRegionOverlay()
        }
        return START_STICKY
    }

    @SuppressLint("WrongConstant")
    private fun showRegionOverlay(x: Int, y: Int, width: Int, height: Int) {
        if (regionView != null) hideRegionOverlay()
        if (width <= 0 || height <= 0) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            width,
            height,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        regionView = RegionBorderView(this)
        windowManager?.addView(regionView, layoutParams)
        isAdded = true
    }

    private fun hideRegionOverlay() {
        if (regionView != null && isAdded) {
            windowManager?.removeView(regionView)
            isAdded = false
        }
        regionView = null
    }

    override fun onDestroy() {
        LogManager.log(LogManager.TAG_RECORD, "Region overlay destroyed")
        hideRegionOverlay()
        super.onDestroy()
    }

    private inner class RegionBorderView(context: Context) : View(context) {
        private val borderPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = strokeInset
            canvas.drawRect(inset, inset, width.toFloat() - inset, height.toFloat() - inset, borderPaint)
        }

        private val strokeInset: Float get() = 3f
    }
}