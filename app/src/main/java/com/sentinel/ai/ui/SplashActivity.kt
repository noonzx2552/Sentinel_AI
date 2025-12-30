package com.sentinel.ai.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.sentinel.ai.BuildConfig
import com.sentinel.ai.R
import com.sentinel.ai.databinding.ActivitySplashBinding
import com.sentinel.ai.utils.PermissionUtils

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val handler = Handler(Looper.getMainLooper())
    private var progressAnimator: ValueAnimator? = null
    private val statusMessages by lazy {
        listOf(
            getString(R.string.splash_status_model),
            getString(R.string.splash_status_rules),
            getString(R.string.splash_status_security)
        )
    }
    private val progressTargets = listOf(35, 70, 100)
    private val progressDurationsMs = listOf(700L, 700L, 600L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.version.text = "v${BuildConfig.VERSION_NAME}"
        binding.status.text = statusMessages.first()
        binding.progress.max = 100
        binding.progress.progress = 0
        binding.progress.post { startProgressPhase(0, 0) }

        handler.postDelayed({
            val target = Intent(this, IntroActivity::class.java)
            startActivity(target)
            finish()
        }, 2100)
    }

    private fun startProgressPhase(index: Int, fromProgress: Int) {
        if (index >= progressTargets.size) {
            return
        }
        binding.status.text = statusMessages[index]
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofInt(fromProgress, progressTargets[index]).apply {
            duration = progressDurationsMs[index]
            addUpdateListener { animator ->
                binding.progress.progress = animator.animatedValue as Int
            }
            start()
        }
        val nextFrom = progressTargets[index]
        handler.postDelayed(
            { startProgressPhase(index + 1, nextFrom) },
            progressDurationsMs[index]
        )
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        progressAnimator?.cancel()
        super.onDestroy()
    }
}
