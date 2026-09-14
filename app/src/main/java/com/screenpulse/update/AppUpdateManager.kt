package com.screenpulse.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.screenpulse.BuildConfig
import com.screenpulse.util.LogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

data class UpdateInfo(
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val apkSize: Long
)

sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    data object AlreadyLatest : UpdateCheckResult()
    data class Error(val reason: String) : UpdateCheckResult()
}

sealed class InstallLaunchResult {
    data object Started : InstallLaunchResult()
    data object NeedPermission : InstallLaunchResult()
    data object Failed : InstallLaunchResult()
}

class AppUpdateManager(private val context: Context) {

    fun apkFile(): File = File(context.cacheDir, APK_NAME)

    suspend fun checkUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val json = httpGet(RELEASES_URL)
            if (json.isBlank()) {
                return@withContext UpdateCheckResult.AlreadyLatest
            }
            val obj = JSONObject(json)
            val tag = obj.optString("tag_name").ifBlank { obj.optString("name") }
            val latestName = tag.trim().trimStart('v', 'V')
            if (latestName.isBlank()) {
                return@withContext UpdateCheckResult.Error("empty")
            }
            if (compareVersions(latestName, currentVersionName()) <= 0) {
                return@withContext UpdateCheckResult.AlreadyLatest
            }
            val assets = obj.optJSONArray("assets") ?: return@withContext UpdateCheckResult.Error("no_apk")
            val apk = pickApk(assets) ?: return@withContext UpdateCheckResult.Error("no_apk")
            val url = apk.optString("browser_download_url")
            if (url.isBlank()) {
                return@withContext UpdateCheckResult.Error("no_apk")
            }
            UpdateCheckResult.Available(
                UpdateInfo(
                    versionName = latestName,
                    apkUrl = url,
                    changelog = obj.optString("body").trim(),
                    apkSize = apk.optLong("size", 0L)
                )
            )
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_MAIN, "check update failed: ${e.message}")
            UpdateCheckResult.Error("network")
        }
    }

    suspend fun downloadApk(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val tmp = File(dest.parentFile, "${dest.name}.part")
        if (tmp.exists()) tmp.delete()
        try {
            val conn = openDownload(url)
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext Result.failure(IllegalStateException("http $code"))
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                if (total <= 0) {
                    withContext(Dispatchers.Main) { onProgress(-1) }
                }
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var downloaded = 0L
                        var lastPercent = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            coroutineContext.ensureActive()
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    withContext(Dispatchers.Main) { onProgress(percent) }
                                }
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            withContext(Dispatchers.Main) { onProgress(100) }
            Result.success(dest)
        } catch (e: CancellationException) {
            tmp.delete()
            dest.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            dest.delete()
            LogManager.log(LogManager.TAG_MAIN, "download update failed: ${e.message}")
            Result.failure(e)
        }
    }

    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installPermissionIntent(): Intent {
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun installApk(file: File): InstallLaunchResult {
        if (!file.exists() || file.length() <= 0L) return InstallLaunchResult.Failed
        if (!canInstallPackages()) return InstallLaunchResult.NeedPermission
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallLaunchResult.Started
        } catch (e: Exception) {
            LogManager.log(LogManager.TAG_MAIN, "install update failed: ${e.message}")
            InstallLaunchResult.Failed
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun currentVersionName(): String {
        return runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { BuildConfig.VERSION_NAME }
    }

    private fun pickApk(assets: org.json.JSONArray): JSONObject? {
        val apks = buildList {
            for (i in 0 until assets.length()) {
                val item = assets.optJSONObject(i) ?: continue
                if (item.optString("name").endsWith(".apk", ignoreCase = true)) {
                    add(item)
                }
            }
        }
        return apks.firstOrNull { !it.optString("name").contains("debug", ignoreCase = true) }
            ?: apks.firstOrNull()
    }

    private fun compareVersions(remote: String, local: String): Int {
        val a = parseVersion(remote)
        val b = parseVersion(local)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private fun parseVersion(value: String): List<Int> {
        return value.trim().trimStart('v', 'V')
            .split('.', '-', '_')
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }

    private fun httpGet(url: String): String {
        val conn = openConnection(url)
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connect()
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code == HttpURLConnection.HTTP_NOT_FOUND) {
            return ""
        }
        if (code !in 200..299) {
            throw IllegalStateException("http $code")
        }
        return body
    }

    private fun openDownload(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val conn = openConnection(current)
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val next = conn.getHeaderField("Location")
                conn.disconnect()
                if (next.isNullOrBlank()) {
                    throw IllegalStateException("http $code")
                }
                current = if (next.startsWith("http")) next else URL(URL(current), next).toString()
            } else {
                return conn
            }
        }
        throw IllegalStateException("too many redirects")
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ScreenPulse/${BuildConfig.VERSION_NAME}")
        return conn
    }

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/SongLiKod/screen-pulse-android/releases/latest"
        private const val APK_NAME = "screenpulse-update.apk"
    }
}
