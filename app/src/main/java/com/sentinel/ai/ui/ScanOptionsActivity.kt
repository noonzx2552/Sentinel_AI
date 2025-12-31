package com.sentinel.ai.ui

import android.os.Bundle
import com.sentinel.ai.R
import com.sentinel.ai.ui.navigation.BottomTab

class ScanOptionsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_options)

    }

    override fun getCurrentTab(): BottomTab = BottomTab.SCAN

}
