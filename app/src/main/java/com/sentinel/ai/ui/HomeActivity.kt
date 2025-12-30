package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sentinel.ai.R

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<android.view.View>(R.id.quickCheckLink).setOnClickListener {
            goTo(Intent(this, CheckLinkActivity::class.java))
        }
        findViewById<android.view.View>(R.id.quickCheckNumber).setOnClickListener {
            goTo(Intent(this, CheckNumberActivity::class.java))
        }
        findViewById<android.view.View>(R.id.quickCallLog).setOnClickListener {
            goTo(Intent(this, CallLogScanActivity::class.java))
        }
        findViewById<android.view.View>(R.id.navActivity).setOnClickListener {
            goTo(Intent(this, ActivityLogActivity::class.java))
        }
        findViewById<android.view.View>(R.id.navScan).setOnClickListener {
            goTo(Intent(this, ScanOptionsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.navProfile).setOnClickListener {
            goTo(Intent(this, ProfileActivity::class.java))
        }
    }

    private fun goTo(intent: Intent, finishSelf: Boolean = false) {
        startActivity(intent)
        if (finishSelf) finish()
        overridePendingTransition(R.anim.fade_in_scale, R.anim.fade_out)
    }
}
