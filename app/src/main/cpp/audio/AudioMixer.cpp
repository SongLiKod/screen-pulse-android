#include "AudioMixer.h"
#include <algorithm>

void AudioMixer::mix(const int16_t* pcmSystem, const int16_t* pcmMic,
                     int16_t* output, float systemVolume, float micVolume,
                     int sampleCount) {
    float sysGain = std::clamp(systemVolume, 0.0f, 2.0f);
    float micGain = std::clamp(micVolume, 0.0f, 2.0f);

    for (int i = 0; i < sampleCount; i++) {
        int32_t sysSample = static_cast<int32_t>(pcmSystem[i] * sysGain);
        int32_t micSample = static_cast<int32_t>(pcmMic[i] * micGain);
        output[i] = clampSample(sysSample + micSample);
    }
}

int16_t AudioMixer::clampSample(int32_t sample) {
    if (sample > INT16_MAX) return INT16_MAX;
    if (sample < INT16_MIN) return INT16_MIN;
    return static_cast<int16_t>(sample);
}
