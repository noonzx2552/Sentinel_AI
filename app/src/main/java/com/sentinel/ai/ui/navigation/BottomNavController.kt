package com.sentinel.ai.ui.navigation

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class BottomNavController(
    private val activity: AppCompatActivity,
    private val bottomNavView: BottomNavView,
    private val navItems: List<NavItem>,
    private val stateStore: NavStateStore = NavStateStore
) {
    private var isBound = false

    fun bind(onNavigate: (BottomTab) -> Unit) {
        if (isBound) return
        isBound = true

        bottomNavView.setItems(navItems)
        bottomNavView.render(stateStore.state.value, animate = false)

        bottomNavView.onTabSelected = fun(tab: BottomTab) {
            val current = stateStore.state.value.currentTab
            if (tab == current) return
            stateStore.setCurrentTab(tab)
            bottomNavView.render(stateStore.state.value.copy(currentTab = tab), animate = true)
            // Give the tap animation a short lead so the nav feels responsive.
            bottomNavView.postDelayed({ onNavigate(tab) }, 90L)
        }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                stateStore.state.collect { state ->
                    bottomNavView.render(state, animate = false)
                }
            }
        }
    }
}
