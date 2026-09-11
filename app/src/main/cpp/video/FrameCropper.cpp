#include "FrameCropper.h"
#include <cstring>

void FrameCropper::crop(const uint8_t* input, uint8_t* output,
                        int srcWidth, int srcHeight,
                        int cropX, int cropY, int cropWidth, int cropHeight,
                        int stride) {
    if (cropX < 0) cropX = 0;
    if (cropY < 0) cropY = 0;
    if (cropX + cropWidth > srcWidth) cropWidth = srcWidth - cropX;
    if (cropY + cropHeight > srcHeight) cropHeight = srcHeight - cropY;

    int bytesPerPixel = stride / srcWidth;
    int srcRowBytes = srcWidth * bytesPerPixel;
    int dstRowBytes = cropWidth * bytesPerPixel;

    const uint8_t* srcRow = input + cropY * srcRowBytes + cropX * bytesPerPixel;
    uint8_t* dstRow = output;

    for (int row = 0; row < cropHeight; row++) {
        memcpy(dstRow, srcRow, dstRowBytes);
        srcRow += srcRowBytes;
        dstRow += dstRowBytes;
    }
}
