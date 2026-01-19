package com.sentinel.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.sentinel.ai.R
import com.sentinel.ai.SentinelApp
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.LinkCheckResult
import com.sentinel.ai.security.LinkChecker
import com.sentinel.ai.security.SafetyLevel
import com.sentinel.ai.ui.navigation.BottomTab
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class CheckLinkActivity : BaseActivity() {

    private val checker by lazy { LinkChecker(this, OkHttpClient()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check_link)

        val input = findViewById<EditText>(R.id.inputUrl)
        val paste = findViewById<TextView>(R.id.btnPaste)
        val scanBtn = findViewById<MaterialButton>(R.id.btnScan)

        val statusTitle = findViewById<TextView>(R.id.txtLinkStatusTitle)
        val scoreView = findViewById<TextView>(R.id.txtLinkScore)
        val domainBody = findViewById<TextView>(R.id.txtDomainBody)
        val sslBody = findViewById<TextView>(R.id.txtSslBody)
        val historyBody = findViewById<TextView>(R.id.txtHistoryBody)
        val deductionsView = findViewById<TextView>(R.id.txtDeductions)

        paste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                input.setText(text)
                input.setSelection(text.length)
            } else {
                Toast.makeText(this, R.string.checklink_paste, Toast.LENGTH_SHORT).show()
            }
        }

        scanBtn.setOnClickListener {
            val url = input.text?.toString().orEmpty()
            lifecycleScope.launch {
                scanBtn.isEnabled = false
                scanBtn.text = getString(R.string.checklink_scanning)
                runCatching { checker.check(url) }
                    .onSuccess { result ->
                        updateUi(result, statusTitle, scoreView, domainBody, sslBody, historyBody, deductionsView)
                        SentinelApp.instance.database.eventDao().insert(
                            GuardianEvent(
                                source = getString(R.string.scan_event_source_web),
                                content = getString(R.string.scan_event_content_url_format, result.domain),
                                score = result.score,
                                riskLevel = toRiskLevel(result.status)
                            )
                        )
                    }
                    .onFailure {
                        Toast.makeText(
                            this@CheckLinkActivity,
                            it.message ?: getString(R.string.error_invalid_url),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                scanBtn.isEnabled = true
                scanBtn.text = getString(R.string.checklink_scan)
            }
        }
    }

    private fun updateUi(
        result: LinkCheckResult,
        statusTitle: TextView,
        scoreView: TextView,
        domainBody: TextView,
        sslBody: TextView,
        historyBody: TextView,
        deductionsView: TextView
    ) {
        val (titleText, colorRes) = when (result.status) {
            SafetyLevel.SAFE -> getString(R.string.checklink_safe_title) to R.color.splash_title
            SafetyLevel.CAUTION -> getString(R.string.checklink_caution_title) to R.color.home_accent_yellow
            SafetyLevel.DANGER -> getString(R.string.checklink_danger_title) to R.color.home_accent_red
            SafetyLevel.UNKNOWN -> getString(R.string.checklink_unknown_title) to R.color.home_muted
        }

        statusTitle.text = titleText
        statusTitle.setTextColor(ContextCompat.getColor(this, colorRes))

        scoreView.text = getString(R.string.checklink_score_format, result.score)

        val ageText = when {
            result.registrationDate != null -> formatRegistrationDate(result.registrationDate)
            result.domainAgeDays != null -> getString(R.string.checklink_age_days_format, result.domainAgeDays)
            else -> getString(R.string.checklink_age_unknown)
        }
        val countryText = result.country ?: getString(R.string.checklink_country_unknown)
        val ipText = result.resolvedIp ?: getString(R.string.checklink_ip_unknown)
        domainBody.text = getString(R.string.checklink_domain_body_format_ip, result.domain, ipText, ageText, countryText)

        sslBody.text = if (result.https) {
            val tls = result.tlsVersion ?: getString(R.string.checklink_ssl_unknown)
            val subject = result.certSubject ?: getString(R.string.checklink_ssl_unknown)
            val issuer = result.certIssuer ?: getString(R.string.checklink_ssl_unknown)
            getString(R.string.checklink_ssl_details, tls, subject, issuer)
        } else {
            getString(R.string.checklink_ssl_missing)
        }

        val issues = result.issues.takeIf { it.isNotEmpty() }
            ?.joinToString(getString(R.string.list_separator))
            ?: getString(R.string.checklink_no_issues)
        historyBody.text = issues.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

        val deductions = result.deductions.takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "-${it.points}: ${it.reason}" }
            ?: getString(R.string.checklink_no_deductions)
        deductionsView.text = deductions
    }

    override fun getCurrentTab(): BottomTab = BottomTab.SCAN

    private fun toRiskLevel(status: SafetyLevel): RiskLevel = when (status) {
        SafetyLevel.SAFE -> RiskLevel.SAFE
        SafetyLevel.CAUTION -> RiskLevel.WARNING
        SafetyLevel.DANGER -> RiskLevel.CRITICAL
        SafetyLevel.UNKNOWN -> RiskLevel.SAFE
    }

    private fun formatRegistrationDate(raw: String): String {
        return runCatching {
            val date = Instant.parse(raw).atZone(ZoneOffset.UTC).toLocalDate()
            date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) // DD-MM-YYYY
        }.getOrElse {
            val fallback = raw.substringBefore("T", raw)
            // Try reformat fallback if already yyyy-MM-dd
            runCatching {
                val parsed = DateTimeFormatter.ISO_LOCAL_DATE.parse(fallback)
                DateTimeFormatter.ofPattern("dd-MM-yyyy").format(parsed)
            }.getOrDefault(fallback)
        }
    }
}
