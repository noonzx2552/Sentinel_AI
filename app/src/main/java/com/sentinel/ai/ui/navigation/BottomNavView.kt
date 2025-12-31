package com.sentinel.ai.ui.navigation

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sentinel.ai.R

class BottomNavView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tabViews: Map<BottomTab, View>
    private val iconViews: Map<BottomTab, ImageView>
    private val labelViews: Map<BottomTab, TextView>
    private val indicatorViews: Map<BottomTab, View>
    private val indicatorIcons: Map<BottomTab, ImageView>
    private val indicatorDrawables: Map<BottomTab, GradientDrawable>

    private var items: Map<BottomTab, NavItem> = emptyMap()
    private var activeTab: BottomTab? = null

    var onTabSelected: ((BottomTab) -> Unit)? = null

    init {
        inflate(context, R.layout.view_bottom_nav, this)
        clipChildren = false
        clipToPadding = false

        tabViews = mapOf(
            BottomTab.HOME to findViewById(R.id.tabHome),
            BottomTab.ACTIVITY to findViewById(R.id.tabActivity),
            BottomTab.SCAN to findViewById(R.id.tabScan),
            BottomTab.PROFILE to findViewById(R.id.tabProfile)
        )
        iconViews = mapOf(
            BottomTab.HOME to findViewById(R.id.tabHomeIcon),
            BottomTab.ACTIVITY to findViewById(R.id.tabActivityIcon),
            BottomTab.SCAN to findViewById(R.id.tabScanIcon),
            BottomTab.PROFILE to findViewById(R.id.tabProfileIcon)
        )
        labelViews = mapOf(
            BottomTab.HOME to findViewById(R.id.tabHomeLabel),
            BottomTab.ACTIVITY to findViewById(R.id.tabActivityLabel),
            BottomTab.SCAN to findViewById(R.id.tabScanLabel),
            BottomTab.PROFILE to findViewById(R.id.tabProfileLabel)
        )
        indicatorViews = mapOf(
            BottomTab.HOME to findViewById(R.id.tabHomeIndicator),
            BottomTab.ACTIVITY to findViewById(R.id.tabActivityIndicator),
            BottomTab.SCAN to findViewById(R.id.tabScanIndicator),
            BottomTab.PROFILE to findViewById(R.id.tabProfileIndicator)
        )
        indicatorIcons = mapOf(
            BottomTab.HOME to findViewById(R.id.tabHomeIndicatorIcon),
            BottomTab.ACTIVITY to findViewById(R.id.tabActivityIndicatorIcon),
            BottomTab.SCAN to findViewById(R.id.tabScanIndicatorIcon),
            BottomTab.PROFILE to findViewById(R.id.tabProfileIndicatorIcon)
        )
        indicatorDrawables = indicatorViews.mapValues { GradientDrawable().apply { shape = GradientDrawable.OVAL } }
        indicatorViews.forEach { (tab, view) ->
            view.background = indicatorDrawables.getValue(tab)
            view.alpha = 0f
            view.scaleX = 0.9f
            view.scaleY = 0.9f
        }

        tabViews.forEach { (tab, view) ->
            view.setOnClickListener { onTabSelected?.invoke(tab) }
        }
    }

    fun setItems(items: List<NavItem>) {
        this.items = items.associateBy { it.tab }
        items.forEach { item ->
            iconViews[item.tab]?.setImageResource(item.iconRes)
            labelViews[item.tab]?.setText(item.labelRes)
        }
    }

    fun render(state: NavUiState, animate: Boolean) {
        val tab = state.currentTab
        val activeColor = activeColorFor(tab, state.isDanger)
        updateLabels(tab, activeColor)
        updateIcons(tab)
        updateIndicators(tab, activeColor, animate && activeTab != null)
        activeTab = tab
    }

    private fun updateLabels(active: BottomTab, activeColor: Int) {
        val inactiveColor = ContextCompat.getColor(context, R.color.bottom_nav_inactive)
        labelViews.forEach { (tab, label) ->
            label.setTextColor(if (tab == active) activeColor else inactiveColor)
        }
    }

    private fun updateIcons(active: BottomTab) {
        val inactiveColor = ContextCompat.getColor(context, R.color.bottom_nav_inactive)
        val inactiveTint = ColorStateList.valueOf(inactiveColor)
        iconViews.forEach { (tab, icon) ->
            icon.imageTintList = inactiveTint
            icon.alpha = if (tab == active) 0f else 1f
        }
    }

    private fun updateIndicators(active: BottomTab, activeColor: Int, animate: Boolean) {
        indicatorViews.forEach { (tab, indicator) ->
            val isActive = tab == active
            val icon = indicatorIcons[tab]
            val drawable = indicatorDrawables[tab]
            if (drawable != null) drawable.setColor(activeColor)
            if (icon != null) {
                icon.setImageResource(items[tab]?.iconRes ?: 0)
                icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            }

            if (!animate) {
                indicator.alpha = if (isActive) 1f else 0f
                indicator.scaleX = if (isActive) 1f else 0.9f
                indicator.scaleY = if (isActive) 1f else 0.9f
                return@forEach
            }

            indicator.animate().cancel()
            if (isActive) {
                indicator.alpha = 0f
                indicator.scaleX = 0.9f
                indicator.scaleY = 0.9f
                indicator.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                indicator.animate()
                    .alpha(0f)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun activeColorFor(tab: BottomTab, isDanger: Boolean): Int {
        return if (tab == BottomTab.HOME) {
            ContextCompat.getColor(
                context,
                if (isDanger) R.color.bottom_nav_active_red else R.color.bottom_nav_active_green
            )
        } else {
            val item = items[tab]
            val colorRes = item?.activeColorRes ?: R.color.bottom_nav_active_green
            ContextCompat.getColor(context, colorRes)
        }
    }
}
