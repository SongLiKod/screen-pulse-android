package com.screenpulse.util

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper

/**
 * Holds a live MediaProjection so countdown can finish into a real recording
 * and the floating window can start without opening the app UI.
 *
 * Android 14+ invalidates the consent token if getMediaProjection() is delayed
 * until after a countdown. We obtain the projection immediately after consent
 * and keep a tiny dummy VirtualDisplay while waiting to start.
 */
object MediaProjectionHolder {

    @Volatile
    private var projection: MediaProjection? = null
    private var dummyDisplay: VirtualDisplay? = null
    private var dummyReader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    @Volatile
    private var onStopped: (() -> Unit)? = null

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
                LogManager.log(LogManager.TAG_RECORD, "MediaProjection stopped by system")
                val listener: (() -> Unit)?
                synchronized(this@MediaProjectionHolder) {
                    if (projection == null) {
                        return
                    }
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
        mediaProjection.registerCallback(cb, Handler(Looper.getMainLooper()))
        LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder attached")
        runCatching { previous?.stop() }
    }

    @Synchronized
    fun unpark() {
        releaseDummyLocked()
    }

    @Synchronized
    fun park() {
        val p = projection ?: return
        releaseDummyLocked()
        try {
            val reader = ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2)
            dummyReader = reader
            dummyDisplay = p.createVirtualDisplay(
                "ScreenPulseKeepAlive",
                2,
                2,
                1,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
            LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder parked (keep-alive display)")
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_RECORD, "MediaProjectionHolder park failed", e)
            releaseDummyLocked()
        }
    }

    fun clear() {
        val toStop: MediaProjection?
        synchronized(this) {
            onStopped = null
            toStop = takeProjectionLocked()
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
