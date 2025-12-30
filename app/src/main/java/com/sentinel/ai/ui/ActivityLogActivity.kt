package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sentinel.ai.R

class ActivityLogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activity_log)

        findViewById<android.view.View>(R.id.navHome).setOnClickListener {
            goTo(Intent(this, HomeActivity::class.java), finishSelf = true)
        }
        findViewById<android.view.View>(R.id.navScan).setOnClickListener {
            goTo(Intent(this, ScanOptionsActivity::class.java), finishSelf = true)
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
