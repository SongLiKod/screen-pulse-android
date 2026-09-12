package com.screenpulse.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.screenpulse.permission.PermissionManager
import com.screenpulse.util.LogManager
import com.screenpulse.util.OverlayRecordingStarter
import com.screenpulse.util.RecordingCache

/**
 * Invisible activity that only shows the system MediaProjection consent dialog.
 * Used by the floating window so recording can start without opening the app UI.
 */
class ProjectionConsentActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            LogManager.log(LogManager.TAG_UI, "ProjectionConsent: granted, starting recording")
            val startIntent = RecordingCache.createStartIntent(
                this,
                result.resultCode,
                result.data
            )
            ContextCompat.startForegroundService(this, startIntent)
            OverlayRecordingStarter.startPipIfEnabled(this)
        } else {
            LogManager.log(LogManager.TAG_UI, "ProjectionConsent: denied or cancelled")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        LogManager.log(LogManager.TAG_UI, "ProjectionConsent: requesting MediaProjection")
        projectionLauncher.launch(PermissionManager.createMediaProjectionIntent(this))
    }
}
