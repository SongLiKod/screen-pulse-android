package com.screenpulse.shortcut

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.screenpulse.service.ScreenRecordService
import com.screenpulse.util.OverlayRecordingStarter
import com.screenpulse.viewmodel.RecordingState

class ShortcutKeyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.screenpulse.ACTION_TOGGLE_RECORDING") return

        val currentState = RecordingStateManager.currentState
        when (currentState) {
            RecordingState.IDLE -> OverlayRecordingStarter.start(context)
            RecordingState.RECORDING -> context.startService(
                Intent(context, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_PAUSE
                }
            )
            RecordingState.PAUSED -> context.startService(
                Intent(context, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_RESUME
                }
            )
            RecordingState.COUNTDOWN -> return
        }
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
