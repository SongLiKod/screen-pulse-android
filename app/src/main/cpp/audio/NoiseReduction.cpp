#include "NoiseReduction.h"
#include <cmath>
#include <algorithm>

NoiseReduction::NoiseReduction() = default;

void NoiseReduction::initialize() {
    noiseFloor = 0.0f;
    initialized = true;
}

void NoiseReduction::process(const int16_t* pcmInput, int16_t* pcmOutput, int sampleCount) {
    if (!initialized) {
        initialize();
    }

    float energy = 0.0f;
    for (int i = 0; i < sampleCount; i++) {
        float sample = static_cast<float>(pcmInput[i]);
        energy += sample * sample;
    }
    float rms = std::sqrt(energy / static_cast<float>(std::max(sampleCount, 1)));
    noiseFloor = 0.98f * noiseFloor + 0.02f * rms;

    float gate = noiseFloor * 1.8f;
    float gain = 1.0f;
    if (rms < gate && gate > 1.0f) {
        gain = std::max(0.15f, rms / gate);
    }

    for (int i = 0; i < sampleCount; i++) {
        float sample = static_cast<float>(pcmInput[i]) * gain;
        int32_t result = static_cast<int32_t>(sample);
        if (result > INT16_MAX) result = INT16_MAX;
        if (result < INT16_MIN) result = INT16_MIN;
        pcmOutput[i] = static_cast<int16_t>(result);
    }
}
