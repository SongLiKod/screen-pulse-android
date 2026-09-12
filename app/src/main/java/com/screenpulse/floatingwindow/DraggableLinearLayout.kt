package com.screenpulse.floatingwindow

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout

/**
 * A LinearLayout that intercepts drag gestures while allowing child clicks to work normally.
 *
 * When the user touches and drags beyond the touch slop, this view intercepts the event
 * and delivers drag callbacks. When the user taps without dragging, children receive
 * the click event as usual.
 */
class DraggableLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Callback invoked with (rawDeltaX, rawDeltaY) during drag. */
    var onDragListener: ((dx: Int, dy: Int) -> Unit)? = null

    /** Callback invoked when a drag gesture begins. Use this to capture the initial position. */
    var onDragStart: (() -> Unit)? = null

    private var isDragging = false
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = ev.rawX
                initialTouchY = ev.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) {
                    val dx = Math.abs(ev.rawX - initialTouchX)
                    val dy = Math.abs(ev.rawY - initialTouchY)
                    if (dx > touchSlop || dy > touchSlop) {
                        isDragging = true
                        onDragStart?.invoke()
                        // Cancel child touch handling so we take over
                        return true
                    }
                }
                if (isDragging) {
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isDragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    onDragListener?.invoke(dx, dy)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}