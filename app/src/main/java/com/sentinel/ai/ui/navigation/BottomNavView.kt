package com.sentinel.ai.ui.navigation

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.sentinel.ai.R

class BottomNavView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tabViews: Map<BottomTab, View>
    private val iconViews: Map<BottomTab, ImageView>
    private val labelViews: Map<BottomTab, TextView>
    private val indicatorViews: Map<BottomTab, MaterialCardView>
    private val indicatorIcons: Map<BottomTab, ImageView>
    private val indicatorInnerViews: Map<BottomTab, View>
    private val shadowViews: Map<BottomTab, View>
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
        // Keep labels on top to avoid being obscured by indicators/shadows
        labelViews.values.forEach { label ->
            label.bringToFront()
            label.translationZ = 8f
            label.alpha = 1f
            label.visibility = View.VISIBLE
        }
        indicatorViews = mapOf(
            BottomTab.HOME to findViewById<MaterialCardView>(R.id.tabHomeIndicator),
            BottomTab.ACTIVITY to findViewById<MaterialCardView>(R.id.tabActivityIndicator),
            BottomTab.SCAN to findViewById<MaterialCardView>(R.id.tabScanIndicator),
            BottomTab.PROFILE to findViewById<MaterialCardView>(R.id.tabProfileIndicator)
        )
        indicatorIcons = mapOf(
            BottomTab.HOME to findViewById(R.id.tabHomeIndicatorIcon),
            BottomTab.ACTIVITY to findViewById(R.id.tabActivityIndicatorIcon),
            BottomTab.SCAN to findViewById(R.id.tabScanIndicatorIcon),
            BottomTab.PROFILE to findViewById(R.id.tabProfileIndicatorIcon)
        )
        indicatorInnerViews = mapOf(
            BottomTab.HOME to findViewById(R.id.tabHomeIndicatorInner),
            BottomTab.ACTIVITY to findViewById(R.id.tabActivityIndicatorInner),
            BottomTab.SCAN to findViewById(R.id.tabScanIndicatorInner),
            BottomTab.PROFILE to findViewById(R.id.tabProfileIndicatorInner)
        )
        shadowViews = mapOf(
            BottomTab.HOME to findViewById(R.id.tabHomeShadow),
            BottomTab.ACTIVITY to findViewById(R.id.tabActivityShadow),
            BottomTab.SCAN to findViewById(R.id.tabScanShadow),
            BottomTab.PROFILE to findViewById(R.id.tabProfileShadow)
        )
        indicatorDrawables = indicatorInnerViews.mapValues { entry ->
            val bg: Drawable? = entry.value.background
            val gradient: GradientDrawable = ((bg as? GradientDrawable)?.mutate() as? GradientDrawable)
                ?: GradientDrawable().apply { shape = GradientDrawable.OVAL }
            entry.value.background = gradient
            gradient
        }
        indicatorViews.forEach { (_, view) ->
            view.alpha = 0f
            view.scaleX = 0.92f
            view.scaleY = 0.92f
            view.visibility = View.INVISIBLE
        }
        indicatorInnerViews.forEach { (tab, inner) ->
            inner.background = indicatorDrawables.getValue(tab)
        }
        shadowViews.forEach { (_, shadow) ->
            shadow.alpha = 0f
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
        updateLabels(tab, activeColor, animate && activeTab != null)
        updateIcons(tab)
        updateIndicators(tab, activeColor, animate && activeTab != null)
        activeTab = tab
    }

    private fun updateLabels(active: BottomTab, activeColor: Int, animate: Boolean) {
        val inactiveColor = ContextCompat.getColor(context, R.color.bottom_nav_inactive)
        labelViews.forEach { (tab, label) ->
            label.visibility = View.VISIBLE
            label.alpha = 1f
            val isActive = tab == active
            // Remove vertical translation by setting it to 0f always.
            val targetTranslation = 0f
            if (!animate) {
                label.translationY = targetTranslation
            } else {
                label.animate().cancel()
                label.animate()
                    .translationY(targetTranslation)
                    .setDuration(200)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            label.setTextColor(if (isActive) activeColor else inactiveColor)
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
            val shadow = shadowViews[tab]
            if (drawable != null) drawable.setColor(activeColor)
            if (icon != null) {
                icon.setImageResource(items[tab]?.iconRes ?: 0)
                icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            shadow?.backgroundTintList = ColorStateList.valueOf(activeColor)
            shadow?.backgroundTintMode = PorterDuff.Mode.SRC_IN

            if (!animate) {
                indicator.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
                indicator.alpha = if (isActive) 1f else 0f
                indicator.scaleX = if (isActive) 1f else 0.92f
                indicator.scaleY = if (isActive) 1f else 0.92f
                shadow?.alpha = if (isActive) 0.85f else 0f
                return@forEach
            }

            indicator.animate().cancel()
            shadow?.animate()?.cancel()
            if (isActive) {
                indicator.visibility = View.VISIBLE
                indicator.alpha = 0f
                indicator.scaleX = 0.92f
                indicator.scaleY = 0.92f
                indicator.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                shadow?.alpha = 0f
                shadow?.animate()
                    ?.alpha(0.9f)
                    ?.setDuration(240)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.start()
            } else {
                indicator.animate()
                    .alpha(0f)
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { indicator.visibility = View.INVISIBLE }
                    .start()
                shadow?.animate()
                    ?.alpha(0f)
                    ?.setDuration(180)
                    ?.setInterpolator(DecelerateInterpolator())
                    ?.start()
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
