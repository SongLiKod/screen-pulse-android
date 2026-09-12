package com.screenpulse.floatingwindow

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
        val sideInset = 16.dpToPx()
        rootView?.addView(toolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            leftMargin = sideInset
            rightMargin = sideInset
            bottomMargin = navigationBarInset() + 12.dpToPx()
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
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(10.dpToPx(), 8.dpToPx(), 10.dpToPx(), 8.dpToPx())
            background = roundedRect(
                if (isDarkMode) 0xF21E1E1E.toInt() else 0xF2FFFFFF.toInt(),
                22.dpToPx().toFloat()
            )
            elevation = 10.dpToPx().toFloat()
        }

        val btnSize = 36.dpToPx()
        val toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tools = listOf(
            Triple(AnnotationOverlayView.AnnotationTool.PEN, R.drawable.ic_pen, getString(R.string.tool_pen)),
            Triple(AnnotationOverlayView.AnnotationTool.ARROW, R.drawable.ic_arrow, getString(R.string.tool_arrow)),
            Triple(AnnotationOverlayView.AnnotationTool.RECTANGLE, R.drawable.ic_rectangle, getString(R.string.tool_rectangle)),
            Triple(AnnotationOverlayView.AnnotationTool.CIRCLE, R.drawable.ic_circle, getString(R.string.tool_circle)),
            Triple(AnnotationOverlayView.AnnotationTool.TEXT, R.drawable.ic_text, getString(R.string.tool_text)),
        )

        tools.forEach { (tool, iconRes, desc) ->
            val btn = toolButton(iconRes, desc) {
                annotationView?.setTool(tool)
                updateToolSelection(this)
            }
            if (tool == AnnotationOverlayView.AnnotationTool.PEN) {
                selectedToolBtn = btn
                highlightToolButton(btn, true)
            }
            toolRow.addView(btn, LinearLayout.LayoutParams(btnSize, btnSize))
        }

        toolRow.addView(verticalDivider(), LinearLayout.LayoutParams(1.dpToPx(), 20.dpToPx()).apply {
            marginStart = 6.dpToPx()
            marginEnd = 6.dpToPx()
        })

        val undoBtn = toolButton(R.drawable.ic_undo, getString(R.string.cd_undo)) {
            annotationView?.undo()
        }
        toolRow.addView(undoBtn, LinearLayout.LayoutParams(btnSize, btnSize))

        val clearBtn = toolButton(R.drawable.ic_clear, getString(R.string.cd_clear_draw)) {
            annotationView?.clearAll()
        }
        toolRow.addView(clearBtn, LinearLayout.LayoutParams(btnSize, btnSize))

        val closeBtn = toolButton(R.drawable.ic_close, getString(R.string.cd_close)) {
            hideAnnotationOverlay()
        }
        toolRow.addView(closeBtn, LinearLayout.LayoutParams(btnSize, btnSize))

        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 8.dpToPx(), 0, 2.dpToPx())
        }
        val chipSize = 22.dpToPx()
        AnnotationOverlayView.AnnotationColor.entries.forEach { color ->
            val btn = View(this).apply {
                tag = color.colorInt
                contentDescription = color.displayName
                background = colorChipDrawable(color.colorInt, color == AnnotationOverlayView.AnnotationColor.GREEN)
                setOnClickListener {
                    annotationView?.setColor(color)
                    updateColorSelection(this)
                }
            }
            if (color == AnnotationOverlayView.AnnotationColor.GREEN) {
                selectedColorView = btn
            }
            colorRow.addView(btn, LinearLayout.LayoutParams(chipSize, chipSize).apply {
                marginStart = 6.dpToPx()
                marginEnd = 6.dpToPx()
            })
        }

        toolbar.addView(toolRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        toolbar.addView(colorRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return toolbar
    }

    private fun toolButton(iconRes: Int, description: String, onClick: ImageView.() -> Unit): ImageView {
        return ImageView(this).apply {
            setImageResource(iconRes)
            contentDescription = description
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(7.dpToPx(), 7.dpToPx(), 7.dpToPx(), 7.dpToPx())
            setOnClickListener { onClick() }
        }
    }

    private fun verticalDivider(): View {
        return View(this).apply {
            background = GradientDrawable().apply {
                setColor(if (isDarkMode) 0x44FFFFFF else 0x33999999)
                cornerRadius = 1.dpToPx().toFloat()
            }
        }
    }

    private fun roundedRect(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun colorChipDrawable(color: Int, selected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            val stroke = if (selected) {
                if (isDarkMode) Color.WHITE else Color.parseColor("#FF2E7D32")
            } else if (color == Color.WHITE || color == Color.YELLOW) {
                Color.parseColor("#66000000")
            } else {
                Color.parseColor("#33FFFFFF")
            }
            setStroke(if (selected) 3.dpToPx() else 1.dpToPx(), stroke)
        }
    }

    private fun navigationBarInset(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.windowInsets
                ?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                ?.bottom
                ?.coerceAtLeast(12.dpToPx())
                ?: 24.dpToPx()
        } else {
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 24.dpToPx()
        }
    }

    private fun updateToolSelection(selectedBtn: View) {
        highlightToolButton(selectedToolBtn, false)
        highlightToolButton(selectedBtn, true)
        selectedToolBtn = selectedBtn
    }

    private fun highlightToolButton(btn: View?, highlight: Boolean) {
        btn?.background = if (highlight) {
            roundedRect(
                if (isDarkMode) Color.parseColor("#44FFFFFF") else Color.parseColor("#332E7D32"),
                10.dpToPx().toFloat()
            )
        } else {
            null
        }
    }

    private var selectedColorView: View? = null

    private fun updateColorSelection(selectedBtn: View) {
        highlightColorButton(selectedColorView, false)
        highlightColorButton(selectedBtn, true)
        selectedColorView = selectedBtn
    }

    private fun highlightColorButton(btn: View?, highlight: Boolean) {
        val color = btn?.tag as? Int ?: return
        btn.background = colorChipDrawable(color, highlight)
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