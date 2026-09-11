package com.screenpulse.ui.annotation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.screenpulse.R

class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class AnnotationTool {
        PEN, TEXT, ARROW, NONE
    }

    private var currentTool = AnnotationTool.NONE
    private var isVisible = false

    private val penColor = ContextCompat.getColor(context, R.color.annotation_pen_color)

    private val penPaint = Paint().apply {
        color = penColor
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arrowPaint = Paint().apply {
        color = penColor
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = penColor
        textSize = 48f
        isAntiAlias = true
    }

    private val drawings = mutableListOf<DrawingItem>()
    private var currentPath: Path? = null
    private var arrowStartX = 0f
    private var arrowStartY = 0f
    private var arrowEndX = 0f
    private var arrowEndY = 0f
    private var isDrawingArrow = false

    data class DrawingItem(
        val tool: AnnotationTool,
        val path: Path? = null,
        val startX: Float = 0f,
        val startY: Float = 0f,
        val endX: Float = 0f,
        val endY: Float = 0f,
        val text: String = "",
        val textX: Float = 0f,
        val textY: Float = 0f
    )

    fun setTool(tool: AnnotationTool) {
        currentTool = tool
    }

    fun setVisible(visible: Boolean) {
        isVisible = visible
        if (!visible) {
            drawings.clear()
            invalidate()
        }
    }

    fun clearAll() {
        drawings.clear()
        invalidate()
    }

    fun undo() {
        if (drawings.isNotEmpty()) {
            drawings.removeAt(drawings.size - 1)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isVisible) return

        for (item in drawings) {
            when (item.tool) {
                AnnotationTool.PEN -> {
                    item.path?.let { canvas.drawPath(it, penPaint) }
                }
                AnnotationTool.ARROW -> {
                    drawArrow(canvas, item.startX, item.startY, item.endX, item.endY)
                }
                AnnotationTool.TEXT -> {
                    canvas.drawText(item.text, item.textX, item.textY, textPaint)
                }
                AnnotationTool.NONE -> {}
            }
        }

        if (currentPath != null) {
            canvas.drawPath(currentPath!!, penPaint)
        }

        if (isDrawingArrow) {
            drawArrow(canvas, arrowStartX, arrowStartY, arrowEndX, arrowEndY)
        }
    }

    private fun drawArrow(canvas: Canvas, sx: Float, sy: Float, ex: Float, ey: Float) {
        canvas.drawLine(sx, sy, ex, ey, arrowPaint)

        val angle = Math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())
        val arrowLength = 30f

        val x1 = (ex - arrowLength * Math.cos(angle - Math.PI / 6)).toFloat()
        val y1 = (ey - arrowLength * Math.sin(angle - Math.PI / 6)).toFloat()
        val x2 = (ex - arrowLength * Math.cos(angle + Math.PI / 6)).toFloat()
        val y2 = (ey - arrowLength * Math.sin(angle + Math.PI / 6)).toFloat()

        canvas.drawLine(ex, ey, x1, y1, arrowPaint)
        canvas.drawLine(ex, ey, x2, y2, arrowPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isVisible || currentTool == AnnotationTool.NONE) return false

        when (currentTool) {
            AnnotationTool.PEN -> handlePenTouch(event)
            AnnotationTool.ARROW -> handleArrowTouch(event)
            AnnotationTool.TEXT -> handleTextTouch(event)
            AnnotationTool.NONE -> {}
        }
        return true
    }

    private fun handlePenTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                currentPath = Path().apply {
                    moveTo(event.x, event.y)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.lineTo(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                currentPath?.let {
                    drawings.add(DrawingItem(tool = AnnotationTool.PEN, path = Path(it)))
                }
                currentPath = null
                invalidate()
            }
        }
    }

    private fun handleArrowTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                arrowStartX = event.x
                arrowStartY = event.y
                arrowEndX = event.x
                arrowEndY = event.y
                isDrawingArrow = true
            }
            MotionEvent.ACTION_MOVE -> {
                arrowEndX = event.x
                arrowEndY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                arrowEndX = event.x
                arrowEndY = event.y
                drawings.add(DrawingItem(
                    tool = AnnotationTool.ARROW,
                    startX = arrowStartX,
                    startY = arrowStartY,
                    endX = arrowEndX,
                    endY = arrowEndY
                ))
                isDrawingArrow = false
                invalidate()
            }
        }
    }

    private fun handleTextTouch(event: MotionEvent) {
        if (event.action == MotionEvent.ACTION_UP) {
            onTextPositionSelected?.invoke(event.x, event.y)
        }
    }

    var onTextPositionSelected: ((Float, Float) -> Unit)? = null

    fun addText(text: String, x: Float, y: Float) {
        drawings.add(DrawingItem(
            tool = AnnotationTool.TEXT,
            text = text,
            textX = x,
            textY = y
        ))
        invalidate()
    }
}
