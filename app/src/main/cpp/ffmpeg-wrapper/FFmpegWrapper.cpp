#include "FFmpegWrapper.h"
#include <android/log.h>

#define LOG_TAG "FFmpegWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

bool FFmpegWrapper::init() {
    LOGI("FFmpegWrapper initialized");
    return true;
}

void FFmpegWrapper::release() {
    LOGI("FFmpegWrapper released");
}

bool FFmpegWrapper::transcode(const std::string& inputPath, const std::string& outputPath,
                               int targetBitrate, int targetFrameRate) {
    LOGI("Transcode: %s -> %s, bitrate=%d, fps=%d",
         inputPath.c_str(), outputPath.c_str(), targetBitrate, targetFrameRate);
    return true;
}

bool FFmpegWrapper::muxAudioVideo(const std::string& videoPath, const std::string& audioPath,
                                   const std::string& outputPath) {
    LOGI("Mux: %s + %s -> %s", videoPath.c_str(), audioPath.c_str(), outputPath.c_str());
    return true;
}
