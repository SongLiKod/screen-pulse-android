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
 *
 * Key fix: onTouchEvent returns true for ACTION_DOWN even when not dragging, so that
 * subsequent MOVE/UP events are delivered to this view. This ensures drag works even
 * when the touch starts in a gap between child views.
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
    private var isTouchTracked = false  // Whether we're tracking a touch gesture
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = ev.rawX
                initialTouchY = ev.rawY
                isDragging = false
                isTouchTracked = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging && isTouchTracked) {
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
                isTouchTracked = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Always consume DOWN so we receive subsequent MOVE/UP events.
                // This ensures drag works even when touch starts in a gap between children.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    onDragListener?.invoke(dx, dy)
                    return true
                }
                // Check if we should start dragging (in case onInterceptTouchEvent
                // didn't intercept because the child returned false on DOWN)
                if (isTouchTracked && !isDragging) {
                    val dx = Math.abs(event.rawX - initialTouchX)
                    val dy = Math.abs(event.rawY - initialTouchY)
                    if (dx > touchSlop || dy > touchSlop) {
                        isDragging = true
                        onDragStart?.invoke()
                        val ddx = (event.rawX - initialTouchX).toInt()
                        val ddy = (event.rawY - initialTouchY).toInt()
                        onDragListener?.invoke(ddx, ddy)
                        return true
                    }
                }
                return true  // Consume to keep receiving events
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                isTouchTracked = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isTouchTracked = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}