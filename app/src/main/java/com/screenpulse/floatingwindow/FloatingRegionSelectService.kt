package com.screenpulse.floatingwindow

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.screenpulse.R
import com.screenpulse.repository.CustomRegion
import com.screenpulse.util.LogManager
import com.screenpulse.util.OverlayRecordingStarter
import com.screenpulse.util.RecordingCache

class FloatingRegionSelectService : Service() {

    companion object {
        const val ACTION_SHOW = "com.screenpulse.regionselect.ACTION_SHOW"
        const val ACTION_HIDE = "com.screenpulse.regionselect.ACTION_HIDE"
    }

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var overlayView: RegionOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false
    private val currentRect = RectF()
    private var isDrawing = false
    private var startX = 0f
    private var startY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showSelector()
            ACTION_HIDE -> hideSelector()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSelector() {
        if (isAdded) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val root = FrameLayout(this)
        overlayView = RegionOverlayView(this)
        root.addView(
            overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val confirmBtn = Button(this).apply {
            text = getString(R.string.confirm)
            setOnClickListener { confirmRegion() }
        }
        val cancelBtn = Button(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener { hideSelector() }
        }
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(cancelBtn)
            addView(confirmBtn)
        }
        root.addView(
            buttonLayout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = 48
            }
        )

        val hintView = TextView(this).apply {
            text = getString(R.string.region_hint)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(32, 16, 32, 16)
        }
        root.addView(
            hintView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = 48
            }
        )

        rootView = root
        windowManager?.addView(root, layoutParams)
        isAdded = true
        LogManager.log(LogManager.TAG_FLOAT, "RegionSelect overlay shown")
    }

    private fun confirmRegion() {
        val region = CustomRegion(
            width = currentRect.width().toInt(),
            height = currentRect.height().toInt(),
            offsetX = currentRect.left.toInt(),
            offsetY = currentRect.top.toInt()
        )
        if (region.width <= 0 || region.height <= 0) {
            LogManager.log(LogManager.TAG_FLOAT, "RegionSelect overlay: empty region ignored")
            return
        }
        RecordingCache.applyRegion(region)
        LogManager.log(
            LogManager.TAG_FLOAT,
            "RegionSelect overlay confirmed: ${region.offsetX},${region.offsetY} ${region.width}x${region.height}"
        )
        hideSelector()
        OverlayRecordingStarter.startCapture(this)
    }

    private fun hideSelector() {
        if (rootView != null && isAdded) {
            runCatching { windowManager?.removeView(rootView) }
        }
        isAdded = false
        rootView = null
        overlayView = null
        currentRect.setEmpty()
        stopSelf()
    }

    override fun onDestroy() {
        if (rootView != null && isAdded) {
            runCatching { windowManager?.removeView(rootView) }
        }
        isAdded = false
        rootView = null
        overlayView = null
        super.onDestroy()
    }

    private inner class RegionOverlayView(context: Context) : View(context) {
        private val dimPaint = Paint().apply {
            color = Color.argb(90, 0, 0, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val borderPaint = Paint().apply {
            color = ContextCompat.getColor(this@FloatingRegionSelectService, R.color.brand_green)
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        private val dimPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            dimPath.reset()
            dimPath.fillType = Path.FillType.EVEN_ODD
            dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (isDrawing || currentRect.width() > 0) {
                dimPath.addRect(currentRect, Path.Direction.CW)
            }
            canvas.drawPath(dimPath, dimPaint)
            if (isDrawing || currentRect.width() > 0) {
                canvas.drawRect(currentRect, borderPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    currentRect.set(startX, startY, startX, startY)
                    isDrawing = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDrawing) {
                        currentRect.set(
                            minOf(startX, event.x),
                            minOf(startY, event.y),
                            maxOf(startX, event.x),
                            maxOf(startY, event.y)
                        )
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    isDrawing = false
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
