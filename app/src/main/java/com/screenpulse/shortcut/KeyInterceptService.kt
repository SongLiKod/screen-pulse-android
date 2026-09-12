package com.screenpulse.shortcut

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.screenpulse.service.ScreenRecordService
import com.screenpulse.util.OverlayRecordingStarter
import com.screenpulse.viewmodel.RecordingState

class KeyInterceptService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val configuredKey = RecordingStateManager.configuredKeyCode
        if (configuredKey == 0 || event.keyCode != configuredKey) {
            return super.onKeyEvent(event)
        }

        if (event.action != KeyEvent.ACTION_DOWN) {
            return true
        }

        val currentState = RecordingStateManager.currentState
        when (currentState) {
            RecordingState.IDLE -> OverlayRecordingStarter.start(this)
            RecordingState.RECORDING -> startService(
                Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_PAUSE
                }
            )
            RecordingState.PAUSED -> startService(
                Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_RESUME
                }
            )
            RecordingState.COUNTDOWN -> return true
        }
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
}
