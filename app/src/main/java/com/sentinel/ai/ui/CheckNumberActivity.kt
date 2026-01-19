package com.sentinel.ai.ui

import android.os.Bundle
import android.view.View
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.sentinel.ai.R
import com.sentinel.ai.SentinelApp
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.NumberCheckResult
import com.sentinel.ai.security.NumberChecker
import com.sentinel.ai.security.SafetyLevel
import com.sentinel.ai.ui.navigation.BottomTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CheckNumberActivity : BaseActivity() {

    private lateinit var checker: NumberChecker
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var isReportExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check_number)
        checker = NumberChecker(this)

        val recentList = findViewById<LinearLayout>(R.id.checkNumberRecentList)
        val recentEmpty = findViewById<TextView>(R.id.checkNumberRecentEmpty)
        val input = findViewById<EditText>(R.id.inputNumber)
        val clear = findViewById<ImageView>(R.id.btnClearNumber)
        val checkBtn = findViewById<MaterialButton>(R.id.btnCheckNumber)

        val statusTag = findViewById<TextView>(R.id.txtNumberStatusTag)
        val resultNumber = findViewById<TextView>(R.id.txtResultNumber)
        val ratingValue = findViewById<TextView>(R.id.txtRatingValue)
        val ratingNote = findViewById<TextView>(R.id.txtRatingNote)
        val carrierValue = findViewById<TextView>(R.id.txtCarrierValue)
        val locationValue = findViewById<TextView>(R.id.txtLocationValue)
        val reportsValue = findViewById<TextView>(R.id.txtReportsValue)
        val reportHeader = findViewById<View>(R.id.reportHeader)
        val reportHeaderText = findViewById<TextView>(R.id.txtReportHeader)
        val reportCountText = findViewById<TextView>(R.id.txtReportCount)
        val reportSummaryText = findViewById<TextView>(R.id.txtReportSummary)
        val reportChevron = findViewById<ImageView>(R.id.imgReportChevron)
        val reportDetailsContainer = findViewById<View>(R.id.reportDetailsContainer)
        val reportDetailsList = findViewById<LinearLayout>(R.id.reportDetailsList)
        val debugOutput = findViewById<TextView>(R.id.txtDebugOutput)
        val ratingBarContainer = findViewById<View>(R.id.ratingBarContainer)
        val ratingBarFill = findViewById<View>(R.id.ratingBarFill)
        val resultTime = findViewById<TextView>(R.id.txtResultTime)

        clear.setOnClickListener { input.text?.clear() }
        val toggleReport = View.OnClickListener {
            if (!reportHeader.isEnabled) return@OnClickListener
            isReportExpanded = !isReportExpanded
            applyReportExpansion(reportDetailsContainer, reportChevron)
        }
        reportHeader.setOnClickListener(toggleReport)

        lifecycleScope.launch {
            SentinelApp.instance.database.eventDao().getAllEvents().collect { events ->
                renderRecentScans(events, recentList, recentEmpty)
            }
        }

        checkBtn.setOnClickListener {
            val number = input.text?.toString().orEmpty()
            lifecycleScope.launch {
                checkBtn.isEnabled = false
                checkBtn.text = getString(R.string.checknumber_checking)
                try {
                    val result = checker.check(number)
                    val displayNumber = result.displayNumber
                        .ifBlank { result.formattedE164 }
                        .ifBlank { number }
                    updateUi(
                        result,
                        statusTag,
                        resultNumber,
                        ratingValue,
                        ratingNote,
                        carrierValue,
                        locationValue,
                        reportsValue,
                        reportHeader,
                        reportHeaderText,
                        reportCountText,
                        reportSummaryText,
                        reportChevron,
                        reportDetailsContainer,
                        reportDetailsList,
                        debugOutput,
                        ratingBarContainer,
                        ratingBarFill,
                        resultTime
                    )
                    withContext(Dispatchers.IO) {
                        SentinelApp.instance.database.eventDao().insert(
                            GuardianEvent(
                                source = getString(R.string.scan_event_source_number),
                                content = getString(R.string.scan_event_content_phone_format, displayNumber),
                                score = result.score,
                                riskLevel = toRiskLevel(result.status)
                            )
                        )
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        this@CheckNumberActivity,
                        e.message ?: getString(R.string.error_invalid_number),
                        Toast.LENGTH_LONG
                    ).show()
                } finally {
                    checkBtn.isEnabled = true
                    checkBtn.text = getString(R.string.checknumber_action)
                }
            }
        }
    }

    private fun updateUi(
        result: NumberCheckResult,
        statusTag: TextView,
        resultNumber: TextView,
        ratingValue: TextView,
        ratingNote: TextView,
        carrierValue: TextView,
        locationValue: TextView,
        reportsValue: TextView,
        reportHeader: View,
        reportHeaderText: TextView,
        reportCountText: TextView,
        reportSummaryText: TextView,
        reportChevron: ImageView,
        reportDetailsContainer: View,
        reportDetailsList: LinearLayout,
        debugOutput: TextView,
        ratingBarContainer: View,
        ratingBarFill: View,
        resultTime: TextView
    ) {
        val (tagText, tagColor) = when (result.status) {
            SafetyLevel.SAFE -> getString(R.string.checknumber_safe_tag) to R.color.bottom_nav_active_green
            SafetyLevel.CAUTION -> getString(R.string.checknumber_caution_tag) to R.color.home_accent_yellow
            SafetyLevel.DANGER -> getString(R.string.checknumber_danger_tag) to R.color.home_accent_red
            SafetyLevel.UNKNOWN -> getString(R.string.checknumber_unknown_tag) to R.color.home_muted
        }
        statusTag.text = tagText
        statusTag.setTextColor(ContextCompat.getColor(this, tagColor))

        resultNumber.text = result.displayNumber.ifBlank { result.formattedE164 }
        ratingValue.text = getString(R.string.checknumber_rating_value_format, result.score)
        val ratingColor = if (result.score < LOW_SCORE_THRESHOLD) R.color.home_accent_red else R.color.splash_logo_bg
        ratingValue.setTextColor(ContextCompat.getColor(this, ratingColor))
        ratingNote.text = when (result.status) {
            SafetyLevel.SAFE -> getString(R.string.checknumber_note_safe)
            SafetyLevel.CAUTION -> getString(R.string.checknumber_note_caution)
            SafetyLevel.DANGER -> getString(R.string.checknumber_note_danger)
            SafetyLevel.UNKNOWN -> getString(R.string.checknumber_note_unknown)
        }

        carrierValue.text = formatCarrierDisplay(result.carrier)
            ?: getString(R.string.checknumber_carrier_unknown)
        val regionDisplay = buildString {
            result.region?.let { append(it) }
            if (!result.countryName.isNullOrBlank() && result.countryName != result.region) {
                if (isNotEmpty()) append(getString(R.string.list_separator))
                append(result.countryName)
            }
        }.ifBlank { getString(R.string.checknumber_location_unknown) }
        locationValue.text = regionDisplay

        val reportCount = result.reportCount
        val reportText = if (reportCount > 0) {
            getString(R.string.checknumber_reports_format, reportCount)
        } else {
            getString(R.string.checknumber_reports_none)
        }
        reportsValue.text = reportText
        val reportColor = if (reportCount > 0) R.color.home_accent_red else R.color.splash_title
        reportsValue.setTextColor(ContextCompat.getColor(this, reportColor))

        reportHeaderText.text = getString(R.string.checknumber_reports_panel_title)
        reportCountText.text = if (reportCount > 0) {
            getString(R.string.checknumber_reports_count_format, reportCount)
        } else {
            getString(R.string.checknumber_reports_count_zero)
        }
        reportCountText.setTextColor(ContextCompat.getColor(this, if (reportCount > 0) R.color.home_accent_red else R.color.home_muted))
        val summaryText = buildReportSummary(reportCount, result.reportDetails)
        reportSummaryText.text = summaryText
        reportSummaryText.setTextColor(ContextCompat.getColor(this, if (reportCount > 0) R.color.splash_title else R.color.home_muted))

        if (reportCount == 0) {
            isReportExpanded = false
        }
        reportHeader.isEnabled = reportCount > 0
        reportDetailsContainer.visibility = if (isReportExpanded && reportCount > 0) View.VISIBLE else View.GONE
        reportChevron.rotation = if (reportDetailsContainer.visibility == View.VISIBLE) 0f else 180f
        reportChevron.alpha = if (reportCount > 0) 1f else 0.4f

        reportDetailsList.removeAllViews()
        if (result.reportDetails.isEmpty()) {
            reportDetailsList.addView(buildReportBlock(getString(R.string.checknumber_reports_none_detail)))
        } else {
            result.reportDetails.forEach { detail ->
                reportDetailsList.addView(buildReportBlock(formatReportBlock(detail)))
            }
        }

        debugOutput.text = ""
        debugOutput.visibility = View.GONE

        ratingBarContainer.post {
            val width = ratingBarContainer.width
            val percent = (result.score / 100f).coerceIn(0f, 1f)
            val newWidth = (width * percent).toInt().coerceAtLeast(ratingBarContainer.height)
            val params = ratingBarFill.layoutParams
            params.width = newWidth
            ratingBarFill.layoutParams = params
        }

        resultTime.text = getString(R.string.checknumber_results_time_format, timeFormat.format(Date()))
    }

    override fun getCurrentTab(): BottomTab = BottomTab.SCAN

    private fun toRiskLevel(status: SafetyLevel): RiskLevel = when (status) {
        SafetyLevel.SAFE -> RiskLevel.SAFE
        SafetyLevel.CAUTION -> RiskLevel.WARNING
        SafetyLevel.DANGER -> RiskLevel.CRITICAL
        SafetyLevel.UNKNOWN -> RiskLevel.SAFE
    }

    private fun formatCarrierDisplay(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim()
        return when {
            normalized.contains("Advanced Wireless Network", true) -> "AIS"
            normalized.contains("AIS", true) -> "AIS"
            normalized.contains("Total Access Communication", true) -> "DTAC"
            normalized.contains("DTAC", true) -> "DTAC"
            normalized.contains("True", true) -> "TRUE"
            normalized.contains("TrueMove", true) -> "TRUE"
            normalized.contains("National Telecom", true) -> "NT"
            normalized.contains("CAT", true) -> "NT"
            normalized.contains("TOT", true) -> "NT"
            normalized.contains("Google", true) -> "CLOUD_GOOGLE"
            normalized.contains("Amazon", true) -> "CLOUD_AWS"
            normalized.contains("Microsoft", true) -> "CLOUD_AZURE"
            else -> normalized
        }
    }

    private fun applyReportExpansion(container: View, chevron: ImageView) {
        container.visibility = if (isReportExpanded) View.VISIBLE else View.GONE
        chevron.rotation = if (isReportExpanded) 0f else 180f
    }

    private fun buildReportSummary(reportCount: Int, reportDetails: List<String>): String {
        if (reportCount <= 0) {
            return getString(R.string.checknumber_reports_summary_none)
        }
        val primary = extractPrimaryReportLine(reportDetails)
        return if (primary.isNullOrBlank()) {
            getString(R.string.checknumber_reports_summary_count_format, reportCount)
        } else {
            getString(R.string.checknumber_reports_summary_format, reportCount, primary)
        }
    }

    private fun extractPrimaryReportLine(reportDetails: List<String>): String? {
        if (reportDetails.isEmpty()) return null
        val lines = reportDetails.first()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val preferredPrefixes = listOf("สินค้า:", "เพจขายของ:", "รายละเอียด:")
        val skipPrefixes = listOf("ลำดับ:", "เลขรายงาน:", "วันที่:", "จำนวนเงิน:")

        val preferred = lines.firstOrNull { line ->
            preferredPrefixes.any { prefix -> line.startsWith(prefix) }
        }
        if (!preferred.isNullOrBlank()) return preferred

        val fallback = lines.firstOrNull { line ->
            skipPrefixes.none { prefix -> line.startsWith(prefix) }
        }
        return fallback ?: lines.firstOrNull()
    }

    private fun buildReportBlock(text: String): View {
        val block = layoutInflater.inflate(R.layout.item_report_block, null)
        val detailView = block.findViewById<TextView>(R.id.reportBlockText)
        detailView.text = text
        detailView.setOnClickListener {
            val expanded = detailView.tag as? Boolean ?: false
            val nextExpanded = !expanded
            detailView.tag = nextExpanded
            detailView.maxLines = if (nextExpanded) Int.MAX_VALUE else 2
            detailView.ellipsize = if (nextExpanded) null else android.text.TextUtils.TruncateAt.END
        }
        // Apply LayoutParams with marginBottom to add spacing between report blocks
        val marginBottomPx = (8 * resources.displayMetrics.density).toInt()
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, marginBottomPx)
        }
        block.layoutParams = params
        return block
    }

    private fun formatReportBlock(detail: String): String {
        val lines = detail.lines()
            .filter { it.isNotBlank() }
            .filter { !it.contains("ดูรายละเอียด") }
        return lines.joinToString("\n")
    }

    private fun renderRecentScans(
        events: List<GuardianEvent>,
        container: LinearLayout,
        emptyView: TextView
    ) {
        val recent = events
            .filter { it.source == getString(R.string.scan_event_source_number) }
            .sortedByDescending { it.timestamp }
            .take(3)

        container.removeAllViews()
        if (recent.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE

        val inflater = LayoutInflater.from(this)
        recent.forEach { event ->
            val row = inflater.inflate(R.layout.item_checknumber_recent, container, false)
            val title = row.findViewById<TextView>(R.id.recentTitle)
            val body = row.findViewById<TextView>(R.id.recentBody)
            val tag = row.findViewById<TextView>(R.id.recentTag)
            val iconBadge = row.findViewById<View>(R.id.recentIconBadge)
            val icon = row.findViewById<ImageView>(R.id.recentIcon)

            title.text = riskLabel(event.riskLevel)
            body.text = stripPhonePrefix(event.content)
            tag.text = riskTagText(event.riskLevel)

            when (event.riskLevel) {
                RiskLevel.CRITICAL -> {
                    tag.setTextColor(ContextCompat.getColor(this, R.color.home_accent_red))
                    tag.background = ContextCompat.getDrawable(this, R.drawable.checknumber_tag_unsafe_bg)
                    iconBadge.background = ContextCompat.getDrawable(this, R.drawable.home_badge_red_bg)
                    icon.setColorFilter(ContextCompat.getColor(this, R.color.home_accent_red))
                }
                RiskLevel.WARNING -> {
                    tag.setTextColor(ContextCompat.getColor(this, R.color.home_accent_yellow))
                    tag.background = ContextCompat.getDrawable(this, R.drawable.checknumber_tag_caution_bg)
                    iconBadge.background = ContextCompat.getDrawable(this, R.drawable.home_badge_yellow_bg)
                    icon.setColorFilter(ContextCompat.getColor(this, R.color.home_accent_yellow))
                }
                RiskLevel.SAFE -> {
                    tag.setTextColor(ContextCompat.getColor(this, R.color.bottom_nav_active_green))
                    tag.background = ContextCompat.getDrawable(this, R.drawable.checknumber_tag_safe_bg)
                    iconBadge.background = ContextCompat.getDrawable(this, R.drawable.home_badge_green_bg)
                    icon.setColorFilter(ContextCompat.getColor(this, R.color.bottom_nav_active_green))
                }
            }
            container.addView(row)
        }
    }

    private fun riskLabel(level: RiskLevel): String = when (level) {
        RiskLevel.CRITICAL -> getString(R.string.checknumber_recent_label_critical)
        RiskLevel.WARNING -> getString(R.string.checknumber_recent_label_warning)
        RiskLevel.SAFE -> getString(R.string.checknumber_recent_label_safe)
    }

    private fun riskTagText(level: RiskLevel): String = when (level) {
        RiskLevel.CRITICAL -> getString(R.string.checknumber_recent_tag_unsafe)
        RiskLevel.WARNING -> getString(R.string.checknumber_recent_tag_caution)
        RiskLevel.SAFE -> getString(R.string.checknumber_recent_tag_safe)
    }

    private fun stripPhonePrefix(content: String): String {
        val normalized = content.trim()
        return if (normalized.startsWith("Phone:", true)) {
            normalized.substringAfter("Phone:").trim()
        } else {
            normalized
        }
    }

    companion object {
        private const val LOW_SCORE_THRESHOLD = 50
    }
}
