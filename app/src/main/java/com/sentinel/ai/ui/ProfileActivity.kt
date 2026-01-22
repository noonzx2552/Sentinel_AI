package com.sentinel.ai.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.switchmaterial.SwitchMaterial
import com.sentinel.ai.R
import com.sentinel.ai.ui.navigation.BottomTab
import com.sentinel.ai.utils.LanguageManager
import com.sentinel.ai.utils.ProfilePrefs
import kotlinx.coroutines.launch

class ProfileActivity : BaseActivity() {

    private var nameView: TextView? = null
    private var languageValue: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val debugRow = findViewById<View>(R.id.profileDebugRow)
        val versionLabel = findViewById<TextView>(R.id.profileVersionLabel)
        nameView = findViewById(R.id.profileName)
        languageValue = findViewById(R.id.profileLanguageValue)
        val pushToggle = findViewById<SwitchMaterial>(R.id.profilePushToggle)
        val darkToggle = findViewById<SwitchMaterial>(R.id.profileDarkModeToggle)

        // Load persisted values using ProfilePrefs
        applySavedName()
        applyLanguageLabel()
        darkToggle?.isChecked = ProfilePrefs.isDarkMode(this)

        // Allow editing display name
        nameView?.setOnClickListener { showEditNameSheet() }
        findViewById<View>(R.id.profileAvatar)?.setOnClickListener { showEditNameSheet() }

        // Toggles: persist state
        pushToggle?.setOnCheckedChangeListener { _, checked ->
            Toast.makeText(
                this,
                getString(if (checked) R.string.profile_push_enabled else R.string.profile_push_disabled),
                Toast.LENGTH_SHORT
            ).show()
        }
        
        darkToggle?.setOnCheckedChangeListener { _, checked ->
            ProfilePrefs.setDarkMode(this, checked)
            Toast.makeText(
                this,
                getString(if (checked) R.string.profile_dark_enabled else R.string.profile_dark_disabled),
                Toast.LENGTH_SHORT
            ).show()
            // Theme is applied inside ProfilePrefs.setDarkMode
        }

        findViewById<View>(R.id.rowProfileEditName)?.setOnClickListener { showEditNameSheet() }
        findViewById<View>(R.id.rowProfileLanguage)?.setOnClickListener { showLanguageSheet() }



        // Sync row taps with toggles for accessibility
        findViewById<View>(R.id.rowProfilePush)?.setOnClickListener { pushToggle?.toggle() }
        findViewById<View>(R.id.rowProfileDarkMode)?.setOnClickListener { darkToggle?.toggle() }

        debugRow?.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
            overridePendingTransition(R.anim.fade_in_scale, R.anim.fade_out)
        }

        versionLabel?.setOnLongClickListener {
            DebugSettings.toggleDebug()
            true
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DebugSettings.isDebugEnabled.collect { enabled ->
                    debugRow?.visibility = if (enabled) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh name in case it was updated elsewhere.
        applySavedName()
        applyLanguageLabel()
    }

    override fun getCurrentTab(): BottomTab = BottomTab.PROFILE

    private fun applySavedName() {
        nameView?.text = ProfilePrefs.getName(this)
    }

    private fun applyLanguageLabel() {
        val label = when (LanguageManager.getLanguage(this)) {
            LanguageManager.LANG_TH -> getString(R.string.language_thai)
            else -> getString(R.string.language_english)
        }
        languageValue?.text = label
    }

    private fun showEditNameSheet() {
        val sheet = EditNameBottomSheet()
        sheet.onNameSaved = { newName ->
            nameView?.text = newName
            Toast.makeText(this, getString(R.string.profile_name_updated), Toast.LENGTH_SHORT).show()
        }
        sheet.show(supportFragmentManager, "edit_name")
    }

    private fun showLanguageSheet() {
        val sheet = LanguageBottomSheet()
        sheet.onLanguageSelected = {
            applyLanguageLabel()
        }
        sheet.show(supportFragmentManager, "language_sheet")
    }
}
