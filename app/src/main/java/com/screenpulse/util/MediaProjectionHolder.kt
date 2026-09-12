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
 * Releasing that dummy before a capture display exists can stop the whole
 * MediaProjection session. Prefer [adoptOrCreateDisplay] so the keep-alive
 * display is resized onto the encoder surface instead of being released first.
 */
object MediaProjectionHolder {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var projection: MediaProjection? = null
    private var dummyDisplay: VirtualDisplay? = null
    private var dummyReader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    @Volatile
    private var onStopped: (() -> Unit)? = null
    @Volatile
    private var suppressStopCount = 0

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
                    if (suppressStopCount > 0) {
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
        synchronized(this) {
            suppressStopCount++
        }
    }

    fun endDisplayReplacement() {
        // VirtualDisplay.release()/resize() may deliver onStop asynchronously
        // on the main looper. Drop that callback before restoring listeners.
        mainHandler.post {
            synchronized(this) {
                if (suppressStopCount > 0) suppressStopCount--
            }
        }
    }

    /**
     * Reuse the countdown keep-alive VirtualDisplay as the capture display.
     * Creating a second display while the dummy still exists, or releasing the
     * dummy first, can stop MediaProjection on Android 14+.
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
        suppressStopCount++
        try {
            val existing = dummyDisplay
            if (existing != null) {
                try {
                    existing.resize(width, height, densityDpi)
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
                } catch (e: Exception) {
                    LogManager.log(
                        LogManager.TAG_RECORD,
                        "MediaProjectionHolder resize keep-alive failed, creating new display",
                        e
                    )
                    dummyDisplay = null
                    val reader = dummyReader
                    dummyReader = null
                    runCatching { existing.release() }
                    runCatching { reader?.close() }
                }
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
        } finally {
            suppressStopCount--
        }
    }

    @Synchronized
    fun unpark() {
        suppressStopCount++
        try {
            releaseDummyLocked()
        } finally {
            suppressStopCount--
        }
    }

    @Synchronized
    fun park() {
        val p = projection ?: return
        if (dummyDisplay != null) {
            LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder already parked")
            return
        }
        suppressStopCount++
        try {
            val reader = ImageReader.newInstance(16, 16, PixelFormat.RGBA_8888, 2)
            dummyReader = reader
            dummyDisplay = p.createVirtualDisplay(
                "ScreenPulseKeepAlive",
                16,
                16,
                160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
            LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder parked (keep-alive display)")
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder park failed", e)
            releaseDummyLocked()
        } finally {
            suppressStopCount--
        }
    }

    fun clear() {
        val toStop: MediaProjection?
        synchronized(this) {
            suppressStopCount++
            onStopped = null
            toStop = takeProjectionLocked()
            suppressStopCount = 0
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
