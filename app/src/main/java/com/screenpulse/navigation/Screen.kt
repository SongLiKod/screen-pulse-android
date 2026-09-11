package com.screenpulse.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Settings : Screen("settings")
    data object VideoList : Screen("video_list")
    data object VideoPreview : Screen("video_preview/{filePath}") {
        fun createRoute(filePath: String) = "video_preview/$filePath"
    }
    data object VideoTrim : Screen("video_trim/{filePath}") {
        fun createRoute(filePath: String) = "video_trim/$filePath"
    }
    data object Logs : Screen("logs")
}
