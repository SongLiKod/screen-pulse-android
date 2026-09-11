#include "AudioMixer.h"
#include <algorithm>
#include <cstring>

void AudioMixer::mix(const int16_t* pcmSystem, const int16_t* pcmMic,
                     int16_t* output, float systemVolume, float micVolume,
                     int sampleCount) {
    float sysGain = systemVolume / 100.0f;
    float micGain = micVolume / 100.0f;

    for (int i = 0; i < sampleCount; i++) {
        int32_t sysSample = static_cast<int32_t>(pcmSystem[i] * sysGain);
        int32_t micSample = static_cast<int32_t>(pcmMic[i] * micGain);
        int32_t mixed = sysSample + micSample;
        output[i] = clampSample(mixed);
    }
}

int16_t AudioMixer::clampSample(int32_t sample) {
    if (sample > INT16_MAX) return INT16_MAX;
    if (sample < INT16_MIN) return INT16_MIN;
    return static_cast<int16_t>(sample);
}
