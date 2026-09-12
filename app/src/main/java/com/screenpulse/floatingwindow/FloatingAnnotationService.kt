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
    private var toolbarView: LinearLayout? = null
    private var selectedToolBtn: View? = null

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
        toolbarView = toolbar
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

    @SuppressLint("InflateParams")
    private fun showTextInputDialog() {
        // Temporarily make overlay focusable so dialog can receive input
        makeOverlayFocusable(true)

        val editText = EditText(this).apply {
            hint = getString(R.string.annotation_enter_text)
            setPadding(32, 24, 32, 24)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.annotation_add_text))
            .setView(editText)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    pendingTextPosition?.let { (x, y) ->
                        annotationView?.addText(text, x, y)
                    }
                }
                pendingTextPosition = null
                makeOverlayFocusable(false)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                pendingTextPosition = null
                makeOverlayFocusable(false)
            }
            .setOnCancelListener {
                pendingTextPosition = null
                makeOverlayFocusable(false)
            }
            .create()

        // Critical fix: Set TYPE_APPLICATION_OVERLAY for dialog shown from Service
        // Without this, BadTokenException crash occurs because Service has no window token
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
        )

        dialog.show()
    }

    /**
     * Toggle the overlay between focusable and not-focusable.
     * Needed so that the text input dialog can receive soft keyboard input.
     */
    private fun makeOverlayFocusable(focusable: Boolean) {
        if (layoutParams != null && rootView != null && isAdded) {
            if (focusable) {
                layoutParams!!.flags = layoutParams!!.flags and
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                layoutParams!!.flags = layoutParams!!.flags or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            windowManager?.updateViewLayout(rootView, layoutParams)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createToolbar(): LinearLayout {
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 8, 12, 8)
            setBackgroundColor(
                if (isDarkMode) ContextCompat.getColor(context, R.color.annotation_toolbar_bg_dark)
                else ContextCompat.getColor(context, R.color.annotation_toolbar_bg_light)
            )
        }

        // Tool buttons
        val tools = listOf(
            Triple(AnnotationOverlayView.AnnotationTool.PEN, R.drawable.ic_pen, getString(R.string.tool_pen)),
            Triple(AnnotationOverlayView.AnnotationTool.ARROW, R.drawable.ic_arrow, getString(R.string.tool_arrow)),
            Triple(AnnotationOverlayView.AnnotationTool.RECTANGLE, R.drawable.ic_rectangle, getString(R.string.tool_rectangle)),
            Triple(AnnotationOverlayView.AnnotationTool.CIRCLE, R.drawable.ic_circle, getString(R.string.tool_circle)),
            Triple(AnnotationOverlayView.AnnotationTool.TEXT, R.drawable.ic_text, getString(R.string.tool_text)),
        )

        tools.forEach { (tool, iconRes, desc) ->
            val btn = ImageView(this).apply {
                setImageResource(iconRes)
                contentDescription = desc
                setPadding(10, 10, 10, 10)
                setOnClickListener {
                    annotationView?.setTool(tool)
                    updateToolSelection(this)
                }
            }
            if (tool == AnnotationOverlayView.AnnotationTool.PEN) {
                selectedToolBtn = btn
                highlightToolButton(btn, true)
            }
            toolbar.addView(btn, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))
        }

        // Separator
        val separator = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#33999999"))
        }
        toolbar.addView(separator, LinearLayout.LayoutParams(2.dpToPx(), 36.dpToPx()).apply {
            marginStart = 6
            marginEnd = 6
        })

        // Color buttons
        val colors = AnnotationOverlayView.AnnotationColor.entries
        colors.forEach { color ->
            val btn = View(this).apply {
                setBackgroundColor(color.colorInt)
                contentDescription = color.displayName
                val size = 24.dpToPx()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = 4
                    marginEnd = 4
                }
                setOnClickListener {
                    annotationView?.setColor(color)
                    updateColorSelection(this@apply)
                }
            }
            if (color == AnnotationOverlayView.AnnotationColor.GREEN) {
                highlightColorButton(btn, true)
            }
            // Add border/stroke via background
            val wrapper = FrameLayout(this).apply {
                setPadding(2, 2, 2, 2)
                addView(btn)
            }
            toolbar.addView(wrapper, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 2
                marginEnd = 2
            })
        }

        // Separator
        val separator2 = View(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#33999999"))
        }
        toolbar.addView(separator2, LinearLayout.LayoutParams(2.dpToPx(), 36.dpToPx()).apply {
            marginStart = 6
            marginEnd = 6
        })

        // Undo button
        val undoBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_undo)
            contentDescription = getString(R.string.cd_undo)
            setPadding(10, 10, 10, 10)
            setOnClickListener { annotationView?.undo() }
        }
        toolbar.addView(undoBtn, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))

        // Clear button
        val clearBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_clear)
            contentDescription = getString(R.string.cd_clear_draw)
            setPadding(10, 10, 10, 10)
            setOnClickListener { annotationView?.clearAll() }
        }
        toolbar.addView(clearBtn, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))

        // Close button
        val closeBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = getString(R.string.cd_close)
            setPadding(10, 10, 10, 10)
            setOnClickListener { hideAnnotationOverlay() }
        }
        toolbar.addView(closeBtn, LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx()))

        return toolbar
    }

    private fun updateToolSelection(selectedBtn: View) {
        highlightToolButton(selectedToolBtn, false)
        highlightToolButton(selectedBtn, true)
        selectedToolBtn = selectedBtn
    }

    private fun highlightToolButton(btn: View?, highlight: Boolean) {
        btn?.let {
            if (highlight) {
                it.setBackgroundColor(
                    if (isDarkMode) android.graphics.Color.parseColor("#44FFFFFF")
                    else android.graphics.Color.parseColor("#442E7D32")
                )
            } else {
                it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        }
    }

    private var selectedColorView: View? = null

    private fun updateColorSelection(selectedBtn: View) {
        highlightColorButton(selectedColorView, false)
        highlightColorButton(selectedBtn, true)
        selectedColorView = selectedBtn
    }

    private fun highlightColorButton(btn: View?, highlight: Boolean) {
        btn?.let {
            val size = if (highlight) 28.dpToPx() else 24.dpToPx()
            it.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = 0
                marginEnd = 0
            }
            it.requestLayout()
        }
    }

    private fun hideAnnotationOverlay() {
        if (rootView != null && isAdded) {
            windowManager?.removeView(rootView)
            isAdded = false
        }
        rootView = null
        annotationView = null
        toolbarView = null
        selectedToolBtn = null
        selectedColorView = null
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        hideAnnotationOverlay()
        super.onDestroy()
    }
}