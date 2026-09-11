#include "VideoEncoder.h"
#include <android/log.h>

#define LOG_TAG "VideoEncoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

bool VideoEncoder::init(int width, int height, int bitrate, int frameRate) {
    LOGI("VideoEncoder init: %dx%d, bitrate=%d, fps=%d", width, height, bitrate, frameRate);
    initialized = true;
    return true;
}

void VideoEncoder::release() {
    initialized = false;
}
