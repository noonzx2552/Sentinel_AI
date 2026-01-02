package com.sentinel.ai.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sentinel.ai.BuildConfig
import com.sentinel.ai.R
import com.sentinel.ai.databinding.ActivitySplashBinding
import com.sentinel.ai.utils.OnboardingPrefs
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
    private val progressDurationMs = 1200L

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = getColor(R.color.splash_bg)
        window.navigationBarColor = getColor(R.color.splash_bg)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start with all views transparent
        val viewsToAnimate = listOf(binding.logo, binding.title, binding.subtitle, binding.status, binding.progress, binding.version)
        viewsToAnimate.forEach { it.alpha = 0f }

        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val fadeOut = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 200
                addUpdateListener {
                    splashScreenView.view.alpha = it.animatedValue as Float
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        splashScreenView.remove()
                        // Fade in the main content after splash icon disappears
                        fadeInContent()
                    }
                })
            }
            fadeOut.start()
        }
    }

    private fun fadeInContent() {
        val viewsToAnimate = listOf(binding.logo, binding.title, binding.subtitle, binding.status, binding.progress, binding.version)
        viewsToAnimate.forEach { it.alpha = 1f }

        binding.version.text = "v${BuildConfig.VERSION_NAME}"
        binding.status.text = statusMessages.first()
        binding.progress.max = 100
        binding.progress.progress = 0
        startProgressOnce()
    }

    private fun navigateToNextScreen() {
        val target = if (OnboardingPrefs.isComplete(this) && PermissionUtils.allEssentialGranted(this)) {
            Intent(this, HomeActivity::class.java)
        } else {
            Intent(this, IntroActivity::class.java)
        }
        startActivity(target)
        // Apply fade-out transition to this activity and fade-in to the next
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun startProgressOnce() {
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofInt(0, 100).apply {
            duration = progressDurationMs
            addUpdateListener { animator ->
                binding.progress.progress = animator.animatedValue as Int
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    navigateToNextScreen()
                }
            })
            start()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        progressAnimator?.cancel()
        super.onDestroy()
    }
}
