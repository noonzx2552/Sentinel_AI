package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sentinel.ai.databinding.ActivityAlertBinding

/**
 * Simple alert surface shown after an auto-hangup event. Data shown is transient and in-memory only.
 */
class CriticalAlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val score = intent.getIntExtra(EXTRA_SCORE, 0)
        val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()

        binding.alertScore.text = "Risk score: $score"
        binding.alertDetail.text = detail
        binding.alertDismiss.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    companion object {
        const val EXTRA_SCORE = "extra_score"
        const val EXTRA_DETAIL = "extra_detail"
    }
}
