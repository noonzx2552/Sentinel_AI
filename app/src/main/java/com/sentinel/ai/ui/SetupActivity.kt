package com.sentinel.ai.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.sentinel.ai.R
import com.sentinel.ai.databinding.ActivitySetupBinding
import com.sentinel.ai.service.SentinelGuardianService
import com.sentinel.ai.utils.ModelInitializer
import com.sentinel.ai.utils.PermissionUtils

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ModelInitializer.initialize(this)

        binding.btnAccessibility.setOnClickListener {
            PermissionUtils.openAccessibilitySettings(this)
        }
        binding.btnMic.setOnClickListener {
            PermissionUtils.requestMicPermission(this, REQ_MIC)
        }
        binding.btnOverlay.setOnClickListener {
            PermissionUtils.requestOverlayPermission(this)
        }
        binding.btnCallScreening.setOnClickListener {
            PermissionUtils.requestCallScreeningRole(this, REQ_ROLE)
        }
        binding.btnNotification.setOnClickListener {
            PermissionUtils.requestNotificationPermission(this, REQ_NOTIFICATIONS)
        }
        binding.btnContinue.setOnClickListener {
            if (!PermissionUtils.allEssentialGranted(this)) {
                Toast.makeText(this, "Please enable all permissions for Guardian Mode.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SentinelGuardianService.start(this)
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtons()
    }

    private fun updateButtons() {
        binding.btnAccessibility.isEnabled = !PermissionUtils.isAccessibilityEnabled(this)
        binding.btnMic.isEnabled = !PermissionUtils.hasMicPermission(this)
        binding.btnOverlay.isEnabled = !PermissionUtils.canDrawOverlays(this)
        binding.btnCallScreening.isEnabled = !PermissionUtils.isCallScreeningRoleGranted(this)
        binding.btnNotification.isEnabled = !PermissionUtils.isNotificationPermissionGranted(this)

        applyPermissionState(
            binding.cardAccessibility,
            binding.btnAccessibility,
            PermissionUtils.isAccessibilityEnabled(this)
        )
        applyPermissionState(
            binding.cardMic,
            binding.btnMic,
            PermissionUtils.hasMicPermission(this)
        )
        applyPermissionState(
            binding.cardOverlay,
            binding.btnOverlay,
            PermissionUtils.canDrawOverlays(this)
        )
        applyPermissionState(
            binding.cardCall,
            binding.btnCallScreening,
            PermissionUtils.isCallScreeningRoleGranted(this)
        )
        applyPermissionState(
            binding.cardNotification,
            binding.btnNotification,
            PermissionUtils.isNotificationPermissionGranted(this)
        )

        val allGranted = PermissionUtils.allEssentialGranted(this)
        binding.btnContinue.isEnabled = allGranted
        binding.btnContinue.alpha = if (allGranted) 1f else 0.6f
    }

    private fun applyPermissionState(card: MaterialCardView, button: MaterialButton, granted: Boolean) {
        val ctx = card.context
        val doneColor = ContextCompat.getColor(ctx, R.color.permission_done)
        val pendingColor = ContextCompat.getColor(ctx, R.color.permission_pending)
        card.setCardBackgroundColor(if (granted) doneColor else pendingColor)

        button.text = if (granted) getString(R.string.action_enabled) else getString(R.string.action_enable)
        button.isEnabled = !granted
        val btnTint = if (granted) doneColor else ContextCompat.getColor(ctx, R.color.sentinel_accent)
        button.setBackgroundTintList(ColorStateList.valueOf(btnTint))
        button.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.sentinel_accent))
        button.setTextColor(ContextCompat.getColor(ctx, R.color.sentinel_on_surface))
        button.iconTint = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.sentinel_on_surface))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC || requestCode == REQ_NOTIFICATIONS) {
            updateButtons()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ROLE) {
            updateButtons()
        }
    }

    companion object {
        private const val REQ_MIC = 100
        private const val REQ_NOTIFICATIONS = 101
        private const val REQ_ROLE = 102
    }
}
