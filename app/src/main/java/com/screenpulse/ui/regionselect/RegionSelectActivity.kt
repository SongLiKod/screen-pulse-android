package com.screenpulse.ui.regionselect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.screenpulse.R

class RegionSelectActivity : Activity() {

    companion object {
        const val EXTRA_REGION_X = "region_x"
        const val EXTRA_REGION_Y = "region_y"
        const val EXTRA_REGION_WIDTH = "region_width"
        const val EXTRA_REGION_HEIGHT = "region_height"
    }

    private var startX = 0f
    private var startY = 0f
    private var currentRect = RectF()
    private var isDrawing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.TRANSPARENT)

        val overlayView = RegionOverlayView(this)
        root.addView(overlayView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val confirmBtn = Button(this).apply {
            text = getString(R.string.confirm)
            setOnClickListener {
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_REGION_X, currentRect.left.toInt())
                    putExtra(EXTRA_REGION_Y, currentRect.top.toInt())
                    putExtra(EXTRA_REGION_WIDTH, currentRect.width().toInt())
                    putExtra(EXTRA_REGION_HEIGHT, currentRect.height().toInt())
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }

        val cancelBtn = Button(this).apply {
            text = getString(R.string.cancel)
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        val buttonLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            addView(cancelBtn)
            addView(confirmBtn)
        }

        val buttonParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM
            bottomMargin = 48
        }

        root.addView(buttonLayout, buttonParams)

        val hintView = TextView(this).apply {
            text = getString(R.string.region_hint)
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(32, 16, 32, 16)
        }

        val hintParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = 48
        }

        root.addView(hintView, hintParams)

        setContentView(root)
    }

    private inner class RegionOverlayView(context: Context) : View(context) {

        init {
            setBackgroundColor(Color.TRANSPARENT)
        }

        private val borderPaint = Paint().apply {
            color = ContextCompat.getColor(this@RegionSelectActivity, R.color.brand_green)
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
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
