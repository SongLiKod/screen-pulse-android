package com.screenpulse.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Settings : Screen("settings")
    data object VideoList : Screen("video_list")
    data object VideoPreview : Screen("video_preview/{filePath}") {
        fun createRoute(filePath: String) = "video_preview/${Uri.encode(filePath)}"
    }
    data object VideoTrim : Screen("video_trim/{filePath}") {
        fun createRoute(filePath: String) = "video_trim/${Uri.encode(filePath)}"
    }
    data object Logs : Screen("logs")
}
