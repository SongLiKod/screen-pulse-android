package com.screenpulse.util

import com.screenpulse.R
import com.screenpulse.repository.CustomRegion

data class NamedRegionPreset(
    val nameRes: Int,
    val region: CustomRegion
)

object RegionPresets {

    const val MIN_SIZE = 16

    fun namedPresets(screenWidth: Int, screenHeight: Int): List<NamedRegionPreset> {
        return listOf(
            NamedRegionPreset(R.string.region_preset_fullscreen, fullScreen(screenWidth, screenHeight)),
            NamedRegionPreset(R.string.region_preset_16_9, ratioCentered(screenWidth, screenHeight, 16, 9)),
            NamedRegionPreset(R.string.region_preset_9_16, ratioCentered(screenWidth, screenHeight, 9, 16)),
            NamedRegionPreset(R.string.region_preset_1_1, ratioCentered(screenWidth, screenHeight, 1, 1)),
            NamedRegionPreset(R.string.region_preset_4_3, ratioCentered(screenWidth, screenHeight, 4, 3))
        )
    }

    fun fullScreen(screenWidth: Int, screenHeight: Int): CustomRegion {
        return CustomRegion(
            width = screenWidth.coerceAtLeast(MIN_SIZE),
            height = screenHeight.coerceAtLeast(MIN_SIZE),
            offsetX = 0,
            offsetY = 0
        )
    }

    fun ratioCentered(
        screenWidth: Int,
        screenHeight: Int,
        ratioWidth: Int,
        ratioHeight: Int
    ): CustomRegion {
        val shortSide = minOf(screenWidth, screenHeight).coerceAtLeast(MIN_SIZE)
        val regionShort = (shortSide * 0.8f).toInt().coerceAtLeast(MIN_SIZE)
        var width: Int
        var height: Int
        if (ratioWidth >= ratioHeight) {
            height = regionShort
            width = (regionShort.toLong() * ratioWidth / ratioHeight).toInt()
        } else {
            width = regionShort
            height = (regionShort.toLong() * ratioHeight / ratioWidth).toInt()
        }
        if (width > screenWidth) {
            height = (height.toLong() * screenWidth / width).toInt()
            width = screenWidth
        }
        if (height > screenHeight) {
            width = (width.toLong() * screenHeight / height).toInt()
            height = screenHeight
        }
        width = width.coerceAtLeast(MIN_SIZE)
        height = height.coerceAtLeast(MIN_SIZE)
        return CustomRegion(
            width = width,
            height = height,
            offsetX = ((screenWidth - width) / 2).coerceAtLeast(0),
            offsetY = ((screenHeight - height) / 2).coerceAtLeast(0)
        )
    }

    fun clampToScreen(region: CustomRegion, screenWidth: Int, screenHeight: Int): CustomRegion {
        if (screenWidth <= 0 || screenHeight <= 0) return region
        val width = region.width.coerceIn(MIN_SIZE, screenWidth)
        val height = region.height.coerceIn(MIN_SIZE, screenHeight)
        val offsetX = region.offsetX.coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        val offsetY = region.offsetY.coerceIn(0, (screenHeight - height).coerceAtLeast(0))
        return CustomRegion(width, height, offsetX, offsetY)
    }
}
