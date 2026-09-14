package com.screenpulse.navigation

import android.util.Base64

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Settings : Screen("settings")
    data object VideoList : Screen("video_list")
    data object VideoPreview : Screen("video_preview/{filePath}") {
        fun createRoute(filePath: String) = "video_preview/${encodePath(filePath)}"
    }
    data object VideoTrim : Screen("video_trim/{filePath}") {
        fun createRoute(filePath: String) = "video_trim/${encodePath(filePath)}"
    }
    data object Logs : Screen("logs")
}

fun encodePath(path: String): String {
    return Base64.encodeToString(
        path.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    )
}

fun decodePath(encoded: String): String {
    if (encoded.isEmpty()) return encoded
    return runCatching {
        String(
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8
        )
    }.getOrElse { encoded }
}
