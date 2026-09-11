#include "Compressor.h"
#include <android/log.h>

#define LOG_TAG "Compressor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

bool Compressor::compress(const std::string& inputPath, const std::string& outputPath, int mode) {
    LOGI("Compress: %s -> %s, mode=%d", inputPath.c_str(), outputPath.c_str(), mode);
    return true;
}
