#include <jni.h>
#include <android/log.h>

#include "audio/AudioMixer.h"
#include "audio/NoiseReduction.h"
#include "video/FrameCropper.h"

#define LOG_TAG "ScreenPulseNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static AudioMixer* g_audioMixer = nullptr;
static NoiseReduction* g_noiseReduction = nullptr;
static FrameCropper* g_frameCropper = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_com_screenpulse_jni_NativeBridge_nativeInit(JNIEnv *env, jobject thiz) {
    g_audioMixer = new AudioMixer();
    g_noiseReduction = new NoiseReduction();
    g_frameCropper = new FrameCropper();
    LOGI("NativeBridge initialized");
}

JNIEXPORT void JNICALL
Java_com_screenpulse_jni_NativeBridge_nativeRelease(JNIEnv *env, jobject thiz) {
    delete g_audioMixer;
    g_audioMixer = nullptr;
    delete g_noiseReduction;
    g_noiseReduction = nullptr;
    delete g_frameCropper;
    g_frameCropper = nullptr;
    LOGI("NativeBridge released");
}

JNIEXPORT void JNICALL
Java_com_screenpulse_jni_NativeBridge_nativeMixAudio(
    JNIEnv *env, jobject thiz,
    jbyteArray pcmSystem, jbyteArray pcmMic, jbyteArray output,
    jfloat systemVolume, jfloat micVolume, jint sampleCount) {

    if (!g_audioMixer) return;

    jbyte* sysBuf = env->GetByteArrayElements(pcmSystem, nullptr);
    jbyte* micBuf = env->GetByteArrayElements(pcmMic, nullptr);
    jbyte* outBuf = env->GetByteArrayElements(output, nullptr);

    g_audioMixer->mix(
        reinterpret_cast<int16_t*>(sysBuf),
        reinterpret_cast<int16_t*>(micBuf),
        reinterpret_cast<int16_t*>(outBuf),
        systemVolume, micVolume, sampleCount
    );

    env->ReleaseByteArrayElements(pcmSystem, sysBuf, JNI_ABORT);
    env->ReleaseByteArrayElements(pcmMic, micBuf, JNI_ABORT);
    env->ReleaseByteArrayElements(output, outBuf, 0);
}

JNIEXPORT void JNICALL
Java_com_screenpulse_jni_NativeBridge_nativeCropFrame(
    JNIEnv *env, jobject thiz,
    jbyteArray inputBuffer, jbyteArray outputBuffer,
    jint srcWidth, jint srcHeight,
    jint cropX, jint cropY, jint cropWidth, jint cropHeight,
    jint stride) {

    if (!g_frameCropper) return;

    jbyte* inBuf = env->GetByteArrayElements(inputBuffer, nullptr);
    jbyte* outBuf = env->GetByteArrayElements(outputBuffer, nullptr);

    g_frameCropper->crop(
        reinterpret_cast<uint8_t*>(inBuf),
        reinterpret_cast<uint8_t*>(outBuf),
        srcWidth, srcHeight,
        cropX, cropY, cropWidth, cropHeight,
        stride
    );

    env->ReleaseByteArrayElements(inputBuffer, inBuf, JNI_ABORT);
    env->ReleaseByteArrayElements(outputBuffer, outBuf, 0);
}

JNIEXPORT void JNICALL
Java_com_screenpulse_jni_NativeBridge_nativeApplyNoiseReduction(
    JNIEnv *env, jobject thiz,
    jbyteArray pcmInput, jbyteArray pcmOutput, jint sampleCount) {

    if (!g_noiseReduction) return;

    jbyte* inBuf = env->GetByteArrayElements(pcmInput, nullptr);
    jbyte* outBuf = env->GetByteArrayElements(pcmOutput, nullptr);

    g_noiseReduction->process(
        reinterpret_cast<int16_t*>(inBuf),
        reinterpret_cast<int16_t*>(outBuf),
        sampleCount
    );

    env->ReleaseByteArrayElements(pcmInput, inBuf, JNI_ABORT);
    env->ReleaseByteArrayElements(pcmOutput, outBuf, 0);
}

}
