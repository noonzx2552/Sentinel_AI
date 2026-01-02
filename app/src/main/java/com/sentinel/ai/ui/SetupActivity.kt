package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
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
            handlePermissionSwitch(
                binding.btnAccessibility,
                PermissionUtils.isAccessibilityEnabled(this)
            ) {
                PermissionUtils.openAccessibilitySettings(this)
            }
        }
        binding.btnMic.setOnClickListener {
            handlePermissionSwitch(
                binding.btnMic,
                PermissionUtils.hasMicPermission(this)
            ) {
                PermissionUtils.requestMicPermission(this, REQ_MIC)
            }
        }
        binding.btnOverlay.setOnClickListener {
            handlePermissionSwitch(
                binding.btnOverlay,
                PermissionUtils.canDrawOverlays(this)
            ) {
                PermissionUtils.requestOverlayPermission(this)
            }
        }
        binding.btnCallScreening.setOnClickListener {
            handlePermissionSwitch(
                binding.btnCallScreening,
                PermissionUtils.isCallScreeningRoleGranted(this) && PermissionUtils.hasPhoneStatePermission(this)
            ) {
                if (!PermissionUtils.hasPhoneStatePermission(this)) {
                    PermissionUtils.requestPhoneStatePermission(this, REQ_PHONE)
                } else {
                    PermissionUtils.requestCallScreeningRole(this, REQ_ROLE)
                }
            }
        }
        binding.btnNotification.setOnClickListener {
            handlePermissionSwitch(
                binding.btnNotification,
                PermissionUtils.isNotificationPermissionGranted(this)
            ) {
                PermissionUtils.requestNotificationPermission(this, REQ_NOTIFICATIONS)
            }
        }
        binding.btnCallLogSms.setOnClickListener {
            handlePermissionSwitch(
                binding.btnCallLogSms,
                PermissionUtils.hasCallLogPermission(this) && PermissionUtils.hasSmsPermission(this)
            ) {
                PermissionUtils.requestCallLogAndSms(this, REQ_CALLLOG_SMS)
            }
        }
        binding.btnContinue.setOnClickListener {
            if (!PermissionUtils.allEssentialGranted(this)) {
                Toast.makeText(this, "Please enable all permissions for Guardian Mode.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            com.sentinel.ai.utils.OnboardingPrefs.setComplete(this, true)
            SentinelGuardianService.start(this)
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtons()
    }

    private fun updateButtons() {
        binding.btnAccessibility.applySwitchState(PermissionUtils.isAccessibilityEnabled(this))
        binding.btnMic.applySwitchState(PermissionUtils.hasMicPermission(this))
        binding.btnOverlay.applySwitchState(PermissionUtils.canDrawOverlays(this))
        binding.btnCallScreening.applySwitchState(
            PermissionUtils.isCallScreeningRoleGranted(this) && PermissionUtils.hasPhoneStatePermission(this)
        )
        binding.btnNotification.applySwitchState(PermissionUtils.isNotificationPermissionGranted(this))
        binding.btnCallLogSms.applySwitchState(
            PermissionUtils.hasCallLogPermission(this) && PermissionUtils.hasSmsPermission(this)
        )

        val allGranted = PermissionUtils.allEssentialGranted(this)
        binding.btnContinue.isEnabled = allGranted
        binding.btnContinue.alpha = if (allGranted) 1f else 0.6f
    }

    private fun SwitchMaterial.applySwitchState(granted: Boolean) {
        isChecked = granted
        isEnabled = !granted
    }

    private fun handlePermissionSwitch(
        switchView: SwitchMaterial,
        granted: Boolean,
        requestAction: () -> Unit
    ) {
        if (granted) {
            switchView.isChecked = true
            return
        }
        switchView.isChecked = false
        requestAction()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC || requestCode == REQ_NOTIFICATIONS || requestCode == REQ_PHONE || requestCode == REQ_CALLLOG_SMS) {
            updateButtons()
            if (requestCode == REQ_PHONE && PermissionUtils.hasPhoneStatePermission(this)) {
                PermissionUtils.requestCallScreeningRole(this, REQ_ROLE)
            }
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
        private const val REQ_PHONE = 103
        private const val REQ_CALLLOG_SMS = 104
    }
}
