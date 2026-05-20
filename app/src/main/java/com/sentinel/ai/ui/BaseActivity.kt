package com.sentinel.ai.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.snackbar.Snackbar
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
        window.decorView.post { animateSubpageEntrance() }
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
            addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        startActivity(intent)
        overridePendingTransition(0, 0)
    }

    enum class PopupSeverity { SUCCESS, WARNING }

    protected fun showBottomPopup(message: String, isLong: Boolean = false, severity: PopupSeverity? = null) {
        val duration = if (isLong) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
        val root = findViewById<android.view.View>(android.R.id.content) ?: return
        val snackbar = Snackbar.make(root, message, duration)
        snackbar.setAnimationMode(Snackbar.ANIMATION_MODE_FADE)

        findViewById<BottomNavView>(R.id.bottomNavView)?.let { nav ->
            snackbar.anchorView = nav
        }

        val snackbarLayout = snackbar.view as Snackbar.SnackbarLayout
        snackbarLayout.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        snackbarLayout.setBackgroundColor(Color.TRANSPARENT)
        snackbarLayout.background = null
        snackbarLayout.elevation = 0f
        snackbarLayout.setPadding(dp(16), 0, dp(16), dp(8))
        snackbarLayout.removeAllViews()

        val popupView = layoutInflater.inflate(R.layout.view_bottom_popup, snackbarLayout, false)
        val messageView = popupView.findViewById<TextView>(R.id.popupMessage)
        val iconView = popupView.findViewById<ImageView>(R.id.popupIcon)

        messageView.text = message

        val resolved = severity ?: inferSeverityFromMessage(message)
        val isWarning = resolved == PopupSeverity.WARNING
        iconView.setImageResource(if (isWarning) R.drawable.ic_exclamation else R.drawable.ic_check)
        iconView.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (isWarning) R.color.popup_icon_warning else R.color.popup_icon_success
            )
        )

        snackbarLayout.addView(popupView, 0)

        snackbar.show()
    }

    private fun inferSeverityFromMessage(message: String): PopupSeverity {
        val isWarning = message.contains("disable", ignoreCase = true) ||
            message.contains("error", ignoreCase = true) ||
            message.contains("failed", ignoreCase = true)
        return if (isWarning) PopupSeverity.WARNING else PopupSeverity.SUCCESS
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun animateSubpageEntrance() {
        val content = findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        val root = content.getChildAt(0) as? ViewGroup ?: return

        val targetGroup = findPrimaryContainer(root) ?: return
        val children = (0 until targetGroup.childCount)
            .mapNotNull { targetGroup.getChildAt(it) }
            .filter { it.visibility == View.VISIBLE && it.height >= 0 && it.id != R.id.bottomNavView }

        if (children.isEmpty()) return

        children.forEachIndexed { index, child ->
            child.alpha = 0f
            child.translationY = 18f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 38L).coerceAtMost(260L))
                .setDuration(340L)
                .start()
        }
    }

    private fun findPrimaryContainer(root: ViewGroup): ViewGroup? {
        val idCandidates = intArrayOf(
            R.id.scanOptionsScroll,
            R.id.checkLinkScroll,
            R.id.checkNumberScroll,
            R.id.activityLogScroll,
            R.id.profileScroll
        )
        idCandidates.forEach { id ->
            val view = findViewById<View>(id) ?: return@forEach
            when (view) {
                is ScrollView -> return view.getChildAt(0) as? ViewGroup
                is NestedScrollView -> return view.getChildAt(0) as? ViewGroup
                is ViewGroup -> return view
            }
        }
        return root
    }
}
