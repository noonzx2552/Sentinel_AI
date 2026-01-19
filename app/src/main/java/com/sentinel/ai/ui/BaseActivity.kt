package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import com.sentinel.ai.R
import com.sentinel.ai.ui.navigation.BottomNavController
import com.sentinel.ai.ui.navigation.BottomNavView
import com.sentinel.ai.ui.navigation.BottomTab
import com.sentinel.ai.ui.navigation.NavItem
import com.sentinel.ai.ui.navigation.NavStateStore

abstract class BaseActivity : BaseLocalizedActivity() {

    private var bottomNavController: BottomNavController? = null

    protected abstract fun getCurrentTab(): BottomTab

    override fun onCreate(savedInstanceState: Bundle?) {
        com.sentinel.ai.utils.ProfilePrefs.applyTheme(com.sentinel.ai.utils.ProfilePrefs.isDarkMode(this))
        super.onCreate(savedInstanceState)
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        bindBottomNav()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            NavStateStore.setCurrentTab(getCurrentTab())
        }
    }

    private fun bindBottomNav() {
        val bottomNavView = findViewById<BottomNavView>(R.id.bottomNavView) ?: return
        val navItems = listOf(
            NavItem(BottomTab.HOME, R.drawable.ic_nav_home, R.string.home_nav_home, R.color.bottom_nav_active_green),
            NavItem(BottomTab.ACTIVITY, R.drawable.ic_nav_activity, R.string.home_nav_activity, R.color.bottom_nav_active_green),
            NavItem(BottomTab.SCAN, R.drawable.ic_nav_scan, R.string.home_nav_scan, R.color.bottom_nav_active_green),
            NavItem(BottomTab.PROFILE, R.drawable.ic_nav_profile, R.string.home_nav_profile, R.color.bottom_nav_active_blue)
        )

        bottomNavController = BottomNavController(this, bottomNavView, navItems)
        bottomNavController?.bind { tab -> navigateTo(tab) }
    }

    private fun navigateTo(tab: BottomTab) {
        val target = when (tab) {
            BottomTab.HOME -> HomeActivity::class.java
            BottomTab.ACTIVITY -> ActivityLogActivity::class.java
            BottomTab.SCAN -> ScanOptionsActivity::class.java
            BottomTab.PROFILE -> ProfileActivity::class.java
        }
        val intent = Intent(this, target).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in_scale, R.anim.fade_out)
    }
}
