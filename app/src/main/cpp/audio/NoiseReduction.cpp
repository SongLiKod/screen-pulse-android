#include "NoiseReduction.h"
#include <cmath>
#include <algorithm>

NoiseReduction::NoiseReduction() = default;

void NoiseReduction::initialize(int sampleCount) {
    noiseEstimate.resize(sampleCount, 0.0f);
    prevOutput.resize(sampleCount, 0.0f);
    initialized = true;
}

void NoiseReduction::process(const int16_t* pcmInput, int16_t* pcmOutput, int sampleCount) {
    if (!initialized) {
        initialize(sampleCount);
    }

    for (int i = 0; i < sampleCount; i++) {
        float input = static_cast<float>(pcmInput[i]);
        float noise = estimateNoise(input, i);
        float signal = input - noise;

        if (signal < 0) signal = 0;

        float smoothed = SMOOTHING_FACTOR * prevOutput[i] + (1.0f - SMOOTHING_FACTOR) * signal;
        prevOutput[i] = smoothed;

        int32_t result = static_cast<int32_t>(smoothed);
        if (result > INT16_MAX) result = INT16_MAX;
        if (result < INT16_MIN) result = INT16_MIN;
        pcmOutput[i] = static_cast<int16_t>(result);
    }
}

float NoiseReduction::estimateNoise(float sample, int index) {
    float absSample = std::abs(sample);
    noiseEstimate[index] = 0.95f * noiseEstimate[index] + 0.05f * absSample;
    return noiseEstimate[index] * 0.5f;
}
