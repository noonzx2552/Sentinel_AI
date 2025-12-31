package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sentinel.ai.R
import com.sentinel.ai.ui.navigation.BottomTab
import kotlinx.coroutines.launch

class ProfileActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val debugRow = findViewById<View>(R.id.profileDebugRow)
        val versionLabel = findViewById<View>(R.id.profileVersionLabel)

        debugRow?.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
            overridePendingTransition(R.anim.fade_in_scale, R.anim.fade_out)
        }

        versionLabel?.setOnLongClickListener {
            DebugSettings.toggleDebug()
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DebugSettings.isDebugEnabled.collect { enabled ->
                    debugRow?.visibility = if (enabled) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun getCurrentTab(): BottomTab = BottomTab.PROFILE
}
