package com.screenpulse.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.screenpulse.util.LogManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Uses OpenGL ES to crop a region from a full-screen VirtualDisplay capture
 * and render the cropped region to the encoder's input Surface.
 * Also supports rendering a watermark (text or image) overlay on the video.
 *
 * Flow:
 * 1. VirtualDisplay captures full screen → writes to [inputSurface] (SurfaceTexture)
 * 2. On each frame, [drawFrame] reads the texture, crops the region, renders to encoder surface
 * 3. If watermark is set, renders watermark overlay on top
 * 4. MediaCodec reads the frame from its input surface
 */
class RegionCropRenderer {

    companion object {
        private const val TAG = "${LogManager.TAG_RECORD}:RegionCrop"

        private val VERTEX_SHADER = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private val WATERMARK_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private val WATERMARK_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    // Screen capture OES texture
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    // Screen capture shader program
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var texMatrixHandle = 0

    // Watermark shader program
    private var watermarkProgram = 0
    private var watermarkPositionHandle = 0
    private var watermarkTexCoordHandle = 0
    private var watermarkTextureId = 0
    private var watermarkVertexBuffer: java.nio.FloatBuffer? = null
    private var watermarkTexCoordBuffer: java.nio.FloatBuffer? = null
    private var hasWatermark = false

    // Texture coordinate buffer for the crop region
    private var cropTexCoordBuffer: java.nio.FloatBuffer? = null

    // Vertex buffer for full-screen quad
    private var vertexBuffer: java.nio.FloatBuffer? = null

    private var frameAvailable = false
    private val lock = Object()
    private var outputWidth = 0
    private var outputHeight = 0
    private val glThread = HandlerThread("ScreenPulseCropGL")
    private var glHandler: Handler? = null
    @Volatile
    private var released = false

    /**
     * Initialize EGL context, compile shaders, create SurfaceTexture.
     */
    fun init(
        encoderSurface: Surface,
        screenWidth: Int,
        screenHeight: Int,
        cropX: Int,
        cropY: Int,
        cropWidth: Int,
        cropHeight: Int
    ): Boolean {
        LogManager.log(TAG, "init: screen=${screenWidth}x${screenHeight} crop=[$cropX,$cropY ${cropWidth}x${cropHeight}]")
        released = false
        outputWidth = cropWidth.coerceAtLeast(1)
        outputHeight = cropHeight.coerceAtLeast(1)
        ensureGlThread()
        val handler = glHandler
        if (handler == null) {
            LogManager.log(TAG, "init: GL thread unavailable")
            return false
        }

        val ok = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        handler.post {
            try {
                ok.set(initOnGlThread(encoderSurface, screenWidth, screenHeight, cropX, cropY, cropWidth, cropHeight))
            } catch (e: Exception) {
                LogManager.log(TAG, "init on GL thread failed", e)
            } finally {
                latch.countDown()
            }
        }
        val finished = latch.await(5, TimeUnit.SECONDS)
        if (!finished || !ok.get()) {
            LogManager.log(TAG, "init: failed finished=$finished ok=${ok.get()}")
            release()
            return false
        }
        LogManager.log(TAG, "init: complete")
        return true
    }

    private fun ensureGlThread() {
        if (glThread.isAlive) {
            if (glHandler == null) glHandler = Handler(glThread.looper)
            return
        }
        glThread.start()
        glHandler = Handler(glThread.looper)
    }

    private fun initOnGlThread(
        encoderSurface: Surface,
        screenWidth: Int,
        screenHeight: Int,
        cropX: Int,
        cropY: Int,
        cropWidth: Int,
        cropHeight: Int
    ): Boolean {
        if (!setupEGL(encoderSurface)) {
            LogManager.log(TAG, "init: EGL setup failed")
            return false
        }
        if (!setupGL()) {
            LogManager.log(TAG, "init: GL setup failed")
            return false
        }
        if (!setupWatermarkGL()) {
            LogManager.log(TAG, "init: Watermark GL setup failed, watermark will be disabled")
        }
        if (!createSurfaceTexture(screenWidth, screenHeight)) {
            LogManager.log(TAG, "init: SurfaceTexture creation failed")
            return false
        }

        val left = cropX.toFloat() / screenWidth.toFloat()
        val right = (cropX + cropWidth).toFloat() / screenWidth.toFloat()
        val top = 1f - cropY.toFloat() / screenHeight.toFloat()
        val bottom = 1f - (cropY + cropHeight).toFloat() / screenHeight.toFloat()
        LogManager.log(TAG, "init: crop tex coords: left=$left top=$top right=$right bottom=$bottom")

        val texCoords = floatArrayOf(
            left, bottom,
            right, bottom,
            left, top,
            right, top
        )
        cropTexCoordBuffer = java.nio.ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
            .apply { position(0) }

        val vertices = floatArrayOf(
            -1f, -1f,
             1f, -1f,
            -1f,  1f,
             1f,  1f
        )
        vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .apply { position(0) }

        surfaceTexture?.setOnFrameAvailableListener({ _ ->
            synchronized(lock) { frameAvailable = true }
            drawFrameInternal(force = false)
        }, glHandler)

        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        return true
    }

    fun getInputSurface(): Surface? = inputSurface

    /**
     * Set a watermark bitmap to be rendered on top of each frame.
     * The watermark is positioned at the bottom-right corner with margin.
     *
     * @param bitmap The watermark image (with alpha channel for transparency)
     * @param videoWidth The output video frame width in pixels
     * @param videoHeight The output video frame height in pixels
     */
    fun setWatermark(bitmap: Bitmap, videoWidth: Int, videoHeight: Int) {
        val handler = glHandler
        if (handler == null) {
            LogManager.log(TAG, "setWatermark: GL thread unavailable")
            return
        }
        val latch = CountDownLatch(1)
        handler.post {
            try {
                setWatermarkOnGlThread(bitmap, videoWidth, videoHeight)
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    private fun setWatermarkOnGlThread(bitmap: Bitmap, videoWidth: Int, videoHeight: Int) {
        if (watermarkProgram == 0) {
            LogManager.log(TAG, "setWatermark: watermark program not compiled, skipping")
            return
        }

        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                LogManager.log(TAG, "setWatermark: eglMakeCurrent failed")
                return
            }

            // Create watermark texture
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            watermarkTextureId = textures[0]

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            // Calculate watermark position in NDC (bottom-right corner with margin)
            val marginPixels = 48 // margin from edge in video pixels
            val wmWidth = bitmap.width
            val wmHeight = bitmap.height

            // Convert pixel dimensions to NDC
            val marginNdcX = marginPixels.toFloat() / videoWidth.toFloat() * 2f
            val marginNdcY = marginPixels.toFloat() / videoHeight.toFloat() * 2f
            val wmWidthNdc = wmWidth.toFloat() / videoWidth.toFloat() * 2f
            val wmHeightNdc = wmHeight.toFloat() / videoHeight.toFloat() * 2f

            // Bottom-right corner: right edge at 1 - margin, bottom edge at -1 + margin
            val right = 1f - marginNdcX
            val left = right - wmWidthNdc
            val bottom = -1f + marginNdcY
            val top = bottom + wmHeightNdc

            LogManager.log(TAG, "setWatermark: bitmap=${wmWidth}x${wmHeight} video=${videoWidth}x${videoHeight} ndc=[$left,$bottom,$right,$top]")

            // Watermark vertex positions (NDC)
            val wmVertices = floatArrayOf(
                left, bottom,
                right, bottom,
                left, top,
                right, top
            )
            watermarkVertexBuffer = java.nio.ByteBuffer.allocateDirect(wmVertices.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(wmVertices)
                .apply { position(0) }

            // Watermark texture coordinates (standard 0-1 mapping)
            val wmTexCoords = floatArrayOf(
                0f, 1f,  // bottom-left
                1f, 1f,  // bottom-right
                0f, 0f,  // top-left
                1f, 0f   // top-right
            )
            watermarkTexCoordBuffer = java.nio.ByteBuffer.allocateDirect(wmTexCoords.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(wmTexCoords)
                .apply { position(0) }

            hasWatermark = true
            LogManager.log(TAG, "setWatermark: watermark set successfully")
        } catch (e: Exception) {
            LogManager.log(TAG, "setWatermark: failed", e)
            hasWatermark = false
        }
    }

    /**
     * Draw a cropped frame (with optional watermark) to the encoder surface.
     * Safe to call from the encode loop; actual GL work stays on the GL thread.
     */
    fun drawFrame(): Boolean {
        val handler = glHandler ?: return false
        handler.post { drawFrameInternal(force = false) }
        return true
    }

    private fun drawFrameInternal(force: Boolean): Boolean {
        if (released) return false
        synchronized(lock) {
            if (!force && !frameAvailable) return false
            frameAvailable = false
        }

        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                LogManager.log(TAG, "drawFrame: eglMakeCurrent failed err=${EGL14.eglGetError()}")
                return false
            }

            if (outputWidth > 0 && outputHeight > 0) {
                GLES20.glViewport(0, 0, outputWidth, outputHeight)
            }

            surfaceTexture?.updateTexImage()

            val texMatrix = FloatArray(16)
            android.opengl.Matrix.setIdentityM(texMatrix, 0)
            surfaceTexture?.getTransformMatrix(texMatrix)

            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            GLES20.glUseProgram(program)
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)

            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

            cropTexCoordBuffer?.position(0)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, cropTexCoordBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(0x8D65, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)

            // Draw watermark overlay
            if (hasWatermark) {
                renderWatermark()
            }

            // Set presentation time and swap
            val timestampNs = surfaceTexture?.timestamp ?: 0L
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            return true
        } catch (e: Exception) {
            LogManager.log(TAG, "drawFrame: error", e)
            return false
        }
    }

    private fun renderWatermark() {
        if (watermarkProgram == 0 || watermarkTextureId == 0) return

        // Enable alpha blending for watermark transparency
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(watermarkProgram)

        // Set vertex positions
        GLES20.glEnableVertexAttribArray(watermarkPositionHandle)
        GLES20.glVertexAttribPointer(watermarkPositionHandle, 2, GLES20.GL_FLOAT, false, 8, watermarkVertexBuffer)

        // Set texture coordinates
        GLES20.glEnableVertexAttribArray(watermarkTexCoordHandle)
        GLES20.glVertexAttribPointer(watermarkTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, watermarkTexCoordBuffer)

        // Bind watermark texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(watermarkProgram, "sTexture"), 0)

        // Draw watermark quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(watermarkPositionHandle)
        GLES20.glDisableVertexAttribArray(watermarkTexCoordHandle)

        // Disable blending
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun setupEGL(encoderSurface: Surface): Boolean {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            LogManager.log(TAG, "setupEGL: eglGetDisplay failed")
            return false
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            LogManager.log(TAG, "setupEGL: eglInitialize failed")
            return false
        }

        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        val recordableAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val fallbackAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE
        )
        val choseRecordable = EGL14.eglChooseConfig(
            eglDisplay, recordableAttribs, 0, configs, 0, 1, numConfigs, 0
        ) && numConfigs[0] > 0 && configs[0] != null
        if (!choseRecordable) {
            LogManager.log(TAG, "setupEGL: recordable config unavailable, falling back")
            if (!EGL14.eglChooseConfig(eglDisplay, fallbackAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0 || configs[0] == null
            ) {
                LogManager.log(TAG, "setupEGL: eglChooseConfig failed")
                return false
            }
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            LogManager.log(TAG, "setupEGL: eglCreateContext failed")
            return false
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            LogManager.log(TAG, "setupEGL: eglCreateWindowSurface failed")
            return false
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            LogManager.log(TAG, "setupEGL: eglMakeCurrent failed")
            return false
        }

        LogManager.log(TAG, "setupEGL: success")
        return true
    }

    private fun setupGL(): Boolean {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        if (vertexShader == 0) {
            LogManager.log(TAG, "setupGL: vertex shader compile failed")
            return false
        }

        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (fragmentShader == 0) {
            LogManager.log(TAG, "setupGL: fragment shader compile failed")
            return false
        }

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            LogManager.log(TAG, "setupGL: program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            program = 0
            return false
        }

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")

        LogManager.log(TAG, "setupGL: success, program=$program pos=$positionHandle tex=$texCoordHandle mat=$texMatrixHandle")
        return true
    }

    private fun setupWatermarkGL(): Boolean {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, WATERMARK_VERTEX_SHADER)
        if (vertexShader == 0) {
            LogManager.log(TAG, "setupWatermarkGL: vertex shader compile failed")
            return false
        }

        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, WATERMARK_FRAGMENT_SHADER)
        if (fragmentShader == 0) {
            LogManager.log(TAG, "setupWatermarkGL: fragment shader compile failed")
            return false
        }

        watermarkProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(watermarkProgram, vertexShader)
        GLES20.glAttachShader(watermarkProgram, fragmentShader)
        GLES20.glLinkProgram(watermarkProgram)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(watermarkProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            LogManager.log(TAG, "setupWatermarkGL: program link failed: ${GLES20.glGetProgramInfoLog(watermarkProgram)}")
            GLES20.glDeleteProgram(watermarkProgram)
            watermarkProgram = 0
            return false
        }

        watermarkPositionHandle = GLES20.glGetAttribLocation(watermarkProgram, "aPosition")
        watermarkTexCoordHandle = GLES20.glGetAttribLocation(watermarkProgram, "aTexCoord")

        LogManager.log(TAG, "setupWatermarkGL: success, program=$watermarkProgram pos=$watermarkPositionHandle tex=$watermarkTexCoordHandle")
        return true
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            LogManager.log(TAG, "loadShader: compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun createSurfaceTexture(screenWidth: Int, screenHeight: Int): Boolean {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(0x8D65, textureId)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture?.setDefaultBufferSize(screenWidth, screenHeight)

        inputSurface = Surface(surfaceTexture)

        LogManager.log(TAG, "createSurfaceTexture: success, textureId=$textureId")
        return true
    }

    /**
     * Create a text watermark bitmap matching the floating watermark style.
     */
    fun createTextWatermarkBitmap(text: String): Bitmap {
        val textSize = 48f
        val padding = 24f
        val paint = Paint().apply {
            color = Color.WHITE
            alpha = 128 // 50% transparency
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val textBounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, textBounds)

        val width = (textBounds.width() + padding * 2).toInt()
        val height = (textBounds.height() + padding * 2).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Draw text at bottom-left of the bitmap (since text draws from baseline)
        canvas.drawText(text, padding, height - padding, paint)

        return bitmap
    }

    fun release() {
        LogManager.log(TAG, "release")
        released = true
        val handler = glHandler
        if (handler != null && glThread.isAlive) {
            val latch = CountDownLatch(1)
            handler.post {
                try {
                    releaseOnGlThread()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(2, TimeUnit.SECONDS)
            glThread.quitSafely()
        } else {
            releaseOnGlThread()
        }
        glHandler = null
        LogManager.log(TAG, "release: complete")
    }

    private fun releaseOnGlThread() {
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            }
        }

        if (textureId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
            textureId = 0
        }
        if (watermarkTextureId != 0) {
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(watermarkTextureId), 0) }
            watermarkTextureId = 0
        }
        if (program != 0) {
            runCatching { GLES20.glDeleteProgram(program) }
            program = 0
        }
        if (watermarkProgram != 0) {
            runCatching { GLES20.glDeleteProgram(watermarkProgram) }
            watermarkProgram = 0
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
            }
        }
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglTerminate(eglDisplay) }
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        cropTexCoordBuffer = null
        vertexBuffer = null
        watermarkVertexBuffer = null
        watermarkTexCoordBuffer = null
        hasWatermark = false
    }
}