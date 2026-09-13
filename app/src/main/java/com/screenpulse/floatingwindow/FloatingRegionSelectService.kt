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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.screenpulse.R
import com.screenpulse.ScreenPulseApp
import com.screenpulse.repository.CustomRegion
import com.screenpulse.repository.SavedRegion
import com.screenpulse.util.LogManager
import com.screenpulse.util.OverlayRecordingStarter
import com.screenpulse.util.RecordingCache
import com.screenpulse.util.RegionPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingRegionSelectService : Service() {

    companion object {
        const val ACTION_SHOW = "com.screenpulse.regionselect.ACTION_SHOW"
        const val ACTION_ADD = "com.screenpulse.regionselect.ACTION_ADD"
        const val ACTION_HIDE = "com.screenpulse.regionselect.ACTION_HIDE"
        const val EXTRA_MODE = "mode"
        const val EXTRA_EDIT_REGION_ID = "edit_region_id"
        const val MODE_RECORD = "record"
        const val MODE_ADD = "add"
        const val MODE_EDIT = "edit"
    }

    private enum class Handle {
        NONE, MOVE, DRAW, N, S, E, W, NW, NE, SW, SE
    }

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var overlayView: RegionOverlayView? = null
    private var infoView: TextView? = null
    private var chipRow: LinearLayout? = null
    private var topBar: LinearLayout? = null
    private var buttonLayout: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var isAdded = false
    private val currentRect = RectF()
    private var mode = MODE_RECORD
    private var screenWidth = 0
    private var screenHeight = 0
    private var activeHandle = Handle.NONE
    private var startX = 0f
    private var startY = 0f
    private var startRect = RectF()
    private var savedRegions: List<SavedRegion> = emptyList()
    private var editRegionId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_RECORD
                editRegionId = intent.getStringExtra(EXTRA_EDIT_REGION_ID)
                showSelector()
            }
            ACTION_ADD -> {
                mode = MODE_ADD
                editRegionId = null
                showSelector()
            }
            ACTION_HIDE -> hideSelector()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSelector() {
        if (isAdded) {
            loadInitialRect()
            refreshChips()
            return
        }
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

        val themed = ContextThemeWrapper(this, R.style.Theme_ScreenPulse)
        val root = FrameLayout(themed)
        overlayView = RegionOverlayView(themed)
        root.addView(
            overlayView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val chipScroll = HorizontalScrollView(themed).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            elevation = 16f
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
        }
        chipRow = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipScroll.addView(chipRow)
        infoView = TextView(themed).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            setPadding(24, 8, 24, 8)
        }
        topBar = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            elevation = 16f
            isClickable = true
            isFocusable = true
            addView(
                chipScroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                infoView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                topMargin = statusBarInset() + 8.dpToPx()
            }
        )

        val confirmBtn = Button(themed).apply {
            text = getString(R.string.confirm)
            setOnClickListener { confirmRegion() }
        }
        val cancelBtn = Button(themed).apply {
            text = getString(R.string.cancel)
            setOnClickListener { hideSelector() }
        }
        buttonLayout = LinearLayout(themed).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            elevation = 16f
            isClickable = true
            isFocusable = true
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
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
                bottomMargin = navigationBarInset() + 12.dpToPx()
            }
        )

        rootView = root
        topBar?.bringToFront()
        buttonLayout?.bringToFront()
        runCatching {
            windowManager?.addView(root, layoutParams)
            isAdded = true
            loadInitialRect()
            LogManager.log(LogManager.TAG_FLOAT, "RegionSelect overlay shown mode=$mode")
        }.onFailure { e ->
            LogManager.log(LogManager.TAG_FLOAT, "RegionSelect overlay addView failed", e)
            isAdded = false
            rootView = null
            overlayView = null
            infoView = null
            chipRow = null
            topBar = null
            buttonLayout = null
            stopSelf()
        }
    }

    private fun loadInitialRect() {
        scope.launch {
            val regions = withContext(Dispatchers.IO) {
                runCatching { RecordingCache.ensureConfigLoaded(this@FloatingRegionSelectService) }
                loadSavedRegions()
            }
            savedRegions = regions
            refreshChips()
            val region = if (mode == MODE_EDIT) {
                regions.firstOrNull { it.id == editRegionId }?.toCustomRegion()
                    ?: RegionPresets.fullScreen(screenWidth, screenHeight)
            } else {
                val cfg = RecordingCache.config
                val last = CustomRegion(cfg.regionWidth, cfg.regionHeight, cfg.regionOffsetX, cfg.regionOffsetY)
                if (last.isValid()) {
                    RegionPresets.clampToScreen(last, screenWidth, screenHeight)
                } else {
                    RegionPresets.fullScreen(screenWidth, screenHeight)
                }
            }
            applyRegion(region)
        }
    }

    private fun applyRegion(region: CustomRegion) {
        val clamped = RegionPresets.clampToScreen(region, screenWidth, screenHeight)
        currentRect.set(
            clamped.offsetX.toFloat(),
            clamped.offsetY.toFloat(),
            (clamped.offsetX + clamped.width).toFloat(),
            (clamped.offsetY + clamped.height).toFloat()
        )
        overlayView?.invalidate()
        updateInfo()
    }

    private fun refreshChips() {
        val row = chipRow ?: return
        row.removeAllViews()
        RegionPresets.namedPresets(screenWidth, screenHeight).forEach { preset ->
            addChip(getString(preset.nameRes)) { applyRegion(preset.region) }
        }
        savedRegions.forEach { saved ->
            addChip(saved.name) { applyRegion(saved.toCustomRegion()) }
        }
        addChip(getString(R.string.region_draw_new)) {
            currentRect.setEmpty()
            overlayView?.invalidate()
            updateInfo()
        }
        topBar?.bringToFront()
        buttonLayout?.bringToFront()
    }

    private fun addChip(label: String, onClick: () -> Unit) {
        val padH = 18.dpToPx()
        val padV = 12.dpToPx()
        val chip = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(padH, padV, padH, padV)
            minHeight = 40.dpToPx()
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20.dpToPx().toFloat()
                setColor(Color.parseColor("#E62E7D32"))
            }
            setOnClickListener { onClick() }
        }
        chipRow?.addView(chip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = 10.dpToPx()
        })
    }

    private suspend fun loadSavedRegions(): List<SavedRegion> {
        val repo = (application as? ScreenPulseApp)?.settingsRepository ?: return emptyList()
        return runCatching { repo.savedRegions.first() }.getOrDefault(emptyList())
    }

    private fun currentCustomRegion(): CustomRegion {
        val left = min(currentRect.left, currentRect.right)
        val top = min(currentRect.top, currentRect.bottom)
        val right = max(currentRect.left, currentRect.right)
        val bottom = max(currentRect.top, currentRect.bottom)
        return CustomRegion(
            width = (right - left).toInt(),
            height = (bottom - top).toInt(),
            offsetX = left.toInt(),
            offsetY = top.toInt()
        )
    }

    private fun updateInfo() {
        val region = currentCustomRegion()
        infoView?.text = if (region.isValid()) {
            getString(R.string.region_size_info, region.width, region.height, region.offsetX, region.offsetY)
        } else {
            getString(R.string.region_hint)
        }
    }

    private fun confirmRegion() {
        val region = RegionPresets.clampToScreen(currentCustomRegion(), screenWidth, screenHeight)
        if (!region.isValid()) {
            infoView?.text = getString(R.string.region_too_small)
            return
        }
        if (mode == MODE_ADD) {
            persistSavedRegion(region)
            hideSelector()
            return
        }
        if (mode == MODE_EDIT) {
            persistEditedRegion(region)
            hideSelector()
            return
        }
        RecordingCache.applyRegion(region)
        persistLastRegion(region)
        LogManager.log(
            LogManager.TAG_FLOAT,
            "RegionSelect overlay confirmed: ${region.offsetX},${region.offsetY} ${region.width}x${region.height}"
        )
        hideSelector()
        OverlayRecordingStarter.startCapture(this)
    }

    private fun persistLastRegion(region: CustomRegion) {
        val repo = (application as? ScreenPulseApp)?.settingsRepository ?: return
        scope.launch(Dispatchers.IO) { runCatching { repo.setCustomRegion(region) } }
    }

    private fun persistSavedRegion(region: CustomRegion) {
        val repo = (application as? ScreenPulseApp)?.settingsRepository ?: return
        val name = getString(R.string.region_saved_name, savedRegions.size + 1)
        scope.launch(Dispatchers.IO) {
            runCatching {
                repo.addSavedRegion(
                    SavedRegion(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        width = region.width,
                        height = region.height,
                        offsetX = region.offsetX,
                        offsetY = region.offsetY
                    )
                )
            }
        }
    }

    private fun persistEditedRegion(region: CustomRegion) {
        val repo = (application as? ScreenPulseApp)?.settingsRepository ?: return
        val existing = savedRegions.firstOrNull { it.id == editRegionId }
        if (existing == null) {
            persistSavedRegion(region)
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                repo.updateSavedRegion(
                    existing.copy(
                        width = region.width,
                        height = region.height,
                        offsetX = region.offsetX,
                        offsetY = region.offsetY
                    )
                )
            }
        }
    }

    private fun hideSelector() {
        if (rootView != null && isAdded) {
            runCatching { windowManager?.removeView(rootView) }
        }
        isAdded = false
        rootView = null
        overlayView = null
        infoView = null
        chipRow = null
        topBar = null
        buttonLayout = null
        editRegionId = null
        currentRect.setEmpty()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (rootView != null && isAdded) {
            runCatching { windowManager?.removeView(rootView) }
        }
        isAdded = false
        rootView = null
        overlayView = null
        super.onDestroy()
    }

    private fun statusBarInset(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.windowInsets
                ?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                ?.top
                ?.coerceAtLeast(24.dpToPx())
                ?: 48.dpToPx()
        } else {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else 48.dpToPx()
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

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private inner class RegionOverlayView(context: Context) : View(context) {
        private val handleHit = 44.dp()
        private val handleSize = 22.dp()
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
        private val handlePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val handleStroke = Paint().apply {
            color = ContextCompat.getColor(this@FloatingRegionSelectService, R.color.brand_green)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        private val dimPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            dimPath.reset()
            dimPath.fillType = Path.FillType.EVEN_ODD
            dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            if (hasRect()) {
                normalizeRect()
                dimPath.addRect(currentRect, Path.Direction.CW)
            }
            canvas.drawPath(dimPath, dimPaint)
            if (hasRect()) {
                canvas.drawRect(currentRect, borderPaint)
                handlePoints().forEach { (x, y) ->
                    canvas.drawCircle(x, y, handleSize, handlePaint)
                    canvas.drawCircle(x, y, handleSize, handleStroke)
                }
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN && isInChrome(event.rawX, event.rawY)) {
                return false
            }
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    startRect.set(currentRect)
                    activeHandle = hitTest(event.x, event.y)
                    if (activeHandle == Handle.DRAW) {
                        currentRect.set(startX, startY, startX, startY)
                    }
                    invalidate()
                    updateInfo()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    when (activeHandle) {
                        Handle.DRAW -> currentRect.set(
                            min(startX, event.x),
                            min(startY, event.y),
                            max(startX, event.x),
                            max(startY, event.y)
                        )
                        Handle.MOVE -> moveRect(event.x - startX, event.y - startY)
                        Handle.NONE -> {}
                        else -> resizeRect(activeHandle, event.x, event.y)
                    }
                    if (hasRect() || activeHandle != Handle.DRAW) {
                        clampRect()
                    }
                    invalidate()
                    updateInfo()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    normalizeRect()
                    if (hasRect()) {
                        clampRect()
                    }
                    activeHandle = Handle.NONE
                    invalidate()
                    updateInfo()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun isInChrome(rawX: Float, rawY: Float): Boolean {
            return isViewHit(topBar, rawX, rawY) || isViewHit(buttonLayout, rawX, rawY)
        }

        private fun isViewHit(view: View?, rawX: Float, rawY: Float): Boolean {
            if (view == null || view.width <= 0 || view.height <= 0) return false
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            return rawX >= loc[0] && rawX <= loc[0] + view.width &&
                rawY >= loc[1] && rawY <= loc[1] + view.height
        }

        private fun hasRect(): Boolean = currentRect.width() > 1f && currentRect.height() > 1f

        private fun normalizeRect() {
            val left = min(currentRect.left, currentRect.right)
            val top = min(currentRect.top, currentRect.bottom)
            val right = max(currentRect.left, currentRect.right)
            val bottom = max(currentRect.top, currentRect.bottom)
            currentRect.set(left, top, right, bottom)
        }

        private fun clampRect() {
            if (width < RegionPresets.MIN_SIZE || height < RegionPresets.MIN_SIZE) return
            if (!hasRect()) return
            normalizeRect()
            val minSize = RegionPresets.MIN_SIZE.toFloat()
            val maxLeft = (width - RegionPresets.MIN_SIZE).toFloat().coerceAtLeast(0f)
            val maxTop = (height - RegionPresets.MIN_SIZE).toFloat().coerceAtLeast(0f)
            if (currentRect.left < 0) currentRect.offset(-currentRect.left, 0f)
            if (currentRect.top < 0) currentRect.offset(0f, -currentRect.top)
            if (currentRect.right > width) currentRect.offset(width - currentRect.right, 0f)
            if (currentRect.bottom > height) currentRect.offset(0f, height - currentRect.bottom)
            currentRect.left = currentRect.left.coerceIn(0f, maxLeft)
            currentRect.top = currentRect.top.coerceIn(0f, maxTop)
            val minRight = (currentRect.left + minSize).coerceAtMost(width.toFloat())
            val minBottom = (currentRect.top + minSize).coerceAtMost(height.toFloat())
            currentRect.right = currentRect.right.coerceIn(minRight, width.toFloat())
            currentRect.bottom = currentRect.bottom.coerceIn(minBottom, height.toFloat())
        }

        private fun moveRect(dx: Float, dy: Float) {
            currentRect.set(startRect)
            currentRect.offset(dx, dy)
        }

        private fun resizeRect(handle: Handle, x: Float, y: Float) {
            currentRect.set(startRect)
            when (handle) {
                Handle.N -> currentRect.top = y
                Handle.S -> currentRect.bottom = y
                Handle.W -> currentRect.left = x
                Handle.E -> currentRect.right = x
                Handle.NW -> {
                    currentRect.left = x
                    currentRect.top = y
                }
                Handle.NE -> {
                    currentRect.right = x
                    currentRect.top = y
                }
                Handle.SW -> {
                    currentRect.left = x
                    currentRect.bottom = y
                }
                Handle.SE -> {
                    currentRect.right = x
                    currentRect.bottom = y
                }
                else -> {}
            }
        }

        private fun handlePoints(): List<Pair<Float, Float>> {
            val cx = currentRect.centerX()
            val cy = currentRect.centerY()
            return listOf(
                currentRect.left to currentRect.top,
                cx to currentRect.top,
                currentRect.right to currentRect.top,
                currentRect.left to cy,
                currentRect.right to cy,
                currentRect.left to currentRect.bottom,
                cx to currentRect.bottom,
                currentRect.right to currentRect.bottom
            )
        }

        private fun hitTest(x: Float, y: Float): Handle {
            if (hasRect()) {
                val handles = listOf(
                    Handle.NW to (currentRect.left to currentRect.top),
                    Handle.N to (currentRect.centerX() to currentRect.top),
                    Handle.NE to (currentRect.right to currentRect.top),
                    Handle.W to (currentRect.left to currentRect.centerY()),
                    Handle.E to (currentRect.right to currentRect.centerY()),
                    Handle.SW to (currentRect.left to currentRect.bottom),
                    Handle.S to (currentRect.centerX() to currentRect.bottom),
                    Handle.SE to (currentRect.right to currentRect.bottom)
                )
                handles.forEach { (handle, point) ->
                    if (abs(x - point.first) <= handleHit && abs(y - point.second) <= handleHit) {
                        return handle
                    }
                }
                if (currentRect.contains(x, y)) return Handle.MOVE
            }
            return Handle.DRAW
        }

        private fun Int.dp(): Float = this * resources.displayMetrics.density
    }
}
