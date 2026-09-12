package com.screenpulse.util

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.view.Surface

/**
 * Holds a live MediaProjection so countdown can finish into a real recording
 * and the floating window can start without opening the app UI.
 *
 * Android 14+ invalidates the consent token if getMediaProjection() is delayed
 * until after a countdown. We obtain the projection immediately after consent
 * and keep a tiny dummy VirtualDisplay while waiting to start.
 *
 * Releasing that dummy, or treating a replacement onStop as a real stop,
 * kills the whole MediaProjection session. Reuse the keep-alive display
 * and ignore onStop until capture is running.
 */
object MediaProjectionHolder {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clearReplacementFlag = Runnable {
        replacingDisplays = false
    }

    @Volatile
    private var projection: MediaProjection? = null
    private var dummyDisplay: VirtualDisplay? = null
    private var dummyReader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    @Volatile
    private var onStopped: (() -> Unit)? = null
    @Volatile
    private var replacingDisplays = false

    val isActive: Boolean
        get() = projection != null

    fun get(): MediaProjection? = projection

    fun setOnStopped(listener: (() -> Unit)?) {
        onStopped = listener
    }

    @Synchronized
    fun attach(mediaProjection: MediaProjection) {
        if (projection === mediaProjection) return
        val previous = takeProjectionLocked()
        projection = mediaProjection
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                val listener: (() -> Unit)?
                synchronized(this@MediaProjectionHolder) {
                    if (replacingDisplays) {
                        LogManager.log(LogManager.TAG_RECORD, "MediaProjection onStop ignored during display replacement")
                        return
                    }
                    if (projection == null) {
                        return
                    }
                    LogManager.log(LogManager.TAG_RECORD, "MediaProjection stopped by system")
                    releaseDummyLocked()
                    projection = null
                    callback = null
                    listener = onStopped
                    onStopped = null
                }
                listener?.invoke()
            }
        }
        callback = cb
        mediaProjection.registerCallback(cb, mainHandler)
        LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder attached")
        runCatching { previous?.stop() }
    }

    fun beginDisplayReplacement() {
        mainHandler.removeCallbacks(clearReplacementFlag)
        replacingDisplays = true
    }

    fun endDisplayReplacement() {
        mainHandler.removeCallbacks(clearReplacementFlag)
        mainHandler.postDelayed(clearReplacementFlag, 2000)
    }

    /**
     * Reuse the countdown keep-alive VirtualDisplay as the capture display.
     * Creating a second display or releasing the dummy first can stop
     * MediaProjection on Android 14+.
     */
    @Synchronized
    fun adoptOrCreateDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface
    ): VirtualDisplay? {
        val p = projection ?: return null
        replacingDisplays = true
        try {
            val existing = dummyDisplay
            if (existing != null) {
                runCatching { existing.resize(width, height, densityDpi) }
                existing.setSurface(surface)
                dummyDisplay = null
                val reader = dummyReader
                dummyReader = null
                runCatching { reader?.close() }
                LogManager.log(
                    LogManager.TAG_RECORD,
                    "MediaProjectionHolder reused keep-alive display ${width}x$height"
                )
                return existing
            }
            return p.createVirtualDisplay(
                name,
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder adoptOrCreateDisplay failed", e)
            return null
        }
    }

    @Synchronized
    fun park(width: Int = 16, height: Int = 16, densityDpi: Int = 160) {
        val p = projection ?: return
        replacingDisplays = true
        val w = width.coerceAtLeast(16)
        val h = height.coerceAtLeast(16)
        val dpi = densityDpi.coerceAtLeast(160)
        val existing = dummyDisplay
        if (existing != null) {
            runCatching { existing.resize(w, h, dpi) }
            LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder already parked, resize ${w}x$h")
            return
        }
        val sizes = listOf(w to h, 1280 to 720, 16 to 16)
        for ((pw, ph) in sizes) {
            try {
                releaseDummyLocked()
                val reader = ImageReader.newInstance(pw, ph, PixelFormat.RGBA_8888, 2)
                dummyReader = reader
                dummyDisplay = p.createVirtualDisplay(
                    "ScreenPulseKeepAlive",
                    pw,
                    ph,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null
                )
                LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder parked ${pw}x$ph dpi=$dpi")
                return
            } catch (e: Exception) {
                LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder park failed ${pw}x$ph", e)
                releaseDummyLocked()
            }
        }
    }

    fun clear() {
        val toStop: MediaProjection?
        synchronized(this) {
            mainHandler.removeCallbacks(clearReplacementFlag)
            replacingDisplays = true
            onStopped = null
            toStop = takeProjectionLocked()
            replacingDisplays = false
        }
        runCatching { toStop?.stop() }
        LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder cleared")
    }

    private fun takeProjectionLocked(): MediaProjection? {
        releaseDummyLocked()
        val p = projection
        val cb = callback
        if (p != null && cb != null) {
            runCatching { p.unregisterCallback(cb) }
        }
        projection = null
        callback = null
        return p
    }

    private fun releaseDummyLocked() {
        runCatching { dummyDisplay?.release() }
        dummyDisplay = null
        runCatching { dummyReader?.close() }
        dummyReader = null
    }
}
