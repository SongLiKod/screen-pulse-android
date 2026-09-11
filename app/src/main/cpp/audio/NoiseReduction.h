#pragma once

#include <cstdint>
#include <vector>

class NoiseReduction {
public:
    NoiseReduction();
    ~NoiseReduction() = default;

    void process(const int16_t* pcmInput, int16_t* pcmOutput, int sampleCount);

private:
    static constexpr int WINDOW_SIZE = 512;
    static constexpr float SMOOTHING_FACTOR = 0.98f;
    std::vector<float> noiseEstimate;
    std::vector<float> prevOutput;
    bool initialized = false;

    void initialize(int sampleCount);
    float estimateNoise(float sample, int index);
};
