#pragma once

#include <cstdint>
#include <vector>

class NoiseReduction {
public:
    NoiseReduction();
    ~NoiseReduction() = default;

    void process(const int16_t* pcmInput, int16_t* pcmOutput, int sampleCount);

private:
    float noiseFloor = 0.0f;
    bool initialized = false;

    void initialize();
};
