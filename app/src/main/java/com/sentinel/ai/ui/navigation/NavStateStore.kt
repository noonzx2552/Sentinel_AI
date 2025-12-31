package com.sentinel.ai.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object NavStateStore {
    private val _state = MutableStateFlow(NavUiState())
    val state: StateFlow<NavUiState> = _state.asStateFlow()

    fun setCurrentTab(tab: BottomTab) {
        _state.update { it.copy(currentTab = tab) }
    }

    fun setDanger(isDanger: Boolean) {
        _state.update { it.copy(isDanger = isDanger) }
    }

    fun toggleDanger() {
        _state.update { it.copy(isDanger = !it.isDanger) }
    }
}
