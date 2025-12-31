package com.sentinel.ai.ui.navigation

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

data class NavItem(
    val tab: BottomTab,
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
    @ColorRes val activeColorRes: Int
)
