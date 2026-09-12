package com.screenpulse.floatingwindow

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.ScaleAnimation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import com.screenpulse.R
import com.screenpulse.util.LogManager

class FloatingCountdownService : Service() {

    companion object {
        const val ACTION_SHOW = "com.screenpulse.countdown.ACTION_SHOW"
        const val ACTION_UPDATE = "com.screenpulse.countdown.ACTION_UPDATE"
        const val ACTION_HIDE = "com.screenpulse.countdown.ACTION_HIDE"
        const val EXTRA_REMAINING = "remaining"
    }

    private var windowManager: WindowManager? = null
    private var countdownView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                LogManager.log(LogManager.TAG_FLOAT, "CountdownOverlay: SHOW")
                showCountdown()
            }
            ACTION_UPDATE -> {
                val remaining = intent.getIntExtra(EXTRA_REMAINING, 0)
                LogManager.log(LogManager.TAG_FLOAT, "CountdownOverlay: UPDATE remaining=$remaining")
                updateCountdown(remaining)
            }
            ACTION_HIDE -> {
                LogManager.log(LogManager.TAG_FLOAT, "CountdownOverlay: HIDE")
                hideCountdown()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCountdown() {
        if (isAdded) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        countdownView = View.inflate(this, R.layout.layout_floating_countdown, null)

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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Make the dim view not intercept touch (pass-through)
        countdownView?.findViewById<View>(R.id.countdown_dim)?.let { dimView ->
            dimView.isClickable = false
            dimView.isFocusable = false
        }
        countdownView?.isClickable = false
        countdownView?.isFocusable = false

        windowManager?.addView(countdownView, layoutParams)
        isAdded = true
    }

    private fun updateCountdown(remaining: Int) {
        if (!isAdded || countdownView == null) {
            if (remaining > 0) showCountdown()
            else return
        }

        val numberView = countdownView?.findViewById<TextView>(R.id.countdown_number) ?: return

        if (remaining > 0) {
            numberView.text = remaining.toString()
            numberView.visibility = View.VISIBLE

            // Animate: scale from 1.3x to 1.0x with fade-in for each second
            numberView.clearAnimation()
            numberView.alpha = 0.3f
            numberView.scaleX = 1.3f
            numberView.scaleY = 1.3f

            val scaleAnim = ScaleAnimation(
                1.3f, 1.0f, 1.3f, 1.0f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 400L
                interpolator = DecelerateInterpolator()
            }

            val alphaAnim = AlphaAnimation(0.3f, 0.9f).apply {
                duration = 400L
            }

            val animSet = AnimationSet(true).apply {
                addAnimation(scaleAnim)
                addAnimation(alphaAnim)
            }

            numberView.startAnimation(animSet)
        } else {
            // Countdown finished — animate out then remove
            numberView.clearAnimation()
            val fadeOut = AlphaAnimation(0.9f, 0f).apply {
                duration = 300L
            }
            numberView.startAnimation(fadeOut)

            handler.postDelayed({
                hideCountdown()
            }, 350L)
        }
    }

    private fun hideCountdown() {
        if (!isAdded || countdownView == null) return
        try {
            windowManager?.removeView(countdownView)
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_FLOAT, "CountdownOverlay: removeView failed", e)
        }
        isAdded = false
        countdownView = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideCountdown()
        super.onDestroy()
    }
}