package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sentinel.ai.R
import com.sentinel.ai.databinding.ActivitySetupBinding
import com.sentinel.ai.service.SentinelGuardianService
import com.sentinel.ai.utils.ModelInitializer
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.ProfilePrefs

class SetupActivity : BaseLocalizedActivity() {

    private lateinit var binding: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ProfilePrefs.forceLightMode(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ModelInitializer.initialize(this)

        binding.btnCorePermissions.setOnClickListener {
            if (!PermissionUtils.hasCorePermissions(this)) {
                PermissionUtils.requestCorePermissions(this, REQ_CORE)
            }
        }
        binding.btnCallProtection.setOnClickListener {
            when {
                !PermissionUtils.hasPhoneStatePermission(this) ->
                    PermissionUtils.requestPhoneStatePermission(this, REQ_PHONE)
                !PermissionUtils.isCallScreeningRoleGranted(this) ->
                    PermissionUtils.requestCallScreeningRole(this, REQ_ROLE)
                !PermissionUtils.canDrawOverlays(this) ->
                    PermissionUtils.requestOverlayPermission(this)
            }
        }
        binding.btnBackgroundProtection.setOnClickListener {
            when {
                !PermissionUtils.isAccessibilityEnabled(this) ->
                    PermissionUtils.openAccessibilitySettings(this)
                !PermissionUtils.isBatteryOptimizationIgnored(this) ->
                    PermissionUtils.requestIgnoreBatteryOptimization(this)
            }
        }

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
        binding.btnBattery.setOnClickListener {
            handlePermissionSwitch(
                binding.btnBattery,
                PermissionUtils.isBatteryOptimizationIgnored(this)
            ) {
                PermissionUtils.requestIgnoreBatteryOptimization(this)
            }
        }
        binding.btnContinue.setOnClickListener {
            val allGranted = PermissionUtils.allEssentialGranted(this)
            if (!allGranted) {
                // Allow limited mode if at least minimal permissions granted
                if (PermissionUtils.hasMinimalGranted(this)) {
                    Toast.makeText(this, getString(R.string.setup_permissions_partial), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.setup_permissions_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            com.sentinel.ai.utils.OnboardingPrefs.setComplete(this, true)
            if (allGranted) SentinelGuardianService.start(this)
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtons()
    }

    private fun updateButtons() {
        val coreGranted = PermissionUtils.hasCorePermissions(this)
        val callGranted = PermissionUtils.hasCallProtectionPermissions(this)
        val backgroundGranted = PermissionUtils.hasBackgroundProtectionPermissions(this)
        binding.btnCorePermissions.applyGroupState(coreGranted)
        binding.btnCallProtection.applyGroupState(callGranted)
        binding.btnBackgroundProtection.applyGroupState(backgroundGranted)
        binding.statusCorePermissions.text = groupStatus(4, countCoreGranted())
        binding.statusCallProtection.text = groupStatus(3, countCallProtectionGranted())
        binding.statusBackgroundProtection.text = groupStatus(2, countBackgroundProtectionGranted())

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
        binding.btnBattery.applySwitchState(PermissionUtils.isBatteryOptimizationIgnored(this))

        val hasMinimal = PermissionUtils.hasMinimalGranted(this)
        val allGranted = PermissionUtils.allEssentialGranted(this)
        binding.btnContinue.isEnabled = hasMinimal || allGranted
        binding.btnContinue.alpha = if (hasMinimal || allGranted) 1f else 0.6f
    }

    private fun SwitchMaterial.applySwitchState(granted: Boolean) {
        isChecked = granted
        isEnabled = !granted
    }

    private fun MaterialButton.applyGroupState(granted: Boolean) {
        isEnabled = !granted
        alpha = if (granted) 0.72f else 1f
        text = getString(if (granted) R.string.permission_group_done else R.string.permission_group_action)
    }

    private fun groupStatus(total: Int, granted: Int): String {
        return if (granted >= total) {
            getString(R.string.permission_group_done)
        } else {
            getString(R.string.permission_group_missing_format, total - granted)
        }
    }

    private fun countCoreGranted(): Int =
        listOf(
            PermissionUtils.hasMicPermission(this),
            PermissionUtils.hasCallLogPermission(this),
            PermissionUtils.hasSmsPermission(this),
            PermissionUtils.isNotificationPermissionGranted(this)
        ).count { it }

    private fun countCallProtectionGranted(): Int =
        listOf(
            PermissionUtils.hasPhoneStatePermission(this),
            PermissionUtils.isCallScreeningRoleGranted(this),
            PermissionUtils.canDrawOverlays(this)
        ).count { it }

    private fun countBackgroundProtectionGranted(): Int =
        listOf(
            PermissionUtils.isAccessibilityEnabled(this),
            PermissionUtils.isBatteryOptimizationIgnored(this)
        ).count { it }

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
        if (requestCode == REQ_MIC || requestCode == REQ_NOTIFICATIONS || requestCode == REQ_PHONE || requestCode == REQ_CALLLOG_SMS || requestCode == REQ_CORE) {
            updateButtons()
            if (requestCode == REQ_PHONE && PermissionUtils.hasPhoneStatePermission(this)) {
                PermissionUtils.requestCallScreeningRole(this, REQ_ROLE)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_ROLE -> updateButtons()
        }
    }

    companion object {
        private const val REQ_MIC = 100
        private const val REQ_NOTIFICATIONS = 101
        private const val REQ_ROLE = 102
        private const val REQ_PHONE = 103
        private const val REQ_CALLLOG_SMS = 104
        private const val REQ_BATTERY = 105
        private const val REQ_CORE = 106
    }
}
