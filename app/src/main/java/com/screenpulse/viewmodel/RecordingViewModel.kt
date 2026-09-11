package com.screenpulse.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class RecordingState {
    IDLE,
    COUNTDOWN,
    RECORDING,
    PAUSED
}

class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private val _countdownRemaining = MutableStateFlow(0)
    val countdownRemaining: StateFlow<Int> = _countdownRemaining.asStateFlow()

    private val _isFloatingWindowVisible = MutableStateFlow(true)
    val isFloatingWindowVisible: StateFlow<Boolean> = _isFloatingWindowVisible.asStateFlow()

    fun setRecordingState(state: RecordingState) {
        _recordingState.value = state
        com.screenpulse.shortcut.RecordingStateManager.updateState(state)
    }

    fun updateDuration(durationMs: Long) {
        _recordingDuration.value = durationMs
    }

    fun updateCountdown(remaining: Int) {
        _countdownRemaining.value = remaining
    }

    fun toggleFloatingWindow() {
        _isFloatingWindowVisible.value = !_isFloatingWindowVisible.value
    }
}
