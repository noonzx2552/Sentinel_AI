package com.sentinel.ai.ui.navigation

data class NavUiState(
    val currentTab: BottomTab = BottomTab.HOME,
    val isDanger: Boolean = false
)
