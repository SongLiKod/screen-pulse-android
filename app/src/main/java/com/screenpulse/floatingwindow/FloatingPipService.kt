package com.screenpulse.floatingwindow

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.screenpulse.R
import com.screenpulse.util.LogManager

class FloatingPipService : Service() {

    companion object {
        const val ACTION_SHOW = "com.screenpulse.pip.ACTION_SHOW"
        const val ACTION_HIDE = "com.screenpulse.pip.ACTION_HIDE"
        const val ACTION_SET_SIZE = "com.screenpulse.pip.ACTION_SET_SIZE"
        const val EXTRA_SIZE = "size"
    }

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var textureView: TextureView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false
    private var pipSize = 150

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                pipSize = intent.getIntExtra(EXTRA_SIZE, 150)
                LogManager.log(LogManager.TAG_PIP, "show pip overlay size=$pipSize")
                showPipOverlay()
            }
            ACTION_HIDE -> {
                LogManager.log(LogManager.TAG_PIP, "hide pip overlay")
                hidePipOverlay()
            }
            ACTION_SET_SIZE -> {
                pipSize = intent.getIntExtra(EXTRA_SIZE, 150)
                updatePipSize()
            }
        }

        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showPipOverlay() {
        if (rootView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            pipSize,
            pipSize,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        rootView = FrameLayout(this)

        textureView = TextureView(this)
        rootView?.addView(textureView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val border = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(4, ContextCompat.getColor(this@FloatingPipService, R.color.pip_border_color))
            }
        }
        rootView?.addView(border, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        rootView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams?.x ?: 0
                    initialY = layoutParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams?.x = initialX - (event.rawX - initialTouchX).toInt()
                    layoutParams?.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(rootView, layoutParams)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(rootView, layoutParams)
        isAdded = true

        startCamera()
    }

    private fun updatePipSize() {
        if (rootView == null || !isAdded) return
        layoutParams?.width = pipSize
        layoutParams?.height = pipSize
        windowManager?.updateViewLayout(rootView, layoutParams)
    }

    @SuppressLint("MissingPermission")
    private fun startCamera() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val cameraId = getFrontCameraId(cameraManager) ?: return

            textureView?.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera(cameraManager, cameraId, surface)
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }

            if (textureView?.isAvailable == true) {
                openCamera(cameraManager, cameraId, textureView!!.surfaceTexture!!)
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun getFrontCameraId(cameraManager: CameraManager): String? {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                return id
            }
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(cameraManager: CameraManager, cameraId: String, surfaceTexture: SurfaceTexture) {
        try {
            surfaceTexture.setDefaultBufferSize(pipSize, pipSize)
            val surface = android.view.Surface(surfaceTexture)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession(camera, surface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, null)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun createCaptureSession(camera: CameraDevice, surface: android.view.Surface) {
        try {
            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        }
                        session.setRepeatingRequest(request.build(), null, null)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hidePipOverlay() {
        stopCamera()
        if (rootView != null && isAdded) {
            windowManager?.removeView(rootView)
            isAdded = false
        }
        rootView = null
        textureView = null
    }

    private fun stopCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    override fun onDestroy() {
        LogManager.log(LogManager.TAG_PIP, "pip service destroyed")
        hidePipOverlay()
        super.onDestroy()
    }
}
