package com.sentinel.ai.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DebugSettings {
    private val _isDebugEnabled = MutableStateFlow(false)
    val isDebugEnabled: StateFlow<Boolean> = _isDebugEnabled.asStateFlow()

    fun setDebugEnabled(enabled: Boolean) {
        _isDebugEnabled.value = enabled
    }

    fun toggleDebug() {
        _isDebugEnabled.value = !_isDebugEnabled.value
    }
}
