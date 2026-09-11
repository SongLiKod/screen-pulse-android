package com.screenpulse.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screenpulse.service.ScreenRecordService
import com.screenpulse.viewmodel.RecordingState

class ShortcutKeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.screenpulse.ACTION_TOGGLE_RECORDING") return

        val currentState = RecordingStateManager.currentState
        val serviceIntent = Intent(context, ScreenRecordService::class.java)
        serviceIntent.action = when (currentState) {
            RecordingState.IDLE -> ScreenRecordService.ACTION_START
            RecordingState.RECORDING -> ScreenRecordService.ACTION_PAUSE
            RecordingState.PAUSED -> ScreenRecordService.ACTION_RESUME
            RecordingState.COUNTDOWN -> return
        }
        context.startService(serviceIntent)
    }
}

object RecordingStateManager {
    @Volatile
    var currentState: RecordingState = RecordingState.IDLE

    @Volatile
    var currentDurationMs: Long = 0L

    @Volatile
    var countdownRemaining: Int = 0

    var configuredKeyCode: Int = 0

    fun updateState(state: RecordingState) {
        currentState = state
    }

    fun updateDuration(durationMs: Long) {
        currentDurationMs = durationMs
    }

    fun updateCountdown(remaining: Int) {
        countdownRemaining = remaining
    }

    fun setKeyCode(keyCode: Int) {
        configuredKeyCode = keyCode
    }
}
