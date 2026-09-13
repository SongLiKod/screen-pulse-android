package com.screenpulse.compress

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.screenpulse.repository.CompressionMode

object CompressTracker {

    const val TAG = "screenpulse_compress"

    fun uniqueName(inputPath: String): String = "compress_${inputPath.hashCode()}"

    fun enqueue(
        context: Context,
        inputPath: String,
        compressionMode: Int = CompressionMode.BALANCED.value,
        replace: Boolean = false
    ) {
        val outputPath = inputPath.replace(".mp4", "_compressed.mp4")
        val request = OneTimeWorkRequestBuilder<VideoCompressWorker>()
            .setInputData(
                workDataOf(
                    VideoCompressWorker.KEY_INPUT_PATH to inputPath,
                    VideoCompressWorker.KEY_OUTPUT_PATH to outputPath,
                    VideoCompressWorker.KEY_COMPRESSION_MODE to compressionMode
                )
            )
            .addTag(TAG)
            .addTag(uniqueName(inputPath))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            uniqueName(inputPath),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun retry(context: Context, inputPath: String, compressionMode: Int = CompressionMode.BALANCED.value) {
        enqueue(context, inputPath, compressionMode, replace = true)
    }

    fun stateFor(path: String, infos: List<WorkInfo>): CompressUiState? {
        val name = uniqueName(path)
        val info = infos
            .filter { it.tags.contains(name) }
            .maxByOrNull { it.id } ?: return null
        return when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                CompressUiState(progress = 0, running = true, failed = false, error = null)
            WorkInfo.State.RUNNING ->
                CompressUiState(
                    progress = info.progress.getInt(VideoCompressWorker.KEY_PROGRESS, 0),
                    running = true,
                    failed = false,
                    error = null
                )
            WorkInfo.State.FAILED ->
                CompressUiState(
                    progress = 0,
                    running = false,
                    failed = true,
                    error = info.outputData.getString(VideoCompressWorker.KEY_ERROR)
                )
            else -> null
        }
    }
}

data class CompressUiState(
    val progress: Int,
    val running: Boolean,
    val failed: Boolean,
    val error: String?
)
