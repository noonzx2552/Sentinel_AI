package com.sentinel.ai.ui.navigation

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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
    private val activeBgViews: Map<BottomTab, View>

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
        activeBgViews = mapOf(
            BottomTab.HOME to findViewById(R.id.tabHomeActiveBg),
            BottomTab.ACTIVITY to findViewById(R.id.tabActivityActiveBg),
            BottomTab.SCAN to findViewById(R.id.tabScanActiveBg),
            BottomTab.PROFILE to findViewById(R.id.tabProfileActiveBg)
        )

        tabViews.forEach { (tab, view) ->
            view.setOnTouchListener { target, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        target.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        target.animate().scaleX(1f).scaleY(1f).setDuration(100L).start()
                    }
                }
                false
            }
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
        tabViews.forEach { (itemTab, view) ->
            view.isSelected = itemTab == tab
        }
        updateIcons(tab, activeColor)
        updateLabels(tab, activeColor)
        updateActivePills(tab, activeColor, animate && activeTab != null)
        activeTab = tab
    }

    private fun updateIcons(active: BottomTab, activeColor: Int) {
        val inactiveColor = ContextCompat.getColor(context, R.color.bottom_nav_inactive)
        iconViews.forEach { (tab, icon) ->
            val isActive = tab == active
            val tint = if (isActive) activeColor else inactiveColor
            icon.imageTintList = ColorStateList.valueOf(tint)
            icon.alpha = if (isActive) 1f else 0.72f
            icon.animate().cancel()
            icon.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120L)
                .start()
        }
    }

    private fun updateLabels(active: BottomTab, activeColor: Int) {
        val inactiveColor = ContextCompat.getColor(context, R.color.bottom_nav_inactive)
        labelViews.forEach { (tab, label) ->
            val isActive = tab == active
            label.setTextColor(if (isActive) activeColor else inactiveColor)
            label.alpha = if (isActive) 1f else 0.72f
            label.setTypeface(label.typeface, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            label.isSelected = isActive
            label.animate().cancel()
            label.animate()
                .translationY(0f)
                .setDuration(110L)
                .start()
        }
    }

    private fun updateActivePills(active: BottomTab, activeColor: Int, animate: Boolean) {
        val tint = ColorStateList.valueOf(activeColor)
        activeBgViews.forEach { (tab, bg) ->
            val isActive = tab == active
            bg.backgroundTintList = tint
            bg.isSelected = isActive
            if (!animate) {
                bg.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
                bg.alpha = if (isActive) 1f else 0f
                bg.scaleX = 1f
                bg.scaleY = 1f
            } else {
                bg.animate().cancel()
                if (isActive) {
                    bg.visibility = View.VISIBLE
                    bg.alpha = 0f
                    bg.scaleX = 0.94f
                    bg.scaleY = 0.94f
                    bg.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120L).start()
                } else {
                    bg.animate().alpha(0f).setDuration(90L).withEndAction {
                        bg.visibility = View.INVISIBLE
                        bg.scaleX = 1f
                        bg.scaleY = 1f
                    }.start()
                }
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

    private fun withAlpha(color: Int, alphaFraction: Float): Int {
        val safeAlpha = alphaFraction.coerceIn(0f, 1f)
        return Color.argb(
            (safeAlpha * 255).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }
}
