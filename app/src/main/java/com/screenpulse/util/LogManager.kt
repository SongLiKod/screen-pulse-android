package com.screenpulse.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LogEntry(
    val timestamp: Long,
    val tag: String,
    val message: String
) {
    fun format(): String {
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
        return "[$time] [$tag] $message"
    }
}

object LogManager {

    companion object {
        const val TAG_RECORD = "ScreenRecord"
        const val TAG_FLOAT = "FloatingWindow"
        const val TAG_PIP = "PipService"
        const val TAG_ANNOTATION = "Annotation"
        const val TAG_WATERMARK = "Watermark"
        const val TAG_COMPRESS = "Compress"
        const val TAG_UI = "ScreenPulse-UI"
        const val TAG_MAIN = "ScreenPulse"
    }

    private const val MAX_LOGS = 800

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _isLogScrolling = MutableStateFlow(false)
    val isLogScrolling: StateFlow<Boolean> = _isLogScrolling.asStateFlow()

    fun log(tag: String, message: String) {
        Log.d(tag, message)
        _logs.update { (it + LogEntry(System.currentTimeMillis(), tag, message)).takeLast(MAX_LOGS) }
    }

    fun log(tag: String, message: String, e: Throwable) {
        Log.e(tag, message, e)
        _logs.update {
            (it + LogEntry(System.currentTimeMillis(), tag, "$message\n${e.stackTraceToString()}"))
                .takeLast(MAX_LOGS)
        }
    }

    fun setLogScrolling(scrolling: Boolean) {
        _isLogScrolling.value = scrolling
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun exportText(): String {
        return buildString {
            appendLine("===== ScreenPulse Logs (${_logs.value.size}) =====")
            _logs.value.forEach { appendLine(it.format()) }
        }
    }
}