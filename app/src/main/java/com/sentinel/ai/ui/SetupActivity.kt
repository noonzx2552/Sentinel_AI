package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        binding.btnAccessibility.text = if (binding.btnAccessibility.isEnabled) "Enable" else "Enabled"
        binding.btnMic.text = if (binding.btnMic.isEnabled) "Enable" else "Enabled"
        binding.btnOverlay.text = if (binding.btnOverlay.isEnabled) "Enable" else "Enabled"
        binding.btnCallScreening.text = if (binding.btnCallScreening.isEnabled) "Enable" else "Enabled"
        binding.btnNotification.text = if (binding.btnNotification.isEnabled) "Enable" else "Enabled"
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
