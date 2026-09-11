cmake_minimum_required(VERSION 3.22.1)
project("screenpulse_native")

set(CMAKE_CXX_STANDARD 17)

add_library(screenpulse_native SHARED
    audio/AudioMixer.cpp
    audio/NoiseReduction.cpp
    video/FrameCropper.cpp
    video/VideoEncoder.cpp
    compress/Compressor.cpp
    ffmpeg-wrapper/FFmpegWrapper.cpp
    NativeBridge.cpp
)

target_include_directories(screenpulse_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/audio
    ${CMAKE_CURRENT_SOURCE_DIR}/video
    ${CMAKE_CURRENT_SOURCE_DIR}/compress
    ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg-wrapper
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(screenpulse_native
    ${log-lib}
    ${android-lib}
)
