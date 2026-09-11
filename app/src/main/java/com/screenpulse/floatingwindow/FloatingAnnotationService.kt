package com.screenpulse.floatingwindow

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.screenpulse.R
import com.screenpulse.ui.annotation.AnnotationOverlayView
import com.screenpulse.util.LogManager

class FloatingAnnotationService : Service() {

    companion object {
        const val ACTION_SHOW = "com.screenpulse.annotation.ACTION_SHOW"
        const val ACTION_HIDE = "com.screenpulse.annotation.ACTION_HIDE"
        const val ACTION_SET_TOOL = "com.screenpulse.annotation.ACTION_SET_TOOL"
        const val EXTRA_TOOL = "tool"
    }

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var annotationView: AnnotationOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false
    private var isDarkMode = false
    private var pendingTextPosition: Pair<Float, Float>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

        when (intent?.action) {
            ACTION_SHOW -> {
                LogManager.log(LogManager.TAG_ANNOTATION, "show annotation overlay")
                showAnnotationOverlay()
            }
            ACTION_HIDE -> {
                LogManager.log(LogManager.TAG_ANNOTATION, "hide annotation overlay")
                hideAnnotationOverlay()
            }
            ACTION_SET_TOOL -> {
                val toolValue = intent.getIntExtra(EXTRA_TOOL, 0)
                val tool = AnnotationOverlayView.AnnotationTool.entries[toolValue]
                LogManager.log(LogManager.TAG_ANNOTATION, "set tool -> $tool")
                annotationView?.setTool(tool)
            }
            else -> LogManager.log(LogManager.TAG_ANNOTATION, "onStartCommand action=${intent?.action}")
        }

        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAnnotationOverlay() {
        if (rootView != null) return

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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        rootView = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        annotationView = AnnotationOverlayView(this).apply {
            setVisible(true)
            setTool(AnnotationOverlayView.AnnotationTool.PEN)
            onTextPositionSelected = { x, y ->
                pendingTextPosition = Pair(x, y)
                showTextInputDialog()
            }
        }

        rootView?.addView(annotationView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val toolbar = createToolbar()
        rootView?.addView(toolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = 100
        })

        windowManager?.addView(rootView, layoutParams)
        isAdded = true
    }

    private fun showTextInputDialog() {
        val tempView = View(this)
        val editText = EditText(this).apply {
            hint = "Enter text"
            setPadding(32, 24, 32, 24)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Text")
            .setView(editText)
            .setPositiveButton("Confirm") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    pendingTextPosition?.let { (x, y) ->
                        annotationView?.addText(text, x, y)
                    }
                }
                pendingTextPosition = null
            }
            .setNegativeButton("Cancel") { _, _ ->
                pendingTextPosition = null
            }
            .setOnDismissListener {
                rootView?.requestLayout()
            }
            .show()
    }

    private fun createToolbar(): View {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
            setBackgroundColor(
                if (isDarkMode) ContextCompat.getColor(context, R.color.annotation_toolbar_bg_dark)
                else ContextCompat.getColor(context, R.color.annotation_toolbar_bg_light)
            )
        }

        val tools = listOf(
            Triple(AnnotationOverlayView.AnnotationTool.PEN, R.drawable.ic_pen, "Pen"),
            Triple(AnnotationOverlayView.AnnotationTool.ARROW, R.drawable.ic_arrow, "Arrow"),
            Triple(AnnotationOverlayView.AnnotationTool.TEXT, R.drawable.ic_text, "Text"),
        )

        tools.forEach { (tool, iconRes, desc) ->
            val btn = ImageView(this).apply {
                setImageResource(iconRes)
                contentDescription = desc
                setPadding(12, 12, 12, 12)
                setOnClickListener {
                    annotationView?.setTool(tool)
                }
            }
            toolbar.addView(btn, LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx()))
        }

        val undoBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_undo)
            contentDescription = "Undo"
            setPadding(12, 12, 12, 12)
            setOnClickListener { annotationView?.undo() }
        }
        toolbar.addView(undoBtn, LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx()))

        val clearBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_clear)
            contentDescription = "Clear"
            setPadding(12, 12, 12, 12)
            setOnClickListener { annotationView?.clearAll() }
        }
        toolbar.addView(clearBtn, LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx()))

        val closeBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Close"
            setPadding(12, 12, 12, 12)
            setOnClickListener { hideAnnotationOverlay() }
        }
        toolbar.addView(closeBtn, LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx()))

        return toolbar
    }

    private fun hideAnnotationOverlay() {
        if (rootView != null && isAdded) {
            windowManager?.removeView(rootView)
            isAdded = false
        }
        rootView = null
        annotationView = null
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        hideAnnotationOverlay()
        super.onDestroy()
    }
}
