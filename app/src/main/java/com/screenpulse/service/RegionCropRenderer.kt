package com.screenpulse.service

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES20
import android.view.Surface
import com.screenpulse.util.LogManager

/**
 * Uses OpenGL ES to crop a region from a full-screen VirtualDisplay capture
 * and render the cropped region to the encoder's input Surface.
 *
 * Flow:
 * 1. VirtualDisplay captures full screen → writes to [inputSurface] (SurfaceTexture)
 * 2. On each frame, [drawFrame] reads the texture, crops the region, renders to encoder surface
 * 3. MediaCodec reads the cropped frame from its input surface
 */
class RegionCropRenderer {

    companion object {
        private const val TAG = "${LogManager.TAG_RECORD}:RegionCrop"

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
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
    }

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0

    // Texture coordinate buffer for the crop region
    private var cropTexCoordBuffer: java.nio.FloatBuffer? = null

    // Vertex buffer for full-screen quad
    private var vertexBuffer: java.nio.FloatBuffer? = null

    private var frameAvailable = false
    private val lock = Object()

    /**
     * Initialize EGL context, compile shaders, create SurfaceTexture.
     *
     * @param encoderSurface The MediaCodec input surface to render cropped frames to
     * @param screenWidth Full screen width
     * @param screenHeight Full screen height
     * @param cropX Crop region X offset
     * @param cropY Crop region Y offset
     * @param cropWidth Crop region width
     * @param cropHeight Crop region height
     * @return true if initialization succeeded
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

        // 1. Setup EGL with encoder surface
        if (!setupEGL(encoderSurface)) {
            LogManager.log(TAG, "init: EGL setup failed")
            release()
            return false
        }

        // 2. Compile shaders
        if (!setupGL()) {
            LogManager.log(TAG, "init: GL setup failed")
            release()
            return false
        }

        // 3. Create OES texture + SurfaceTexture
        if (!createSurfaceTexture(screenWidth, screenHeight)) {
            LogManager.log(TAG, "init: SurfaceTexture creation failed")
            release()
            return false
        }

        // 4. Calculate crop texture coordinates
        // SurfaceTexture OES texture has (0,0) at bottom-left, but screen has (0,0) at top-left
        // The SurfaceTexture transform matrix handles the Y-flip, so we use normalized
        // coordinates based on the screen dimensions.
        // After getTransformMatrix is applied, tex coords are in [0,1] with (0,0) at top-left.
        val left = cropX.toFloat() / screenWidth.toFloat()
        val right = (cropX + cropWidth).toFloat() / screenWidth.toFloat()
        val top = cropY.toFloat() / screenHeight.toFloat()
        val bottom = (cropY + cropHeight).toFloat() / screenHeight.toFloat()

        LogManager.log(TAG, "init: crop tex coords: left=$left top=$top right=$right bottom=$bottom")

        // Texture coordinates for the crop region (will be used with transform matrix)
        val texCoords = floatArrayOf(
            left, bottom,   // bottom-left
            right, bottom,  // bottom-right
            left, top,      // top-left
            right, top      // top-right
        )
        cropTexCoordBuffer = java.nio.ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(texCoords)
            .apply { position(0) }

        // Vertex positions for full-screen quad (NDC)
        val vertices = floatArrayOf(
            -1f, -1f,   // bottom-left
             1f, -1f,   // bottom-right
            -1f,  1f,   // top-left
             1f,  1f    // top-right
        )
        vertexBuffer = java.nio.ByteBuffer.allocateDirect(vertices.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
            .apply { position(0) }

        // 5. Set frame available listener
        surfaceTexture?.setOnFrameAvailableListener({ st ->
            synchronized(lock) { frameAvailable = true }
        })

        LogManager.log(TAG, "init: complete")
        return true
    }

    /**
     * Get the input Surface for the VirtualDisplay to write to.
     * The VirtualDisplay should be created at FULL SCREEN resolution with this surface.
     */
    fun getInputSurface(): Surface? = inputSurface

    /**
     * Draw a cropped frame to the encoder surface.
     * Should be called when a new frame is available from the VirtualDisplay.
     *
     * @return true if a frame was drawn, false if no frame was available
     */
    fun drawFrame(): Boolean {
        synchronized(lock) {
            if (!frameAvailable) return false
            frameAvailable = false
        }

        try {
            // Update the GL texture with the new frame from SurfaceTexture
            surfaceTexture?.updateTexImage()

            // Make the encoder EGL surface current
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                LogManager.log(TAG, "drawFrame: eglMakeCurrent failed")
                return false
            }

            // Get the transform matrix from SurfaceTexture
            val texMatrix = FloatArray(16)
            surfaceTexture?.getTransformMatrix(texMatrix)

            // Apply the transform matrix to crop texture coordinates
            // The transform matrix maps raw texture coords to actual texture coords
            // We need to transform our crop coords through this matrix
            val transformedCoords = transformCropCoords(texMatrix)

            // Clear
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Use program
            GLES20.glUseProgram(program)

            // Set vertex positions
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

            // Set transformed texture coordinates
            val transformedBuffer = java.nio.ByteBuffer.allocateDirect(transformedCoords.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(transformedCoords)
                .apply { position(0) }

            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, transformedBuffer)

            // Bind the OES texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(0x8D65, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTexture"), 0)

            // Draw triangle strip (4 vertices = 2 triangles)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // Disable vertex attributes
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)

            // Set presentation time from SurfaceTexture
            val timestampNs = surfaceTexture?.timestamp ?: 0L
            EGL14.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)

            // Swap buffers to push the frame to the encoder
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)

            return true
        } catch (e: Exception) {
            LogManager.log(TAG, "drawFrame: error", e)
            return false
        }
    }

    /**
     * Transform crop texture coordinates through the SurfaceTexture transform matrix.
     * The transform matrix maps from [0,1] texture space to the actual texture coordinates
     * accounting for any transformations (rotation, scaling, etc.) applied by the system.
     */
    private fun transformCropCoords(texMatrix: FloatArray): FloatArray {
        val left = cropTexCoordBuffer?.get(0) ?: 0f
        val bottom = cropTexCoordBuffer?.get(1) ?: 0f
        val right = cropTexCoordBuffer?.get(2) ?: 0f
        val top = cropTexCoordBuffer?.get(5) ?: 0f

        // Transform each corner through the matrix
        // texMatrix is a 4x4 column-major matrix
        // For a 2D point (x, y), the transformed point is:
        // x' = m[0]*x + m[4]*y + m[12]
        // y' = m[1]*x + m[5]*y + m[13]
        val m = texMatrix
        return floatArrayOf(
            transformPoint(left, bottom, m),   // bottom-left x, y
            transformPointY(left, bottom, m),
            transformPoint(right, bottom, m),  // bottom-right x, y
            transformPointY(right, bottom, m),
            transformPoint(left, top, m),      // top-left x, y
            transformPointY(left, top, m),
            transformPoint(right, top, m),     // top-right x, y
            transformPointY(right, top, m)
        )
    }

    private fun transformPoint(x: Float, y: Float, m: FloatArray): Float {
        return m[0] * x + m[4] * y + m[12]
    }

    private fun transformPointY(x: Float, y: Float, m: FloatArray): Float {
        return m[1] * x + m[5] * y + m[13]
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

        // Configure EGL for recording (EGL_RECORDABLE_ANDROID)
        val attribList = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            LogManager.log(TAG, "setupEGL: eglChooseConfig failed")
            return false
        }

        // Create EGL context with OpenGL ES 2.0
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            LogManager.log(TAG, "setupEGL: eglCreateContext failed")
            return false
        }

        // Create EGL surface for the encoder surface
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            LogManager.log(TAG, "setupEGL: eglCreateWindowSurface failed")
            return false
        }

        // Make current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            LogManager.log(TAG, "setupEGL: eglMakeCurrent failed")
            return false
        }

        LogManager.log(TAG, "setupEGL: success")
        return true
    }

    private fun setupGL(): Boolean {
        // Compile vertex shader
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        if (vertexShader == 0) {
            LogManager.log(TAG, "setupGL: vertex shader compile failed")
            return false
        }

        // Compile fragment shader
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (fragmentShader == 0) {
            LogManager.log(TAG, "setupGL: fragment shader compile failed")
            return false
        }

        // Link program
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

        // Get attribute locations
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        LogManager.log(TAG, "setupGL: success, program=$program pos=$positionHandle tex=$texCoordHandle")
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
        // Create OES texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]

        GLES20.glBindTexture(0x8D65, textureId)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Create SurfaceTexture from the OES texture
        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture?.setDefaultBufferSize(screenWidth, screenHeight)

        // Create a Surface from the SurfaceTexture for the VirtualDisplay
        inputSurface = Surface(surfaceTexture)

        LogManager.log(TAG, "createSurfaceTexture: success, textureId=$textureId")
        return true
    }

    /**
     * Release all EGL/GL resources.
     */
    fun release() {
        LogManager.log(TAG, "release")

        // Release input surface and SurfaceTexture first
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null

        // Delete GL texture
        if (textureId != 0) {
            runCatching {
                if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                }
            }
            textureId = 0
        }

        // Delete GL program
        if (program != 0) {
            runCatching {
                if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    GLES20.glDeleteProgram(program)
                }
            }
            program = 0
        }

        // Destroy EGL surface
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        // Destroy EGL context
        if (eglContext != EGL14.EGL_NO_CONTEXT) {
            runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            eglContext = EGL14.EGL_NO_CONTEXT
        }

        // Terminate EGL display
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglTerminate(eglDisplay) }
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }

        cropTexCoordBuffer = null
        vertexBuffer = null

        LogManager.log(TAG, "release: complete")
    }
}

