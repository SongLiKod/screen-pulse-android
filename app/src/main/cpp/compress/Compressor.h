#pragma once

#include <string>

class Compressor {
public:
    Compressor() = default;
    ~Compressor() = default;

    bool compress(const std::string& inputPath, const std::string& outputPath, int mode);
};
