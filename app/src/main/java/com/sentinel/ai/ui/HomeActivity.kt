package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.sentinel.ai.R
import com.sentinel.ai.ui.navigation.BottomTab
import com.sentinel.ai.ui.navigation.NavStateStore

class HomeActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<View>(R.id.quickCheckLink).setOnClickListener {
            goTo(Intent(this, CheckLinkActivity::class.java))
        }
        findViewById<View>(R.id.quickCheckNumber).setOnClickListener {
            goTo(Intent(this, CheckNumberActivity::class.java))
        }
        findViewById<View>(R.id.quickCallLog).setOnClickListener {
            goTo(Intent(this, CallLogScanActivity::class.java))
        }
        findViewById<View>(R.id.homeStatusTitle)?.setOnLongClickListener {
            NavStateStore.toggleDanger()
            true
        }
    }

    override fun getCurrentTab(): BottomTab = BottomTab.HOME

    private fun goTo(intent: Intent, finishSelf: Boolean = false) {
        startActivity(intent)
        if (finishSelf) finish()
        overridePendingTransition(R.anim.fade_in_scale, R.anim.fade_out)
    }
}
