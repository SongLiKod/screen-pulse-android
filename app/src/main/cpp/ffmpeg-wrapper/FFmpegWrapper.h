#pragma once

#include <string>

class FFmpegWrapper {
public:
    FFmpegWrapper() = default;
    ~FFmpegWrapper() = default;

    bool init();
    void release();

    bool transcode(const std::string& inputPath, const std::string& outputPath,
                   int targetBitrate, int targetFrameRate);

    bool muxAudioVideo(const std::string& videoPath, const std::string& audioPath,
                       const std::string& outputPath);
};
