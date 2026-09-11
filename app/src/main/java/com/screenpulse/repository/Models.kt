package com.screenpulse.repository

enum class ThemeMode(val value: Int) {
    FOLLOW_SYSTEM(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromValue(value: Int): ThemeMode = entries.firstOrNull { it.value == value } ?: FOLLOW_SYSTEM
    }
}

enum class AudioMode(val value: Int) {
    SYSTEM_ONLY(0),
    MIC_ONLY(1),
    MIXED(2);

    companion object {
        fun fromValue(value: Int): AudioMode = entries.firstOrNull { it.value == value } ?: MIC_ONLY
    }
}

enum class Resolution(val value: String, val width: Int, val height: Int) {
    R240P("240P", 426, 240),
    R480P("480P", 854, 480),
    R720P("720P", 1280, 720),
    R1080P("1080P", 1920, 1080),
    R2K("2K", 2560, 1440),
    R4K("4K", 3840, 2160),
    CUSTOM("Custom", 0, 0);

    companion object {
        fun fromValue(value: String): Resolution = entries.firstOrNull { it.value == value } ?: R1080P
    }
}

enum class FrameRate(val value: Int) {
    FPS_30(30),
    FPS_60(60);

    companion object {
        fun fromValue(value: Int): FrameRate = entries.firstOrNull { it.value == value } ?: FPS_30
    }
}

enum class CompressionMode(val value: Int) {
    FAST(0),
    BALANCED(1),
    HD_LOSSLESS(2);

    companion object {
        fun fromValue(value: Int): CompressionMode = entries.firstOrNull { it.value == value } ?: BALANCED
    }
}

enum class RecordMode(val value: Int) {
    FULL_SCREEN(0),
    CUSTOM_REGION(1);

    companion object {
        fun fromValue(value: Int): RecordMode = entries.firstOrNull { it.value == value } ?: FULL_SCREEN
    }
}

enum class CountdownMode(val value: Int) {
    NONE(0),
    THREE_SECONDS(3),
    FIVE_SECONDS(5);

    companion object {
        fun fromValue(value: Int): CountdownMode = entries.firstOrNull { it.value == value } ?: NONE
    }
}

enum class WatermarkType(val value: Int) {
    TEXT(0),
    IMAGE(1);

    companion object {
        fun fromValue(value: Int): WatermarkType = entries.firstOrNull { it.value == value } ?: TEXT
    }
}

enum class BitrateMode(val value: Int) {
    SMART(0),
    MANUAL(1);

    companion object {
        fun fromValue(value: Int): BitrateMode = entries.firstOrNull { it.value == value } ?: SMART

        fun calculateSmartBitrate(resolution: Resolution, frameRate: FrameRate): Int {
            val pixels = resolution.width.toLong() * resolution.height.toLong()
            val baseBitrate = when {
                pixels <= 426L * 240 -> 1_000_000L
                pixels <= 854L * 480 -> 2_000_000L
                pixels <= 1280L * 720 -> 4_000_000L
                pixels <= 1920L * 1080 -> 8_000_000L
                pixels <= 2560L * 1440 -> 16_000_000L
                else -> 32_000_000L
            }
            val fpsMultiplier = if (frameRate == FrameRate.FPS_60) 1.5 else 1.0
            return (baseBitrate * fpsMultiplier).toInt()
        }
    }
}
