package com.sentinel.ai.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sentinel.ai.R
import com.sentinel.ai.databinding.ActivitySetupBinding
import com.sentinel.ai.service.SentinelGuardianService
import com.sentinel.ai.utils.MediaProjectionHolder
import com.sentinel.ai.utils.MediaProjectionStore
import com.sentinel.ai.utils.ModelInitializer
import com.sentinel.ai.utils.PermissionUtils

class SetupActivity : BaseLocalizedActivity() {

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
        binding.btnBattery.setOnClickListener {
            handlePermissionSwitch(
                binding.btnBattery,
                PermissionUtils.isBatteryOptimizationIgnored(this)
            ) {
                PermissionUtils.requestIgnoreBatteryOptimization(this)
            }
        }
        binding.btnAudioCapture.setOnClickListener {
            handlePermissionSwitch(
                binding.btnAudioCapture,
                MediaProjectionHolder.isReady()
            ) {
                requestAudioCapture()
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

        binding.btnSkip.setOnClickListener {
            com.sentinel.ai.utils.OnboardingPrefs.setComplete(this, true)
            Toast.makeText(this, getString(R.string.setup_limited_mode_note), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    private fun requestAudioCapture() {
        try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.permission_audio_capture_error), Toast.LENGTH_SHORT).show()
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
        binding.btnBattery.applySwitchState(PermissionUtils.isBatteryOptimizationIgnored(this))
        binding.btnAudioCapture.applySwitchState(MediaProjectionHolder.isReady())

        val hasMinimal = PermissionUtils.hasMinimalGranted(this)
        val allGranted = PermissionUtils.allEssentialGranted(this)
        binding.btnContinue.isEnabled = hasMinimal || allGranted
        binding.btnContinue.alpha = if (hasMinimal || allGranted) 1f else 0.6f
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
        when (requestCode) {
            REQ_ROLE -> updateButtons()
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    try {
                        val mp = mgr.getMediaProjection(resultCode, data)
                        MediaProjectionHolder.store(mp)
                        MediaProjectionStore.save(resultCode, data)  // keep for Android 10-13 reuse
                        SentinelGuardianService.startProjectionMode(this)
                        Toast.makeText(this, getString(R.string.permission_audio_capture_granted), Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, getString(R.string.permission_audio_capture_error), Toast.LENGTH_SHORT).show()
                    }
                }
                updateButtons()
            }
        }
    }

    companion object {
        private const val REQ_MIC = 100
        private const val REQ_NOTIFICATIONS = 101
        private const val REQ_ROLE = 102
        private const val REQ_PHONE = 103
        private const val REQ_CALLLOG_SMS = 104
        private const val REQ_BATTERY = 105
        private const val REQ_PROJECTION = 106
    }
}
