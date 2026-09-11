package com.screenpulse.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ProjectionRequestBus {

    const val REQUEST_PROJECTION_ACTION = "com.screenpulse.action.REQUEST_PROJECTION"

    private val _requestCount = MutableStateFlow(0)
    val requestCount: StateFlow<Int> = _requestCount.asStateFlow()

    fun request() {
        _requestCount.update { it + 1 }
    }
}