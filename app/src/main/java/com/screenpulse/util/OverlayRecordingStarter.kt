package com.screenpulse.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.screenpulse.floatingwindow.FloatingPipService
import com.screenpulse.repository.RecordMode
import com.screenpulse.ui.ProjectionConsentActivity
import com.screenpulse.ui.regionselect.RegionSelectActivity

/**
 * Starts recording from overlay / shortcut using the latest saved settings.
 * If a live MediaProjection is already held, recording begins immediately.
 * Otherwise only the system capture consent dialog is shown.
 */
object OverlayRecordingStarter {

    fun start(context: Context) {
        RecordingCache.refreshConfigFromSettings(context)
        if (RecordingCache.config.recordMode == RecordMode.CUSTOM_REGION.value) {
            LogManager.log(LogManager.TAG_FLOAT, "OverlayRecordingStarter: select region first")
            val selectIntent = Intent(context, RegionSelectActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                putExtra(RegionSelectActivity.EXTRA_START_RECORDING, true)
            }
            context.startActivity(selectIntent)
            return
        }
        startCapture(context)
    }

    fun startCapture(context: Context) {
        if (MediaProjectionHolder.isActive) {
            LogManager.log(LogManager.TAG_FLOAT, "OverlayRecordingStarter: start with held MediaProjection")
            ContextCompat.startForegroundService(context, RecordingCache.createStartIntent(context))
            startPipIfEnabled(context)
            return
        }
        LogManager.log(LogManager.TAG_FLOAT, "OverlayRecordingStarter: request capture consent")
        val consentIntent = Intent(context, ProjectionConsentActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        context.startActivity(consentIntent)
    }

    fun startPipIfEnabled(context: Context) {
        if (!RecordingCache.config.pipEnabled) return
        context.startService(Intent(context, FloatingPipService::class.java).apply {
            action = FloatingPipService.ACTION_SHOW
            putExtra(FloatingPipService.EXTRA_SIZE, RecordingCache.config.pipSize)
        })
    }
}
