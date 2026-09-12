package com.screenpulse.ui.annotation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.screenpulse.R

class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class AnnotationTool {
        PEN, TEXT, ARROW, RECTANGLE, CIRCLE, NONE
    }

    enum class AnnotationColor(val colorInt: Int, val displayName: String) {
        RED(Color.RED, "Red"),
        GREEN(Color.parseColor("#FF2E7D32"), "Green"),
        BLUE(Color.BLUE, "Blue"),
        YELLOW(Color.YELLOW, "Yellow"),
        WHITE(Color.WHITE, "White"),
        BLACK(Color.BLACK, "Black")
    }

    private var currentTool = AnnotationTool.NONE
    private var isVisible = false
    private var currentColor: AnnotationColor = AnnotationColor.GREEN

    private val penPaint = Paint().apply {
        color = currentColor.colorInt
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arrowPaint = Paint().apply {
        color = currentColor.colorInt
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = currentColor.colorInt
        textSize = 48f
        isAntiAlias = true
    }

    private val shapePaint = Paint().apply {
        color = currentColor.colorInt
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val drawings = mutableListOf<DrawingItem>()
    private var currentPath: Path? = null
    private var arrowStartX = 0f
    private var arrowStartY = 0f
    private var arrowEndX = 0f
    private var arrowEndY = 0f
    private var isDrawingArrow = false

    // Shape drawing state
    private var shapeStartX = 0f
    private var shapeStartY = 0f
    private var shapeEndX = 0f
    private var shapeEndY = 0f
    private var isDrawingShape = false

    data class DrawingItem(
        val tool: AnnotationTool,
        val path: Path? = null,
        val startX: Float = 0f,
        val startY: Float = 0f,
        val endX: Float = 0f,
        val endY: Float = 0f,
        val text: String = "",
        val textX: Float = 0f,
        val textY: Float = 0f,
        val color: Int = Color.GREEN
    )

    fun setTool(tool: AnnotationTool) {
        currentTool = tool
    }

    fun setColor(color: AnnotationColor) {
        currentColor = color
        penPaint.color = color.colorInt
        arrowPaint.color = color.colorInt
        textPaint.color = color.colorInt
        shapePaint.color = color.colorInt
    }

    fun getColor(): AnnotationColor = currentColor

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
            val paint = when (item.tool) {
                AnnotationTool.PEN -> penPaint
                AnnotationTool.ARROW -> arrowPaint
                AnnotationTool.TEXT -> textPaint
                AnnotationTool.RECTANGLE, AnnotationTool.CIRCLE -> shapePaint
                AnnotationTool.NONE -> penPaint
            }
            val savedColor = paint.color
            paint.color = item.color

            when (item.tool) {
                AnnotationTool.PEN -> {
                    item.path?.let { canvas.drawPath(it, paint) }
                }
                AnnotationTool.ARROW -> {
                    drawArrow(canvas, item.startX, item.startY, item.endX, item.endY, paint)
                }
                AnnotationTool.TEXT -> {
                    canvas.drawText(item.text, item.textX, item.textY, paint)
                }
                AnnotationTool.RECTANGLE -> {
                    val rect = RectF(
                        minOf(item.startX, item.endX),
                        minOf(item.startY, item.endY),
                        maxOf(item.startX, item.endX),
                        maxOf(item.startY, item.endY)
                    )
                    canvas.drawRect(rect, paint)
                }
                AnnotationTool.CIRCLE -> {
                    val rect = RectF(
                        minOf(item.startX, item.endX),
                        minOf(item.startY, item.endY),
                        maxOf(item.startX, item.endX),
                        maxOf(item.startY, item.endY)
                    )
                    canvas.drawOval(rect, paint)
                }
                AnnotationTool.NONE -> {}
            }

            paint.color = savedColor
        }

        // Draw current pen path
        if (currentPath != null) {
            canvas.drawPath(currentPath!!, penPaint)
        }

        // Draw current arrow preview
        if (isDrawingArrow) {
            drawArrow(canvas, arrowStartX, arrowStartY, arrowEndX, arrowEndY, arrowPaint)
        }

        // Draw current shape preview
        if (isDrawingShape) {
            when (currentTool) {
                AnnotationTool.RECTANGLE -> {
                    val rect = RectF(
                        minOf(shapeStartX, shapeEndX),
                        minOf(shapeStartY, shapeEndY),
                        maxOf(shapeStartX, shapeEndX),
                        maxOf(shapeStartY, shapeEndY)
                    )
                    canvas.drawRect(rect, shapePaint)
                }
                AnnotationTool.CIRCLE -> {
                    val rect = RectF(
                        minOf(shapeStartX, shapeEndX),
                        minOf(shapeStartY, shapeEndY),
                        maxOf(shapeStartX, shapeEndX),
                        maxOf(shapeStartY, shapeEndY)
                    )
                    canvas.drawOval(rect, shapePaint)
                }
                else -> {}
            }
        }
    }

    private fun drawArrow(canvas: Canvas, sx: Float, sy: Float, ex: Float, ey: Float, paint: Paint) {
        canvas.drawLine(sx, sy, ex, ey, paint)

        val angle = Math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())
        val arrowLength = 30f

        val x1 = (ex - arrowLength * Math.cos(angle - Math.PI / 6)).toFloat()
        val y1 = (ey - arrowLength * Math.sin(angle - Math.PI / 6)).toFloat()
        val x2 = (ex - arrowLength * Math.cos(angle + Math.PI / 6)).toFloat()
        val y2 = (ey - arrowLength * Math.sin(angle + Math.PI / 6)).toFloat()

        canvas.drawLine(ex, ey, x1, y1, paint)
        canvas.drawLine(ex, ey, x2, y2, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isVisible || currentTool == AnnotationTool.NONE) return false

        when (currentTool) {
            AnnotationTool.PEN -> handlePenTouch(event)
            AnnotationTool.ARROW -> handleArrowTouch(event)
            AnnotationTool.TEXT -> handleTextTouch(event)
            AnnotationTool.RECTANGLE -> handleShapeTouch(event)
            AnnotationTool.CIRCLE -> handleShapeTouch(event)
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
                    drawings.add(DrawingItem(
                        tool = AnnotationTool.PEN,
                        path = Path(it),
                        color = currentColor.colorInt
                    ))
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
                    endY = arrowEndY,
                    color = currentColor.colorInt
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

    private fun handleShapeTouch(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                shapeStartX = event.x
                shapeStartY = event.y
                shapeEndX = event.x
                shapeEndY = event.y
                isDrawingShape = true
            }
            MotionEvent.ACTION_MOVE -> {
                shapeEndX = event.x
                shapeEndY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                shapeEndX = event.x
                shapeEndY = event.y
                drawings.add(DrawingItem(
                    tool = currentTool,
                    startX = shapeStartX,
                    startY = shapeStartY,
                    endX = shapeEndX,
                    endY = shapeEndY,
                    color = currentColor.colorInt
                ))
                isDrawingShape = false
                invalidate()
            }
        }
    }

    var onTextPositionSelected: ((Float, Float) -> Unit)? = null

    fun addText(text: String, x: Float, y: Float) {
        drawings.add(DrawingItem(
            tool = AnnotationTool.TEXT,
            text = text,
            textX = x,
            textY = y,
            color = currentColor.colorInt
        ))
        invalidate()
    }
}