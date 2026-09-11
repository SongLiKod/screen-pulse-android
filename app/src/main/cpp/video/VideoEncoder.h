#pragma once

#include <cstdint>

class VideoEncoder {
public:
    VideoEncoder() = default;
    ~VideoEncoder() = default;

    bool init(int width, int height, int bitrate, int frameRate);
    void release();

private:
    bool initialized = false;
};
