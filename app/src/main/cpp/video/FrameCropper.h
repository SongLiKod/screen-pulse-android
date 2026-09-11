#pragma once

#include <cstdint>

class FrameCropper {
public:
    FrameCropper() = default;
    ~FrameCropper() = default;

    void crop(const uint8_t* input, uint8_t* output,
              int srcWidth, int srcHeight,
              int cropX, int cropY, int cropWidth, int cropHeight,
              int stride);
};
