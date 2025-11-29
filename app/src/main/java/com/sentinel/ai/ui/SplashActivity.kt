package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.sentinel.ai.databinding.ActivitySplashBinding
import com.sentinel.ai.utils.PermissionUtils

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Handler(Looper.getMainLooper()).postDelayed({
            val target = if (PermissionUtils.allEssentialGranted(this)) {
                Intent(this, DashboardActivity::class.java)
            } else {
                Intent(this, SetupActivity::class.java)
            }
            startActivity(target)
            finish()
        }, 1200)
    }
}
