package com.screenpulse.jni

object NativeBridge {

    external fun nativeInit()

    external fun nativeRelease()

    external fun nativeMixAudio(
        pcmSystem: ByteArray,
        pcmMic: ByteArray,
        output: ByteArray,
        systemVolume: Float,
        micVolume: Float,
        sampleCount: Int
    )

    external fun nativeCropFrame(
        inputBuffer: ByteArray,
        outputBuffer: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        cropX: Int,
        cropY: Int,
        cropWidth: Int,
        cropHeight: Int,
        stride: Int
    )

    external fun nativeApplyNoiseReduction(
        pcmInput: ByteArray,
        pcmOutput: ByteArray,
        sampleCount: Int
    )

    init {
        System.loadLibrary("screenpulse_native")
    }
}
