#pragma once

#include <cstdint>

class AudioMixer {
public:
    AudioMixer() = default;
    ~AudioMixer() = default;

    void mix(const int16_t* pcmSystem, const int16_t* pcmMic,
             int16_t* output, float systemVolume, float micVolume,
             int sampleCount);

private:
    static int16_t clampSample(int32_t sample);
};
