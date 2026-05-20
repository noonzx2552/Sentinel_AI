package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import com.sentinel.ai.databinding.ActivityIntroBinding
import com.sentinel.ai.utils.ProfilePrefs

class IntroActivity : BaseLocalizedActivity() {

    private lateinit var binding: ActivityIntroBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ProfilePrefs.forceLightMode(this)
        super.onCreate(savedInstanceState)
        binding = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnGetStarted.setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
            finish()
        }
    }
}
