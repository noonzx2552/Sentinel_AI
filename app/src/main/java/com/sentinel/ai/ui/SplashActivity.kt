package com.sentinel.ai.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sentinel.ai.BuildConfig
import com.sentinel.ai.R
import com.sentinel.ai.databinding.ActivitySplashBinding
import com.sentinel.ai.utils.LanguageManager
import com.sentinel.ai.utils.OnboardingPrefs
import com.sentinel.ai.utils.PermissionUtils

class SplashActivity : BaseLocalizedActivity() {

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
    private val progressDurationMs = 2000L
    private val progressHoldAfterMs = 500L
    private var ambientAnimator: AnimatorSet? = null
    private var statusStep = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        
        // Let system bars follow the theme instead of being hardcoded
        val isDark = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prepareInitialState()

        splashScreen.setOnExitAnimationListener { splashScreenView ->
            val fadeOut = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 200
                addUpdateListener {
                    splashScreenView.view.alpha = it.animatedValue as Float
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        splashScreenView.remove()
                        fadeInContent()
                    }
                })
            }
            fadeOut.start()
        }
    }

    private fun fadeInContent() {
        binding.version.text = "v${BuildConfig.VERSION_NAME}"
        binding.status.text = statusMessages.first()
        binding.progress.max = 100
        binding.progress.progress = 0

        if (shouldReduceMotion()) {
            listOf(
                binding.logoGlow,
                binding.scanRingOuter,
                binding.scanRingInner,
                binding.logo,
                binding.title,
                binding.subtitle,
                binding.status,
                binding.progress,
                binding.version
            ).forEach { it.alpha = 1f }
        } else {
            playSplashEntrance()
            startAmbientMotion()
        }
        startProgressOnce()
    }

    private fun navigateToNextScreen() {
        val target = if (OnboardingPrefs.isComplete(this) && PermissionUtils.allEssentialGranted(this)) {
            Intent(this, HomeActivity::class.java)
        } else {
            Intent(this, IntroActivity::class.java)
        }
        startActivity(target)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun startProgressOnce() {
        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofInt(0, 100).apply {
            duration = progressDurationMs
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Int
                binding.progress.progress = progress
                updateStatusForProgress(progress)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.status.text = statusMessages.last()
                    ambientAnimator?.cancel()
                    if (!shouldReduceMotion()) {
                        playExitPulse()
                    }
                    handler.postDelayed({ navigateToNextScreen() }, progressHoldAfterMs)
                }
            })
            start()
        }
    }

    private fun prepareInitialState() {
        listOf(
            binding.logoGlow,
            binding.scanRingOuter,
            binding.scanRingInner,
            binding.logo,
            binding.title,
            binding.subtitle,
            binding.status,
            binding.progress,
            binding.version
        ).forEach { it.alpha = 0f }
        binding.logo.scaleX = 0.9f
        binding.logo.scaleY = 0.9f
        binding.logo.translationY = 18f
        listOf(binding.title, binding.subtitle, binding.status, binding.progress).forEach {
            it.translationY = 18f
        }
        binding.scanRingOuter.scaleX = 0.84f
        binding.scanRingOuter.scaleY = 0.84f
        binding.scanRingInner.scaleX = 0.9f
        binding.scanRingInner.scaleY = 0.9f
    }

    private fun playSplashEntrance() {
        val easeOut = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val logoReveal = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.logoGlow, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.logo, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.logo, View.SCALE_X, 0.9f, 1f),
                ObjectAnimator.ofFloat(binding.logo, View.SCALE_Y, 0.9f, 1f),
                ObjectAnimator.ofFloat(binding.logo, View.TRANSLATION_Y, 18f, 0f),
                ObjectAnimator.ofFloat(binding.scanRingOuter, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.scanRingOuter, View.SCALE_X, 0.84f, 1f),
                ObjectAnimator.ofFloat(binding.scanRingOuter, View.SCALE_Y, 0.84f, 1f),
                ObjectAnimator.ofFloat(binding.scanRingInner, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.scanRingInner, View.SCALE_X, 0.9f, 1f),
                ObjectAnimator.ofFloat(binding.scanRingInner, View.SCALE_Y, 0.9f, 1f)
            )
            duration = 620
            interpolator = easeOut
        }

        val copyReveal = listOf(binding.title, binding.subtitle, binding.progress, binding.status, binding.version)
            .mapIndexed { index, view ->
                AnimatorSet().apply {
                    startDelay = 180L + (index * 90L)
                    duration = 460
                    interpolator = easeOut
                    playTogether(
                        ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 18f, 0f)
                    )
                }
            }
        AnimatorSet().apply {
            playTogether(listOf(logoReveal) + copyReveal)
            start()
        }
    }

    private fun startAmbientMotion() {
        val outerSpin = ObjectAnimator.ofFloat(binding.scanRingOuter, View.ROTATION, 0f, 360f).apply {
            duration = 5200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        val innerSpin = ObjectAnimator.ofFloat(binding.scanRingInner, View.ROTATION, 360f, 0f).apply {
            duration = 3800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        val glowPulseX = ObjectAnimator.ofFloat(binding.logoGlow, View.SCALE_X, 0.96f, 1.04f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
        }
        val glowPulseY = ObjectAnimator.ofFloat(binding.logoGlow, View.SCALE_Y, 0.96f, 1.04f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
        }
        ambientAnimator = AnimatorSet().apply {
            playTogether(outerSpin, innerSpin, glowPulseX, glowPulseY)
            start()
        }
    }

    private fun updateStatusForProgress(progress: Int) {
        val nextStep = when {
            progress < 42 -> 0
            progress < 76 -> 1
            else -> 2
        }
        if (nextStep != statusStep) {
            statusStep = nextStep
            if (shouldReduceMotion()) {
                binding.status.text = statusMessages[nextStep]
            } else {
                binding.status.animate()
                    .alpha(0f)
                    .translationY(-8f)
                    .setDuration(120)
                    .withEndAction {
                        binding.status.text = statusMessages[nextStep]
                        binding.status.translationY = 8f
                        binding.status.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(180)
                            .setInterpolator(PathInterpolator(0.22f, 1f, 0.36f, 1f))
                            .start()
                    }
                    .start()
            }
        }
    }

    private fun playExitPulse() {
        AnimatorSet().apply {
            duration = 260
            interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
            playTogether(
                ObjectAnimator.ofFloat(binding.scanRingOuter, View.SCALE_X, 1f, 1.08f),
                ObjectAnimator.ofFloat(binding.scanRingOuter, View.SCALE_Y, 1f, 1.08f),
                ObjectAnimator.ofFloat(binding.scanRingOuter, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(binding.scanRingInner, View.SCALE_X, 1f, 1.12f),
                ObjectAnimator.ofFloat(binding.scanRingInner, View.SCALE_Y, 1f, 1.12f),
                ObjectAnimator.ofFloat(binding.scanRingInner, View.ALPHA, 1f, 0f)
            )
            start()
        }
    }

    private fun shouldReduceMotion(): Boolean {
        return Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        progressAnimator?.cancel()
        ambientAnimator?.cancel()
        binding.status.animate().cancel()
        super.onDestroy()
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LanguageManager.wrapWithLanguage(newBase, LanguageManager.LANG_EN))
    }

    override fun shouldAnimateRootOnEnter(): Boolean = false

    override fun shouldApplyAppLanguage(): Boolean = false
}
