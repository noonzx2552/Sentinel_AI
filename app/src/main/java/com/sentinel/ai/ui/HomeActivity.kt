package com.sentinel.ai.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sentinel.ai.R
import com.sentinel.ai.SentinelApp
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.GuardianEventStore
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.service.SentinelGuardianService
import com.sentinel.ai.ui.navigation.BottomTab
import com.sentinel.ai.ui.navigation.NavStateStore
import com.sentinel.ai.utils.EventLocalization
import com.sentinel.ai.utils.PermissionUtils
import com.sentinel.ai.utils.ProfilePrefs
import kotlinx.coroutines.launch
import java.util.Locale
import com.google.android.material.switchmaterial.SwitchMaterial
import android.text.format.DateUtils
import com.sentinel.ai.utils.ProtectionMode
import com.sentinel.ai.utils.ProtectionPrefs

class HomeActivity : BaseActivity() {
    private var currentStatus: RiskLevel = RiskLevel.SAFE
    private var latestThreat: GuardianEvent? = null

    private lateinit var threatPanel: View
    private lateinit var safePanel: View
    private lateinit var threatSummary: TextView
    private lateinit var threatTypeValue: TextView
    private lateinit var threatSeverityValue: TextView
    private lateinit var threatSeverityDot: View
    private lateinit var threatSourceValue: TextView
    private var protectionToggle: SwitchMaterial? = null
    private lateinit var recentList: LinearLayout
    private lateinit var recentEmpty: TextView
    private lateinit var modeMonitor: View
    private lateinit var modeWarning: View
    private lateinit var modeAuto: View
    private lateinit var modeMonitorRadio: View
    private lateinit var modeWarningRadio: View
    private lateinit var modeAutoRadio: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        threatPanel = findViewById(R.id.homeThreatPanel)
        safePanel = findViewById(R.id.homeSafePanel)
        threatSummary = findViewById(R.id.homeThreatSummary)
        threatTypeValue = findViewById(R.id.homeThreatTypeValue)
        threatSeverityValue = findViewById(R.id.homeThreatSeverityValue)
        threatSeverityDot = findViewById(R.id.homeThreatSeverityDot)
        threatSourceValue = findViewById(R.id.homeThreatSourceValue)
        protectionToggle = findViewById(R.id.homeProtectionToggle)
        recentList = findViewById(R.id.homeRecentList)
        recentEmpty = findViewById(R.id.homeRecentEmpty)
        modeMonitor = findViewById(R.id.homeModeMonitor)
        modeWarning = findViewById(R.id.homeModeWarning)
        modeAuto = findViewById(R.id.homeModeAuto)
        modeMonitorRadio = findViewById(R.id.homeModeMonitorRadio)
        modeWarningRadio = findViewById(R.id.homeModeWarningRadio)
        modeAutoRadio = findViewById(R.id.homeModeAutoRadio)

        findViewById<View>(R.id.quickCheckLink).setOnClickListener {
            goTo(Intent(this, CheckLinkActivity::class.java))
        }
        findViewById<View>(R.id.quickCheckNumber).setOnClickListener {
            goTo(Intent(this, CheckNumberActivity::class.java))
        }
        findViewById<View>(R.id.quickCallLog).setOnClickListener {
            goTo(Intent(this, CallLogScanActivity::class.java))
        }
        findViewById<View>(R.id.homeStatusTitle)?.setOnLongClickListener {
            NavStateStore.toggleDanger()
            true
        }
        findViewById<View>(R.id.homeInfoButton).setOnClickListener {
            showCredits()
        }
        findViewById<View>(R.id.homeRecentSeeAll).setOnClickListener {
            goTo(Intent(this, ActivityLogActivity::class.java))
        }

        GuardianEventStore.observeStatus().observe(this) { status ->
            currentStatus = status
            renderThreatState()
        }

        GuardianEventStore.observeEvents().observe(this) { events ->
            latestThreat = events.firstOrNull { it.riskLevel == RiskLevel.CRITICAL } ?: events.firstOrNull()
            renderThreatState()
        }

        protectionToggle?.apply {
            isChecked = ProtectionPrefs.isEnabled(this@HomeActivity)
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    if (!PermissionUtils.allEssentialGranted(this@HomeActivity)) {
                        isChecked = false
                        Toast.makeText(
                            this@HomeActivity,
                            getString(R.string.setup_permissions_required),
                            Toast.LENGTH_SHORT
                        ).show()
                        goTo(Intent(this@HomeActivity, SetupActivity::class.java))
                        return@setOnCheckedChangeListener
                    }
                    ProtectionPrefs.setEnabled(this@HomeActivity, true)
                    SentinelGuardianService.start(this@HomeActivity)
                } else {
                    ProtectionPrefs.setEnabled(this@HomeActivity, false)
                    SentinelGuardianService.stop(this@HomeActivity)
                }
            }
        }

        modeMonitor.setOnClickListener { setProtectionMode(ProtectionMode.MONITORING) }
        modeWarning.setOnClickListener { setProtectionMode(ProtectionMode.WARNING) }
        modeAuto.setOnClickListener { setProtectionMode(ProtectionMode.AUTO) }
        applyProtectionMode(ProtectionPrefs.getMode(this))

        val eventDao = SentinelApp.instance.database.eventDao()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                eventDao.getAllEvents().collect { events ->
                    renderRecent(events)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val name = ProfilePrefs.getName(this)
        findViewById<TextView>(R.id.homeGreeting)?.text = getString(R.string.home_greeting_format, name)
        protectionToggle?.isChecked = ProtectionPrefs.isEnabled(this)
        applyProtectionMode(ProtectionPrefs.getMode(this))
    }

    override fun getCurrentTab(): BottomTab = BottomTab.HOME

    private fun goTo(intent: Intent, finishSelf: Boolean = false) {
        startActivity(intent)
        if (finishSelf) finish()
        overridePendingTransition(R.anim.fade_in_scale, R.anim.fade_out)
    }

    private fun renderThreatState() {
        val isThreat = currentStatus == RiskLevel.CRITICAL
        threatPanel.visibility = if (isThreat) View.VISIBLE else View.GONE
        safePanel.visibility = if (isThreat) View.GONE else View.VISIBLE
        NavStateStore.setDanger(isThreat)

        if (!isThreat) return

        val event = latestThreat
        if (event == null) {
            threatSummary.text = getString(R.string.home_threat_summary_fallback)
            threatTypeValue.text = getString(R.string.home_threat_type_generic)
            threatSourceValue.text = getString(R.string.home_threat_source_system)
            setSeverityStyle(currentStatus)
            return
        }

        val localized = EventLocalization.localizeEvent(this, event.source, event.content)
        val typeLabel = threatTypeFor(localized.source, localized.content)
        val sourceLabel = threatSourceFor(localized.source, localized.content)
        val highlight = extractHighlight(localized.source, localized.content)
        threatTypeValue.text = typeLabel
        threatSourceValue.text = sourceLabel
        threatSummary.text = buildSummary(typeLabel, highlight)
        setSeverityStyle(currentStatus)
    }

    private fun setSeverityStyle(level: RiskLevel) {
        val (labelRes, colorRes) = when (level) {
            RiskLevel.CRITICAL -> R.string.home_threat_severity_high to R.color.home_accent_red
            RiskLevel.WARNING -> R.string.home_threat_severity_medium to R.color.home_accent_yellow
            RiskLevel.SAFE -> R.string.home_threat_severity_low to R.color.bottom_nav_active_green
        }
        threatSeverityValue.text = getString(labelRes)
        val color = ContextCompat.getColor(this, colorRes)
        threatSeverityValue.setTextColor(color)
        ViewCompat.setBackgroundTintList(threatSeverityDot, ColorStateList.valueOf(color))
    }

    private fun threatTypeFor(source: String, content: String): String {
        val text = "$source $content".lowercase(Locale.getDefault())
        return when {
            text.contains("phish") || text.contains("link") -> getString(R.string.home_threat_type_phishing)
            text.contains("sms") -> getString(R.string.home_threat_type_sms)
            text.contains("call") -> getString(R.string.home_threat_type_call)
            text.contains("scan") || text.contains("qr") -> getString(R.string.home_threat_type_scan)
            else -> getString(R.string.home_threat_type_generic)
        }
    }

    private fun threatSourceFor(source: String, content: String): String {
        val text = "$source $content".lowercase(Locale.getDefault())
        return when {
            text.contains("sms") -> getString(R.string.home_threat_source_sms)
            text.contains("call") -> getString(R.string.home_threat_source_call)
            text.contains("link") || text.contains("web") || text.contains("http") -> getString(R.string.home_threat_source_web)
            text.contains("scan") || text.contains("qr") -> getString(R.string.home_threat_source_scan)
            else -> getString(R.string.home_threat_source_system)
        }
    }

    private fun extractHighlight(source: String, content: String): String? {
        val text = "$content $source"
        val urlMatch = Regex("([A-Za-z0-9.-]+\\.[A-Za-z]{2,})").find(text)
        if (urlMatch != null) return urlMatch.value
        val phoneMatch = Regex("(\\+?\\d[\\d\\s\\-()]{6,})").find(text)
        return phoneMatch?.value?.trim()
    }

    private fun buildSummary(typeLabel: String, highlight: String?): CharSequence {
        if (highlight.isNullOrBlank()) {
            return getString(R.string.home_threat_summary_fallback)
        }
        val raw = getString(R.string.home_threat_summary_format, typeLabel, highlight)
        val start = raw.indexOf(highlight)
        if (start < 0) return raw
        return SpannableString(raw).apply {
            val end = start + highlight.length
            setSpan(ForegroundColorSpan(ContextCompat.getColor(this@HomeActivity, R.color.home_accent_red)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun setProtectionMode(mode: ProtectionMode) {
        ProtectionPrefs.setMode(this, mode)
        applyProtectionMode(mode)
    }

    private fun applyProtectionMode(mode: ProtectionMode) {
        updateModeRow(modeMonitor, modeMonitorRadio, mode == ProtectionMode.MONITORING)
        updateModeRow(modeWarning, modeWarningRadio, mode == ProtectionMode.WARNING)
        updateModeRow(modeAuto, modeAutoRadio, mode == ProtectionMode.AUTO)
    }

    private fun updateModeRow(row: View, radio: View, selected: Boolean) {
        row.background = if (selected) {
            ContextCompat.getDrawable(this, R.drawable.home_mode_selected_bg)
        } else {
            null
        }
        radio.setBackgroundResource(if (selected) R.drawable.home_radio_on else R.drawable.home_radio_off)
    }

    private fun showCredits() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_credits_bottom_sheet, null)
        view.findViewById<View>(R.id.btnCloseCredits).setOnClickListener {
            dialog.dismiss()
        }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun renderRecent(events: List<GuardianEvent>) {
        val latest = events.take(2)
        recentList.removeAllViews()
        if (latest.isEmpty()) {
            recentEmpty.isVisible = true
            recentList.isVisible = false
            return
        }
        recentEmpty.isVisible = false
        recentList.isVisible = true
        val inflater = LayoutInflater.from(this)
        latest.forEach { event ->
            val row = inflater.inflate(R.layout.item_home_recent, recentList, false)
            val iconBg = row.findViewById<FrameLayout>(R.id.homeRecentIconBg)
            val icon = row.findViewById<ImageView>(R.id.homeRecentIcon)
            val title = row.findViewById<TextView>(R.id.homeRecentTitle)
            val body = row.findViewById<TextView>(R.id.homeRecentBody)
            val time = row.findViewById<TextView>(R.id.homeRecentTime)

            val localized = EventLocalization.localizeEvent(this, event.source, event.content)
            title.text = localized.source
            body.text = localized.content.removePrefix("URL: ").removePrefix("URL:")
            time.text = DateUtils.getRelativeTimeSpanString(
                event.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )

            val (bgRes, tintRes) = when (event.riskLevel) {
                RiskLevel.CRITICAL -> R.drawable.home_badge_red_bg to R.color.home_accent_red
                RiskLevel.WARNING -> R.drawable.home_badge_yellow_bg to R.color.home_accent_yellow
                RiskLevel.SAFE -> R.drawable.home_badge_green_bg to R.color.bottom_nav_active_green
            }
            iconBg.setBackgroundResource(bgRes)
            icon.setColorFilter(ContextCompat.getColor(this, tintRes))

            recentList.addView(row)
        }
    }
}
