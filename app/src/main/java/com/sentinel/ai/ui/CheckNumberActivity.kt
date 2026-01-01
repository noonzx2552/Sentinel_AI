package com.sentinel.ai.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.sentinel.ai.R
import com.sentinel.ai.security.NumberCheckResult
import com.sentinel.ai.security.NumberChecker
import com.sentinel.ai.security.SafetyLevel
import com.sentinel.ai.ui.navigation.BottomTab
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CheckNumberActivity : BaseActivity() {

    private lateinit var checker: NumberChecker
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check_number)
        checker = NumberChecker(this)

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
        val ratingBarContainer = findViewById<View>(R.id.ratingBarContainer)
        val ratingBarFill = findViewById<View>(R.id.ratingBarFill)
        val resultTime = findViewById<TextView>(R.id.txtResultTime)

        clear.setOnClickListener { input.text?.clear() }

        checkBtn.setOnClickListener {
            val number = input.text?.toString().orEmpty()
            lifecycleScope.launch {
                checkBtn.isEnabled = false
                checkBtn.text = getString(R.string.checknumber_checking)
                runCatching { checker.check(number) }
                    .onSuccess {
                        updateUi(
                            it,
                            statusTag,
                            resultNumber,
                            ratingValue,
                            ratingNote,
                            carrierValue,
                            locationValue,
                            reportsValue,
                            ratingBarContainer,
                            ratingBarFill,
                            resultTime
                        )
                    }
                    .onFailure { Toast.makeText(this@CheckNumberActivity, it.message ?: "Invalid number", Toast.LENGTH_LONG).show() }
                checkBtn.isEnabled = true
                checkBtn.text = getString(R.string.checknumber_action)
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
        ratingBarContainer: View,
        ratingBarFill: View,
        resultTime: TextView
    ) {
        val (tagText, tagColor) = when (result.status) {
            SafetyLevel.SAFE -> getString(R.string.checknumber_safe_tag) to R.color.bottom_nav_active_green
            SafetyLevel.CAUTION -> getString(R.string.checknumber_caution_tag) to R.color.home_accent_yellow
            SafetyLevel.DANGER -> getString(R.string.checknumber_danger_tag) to R.color.home_accent_red
        }
        statusTag.text = tagText
        statusTag.setTextColor(ContextCompat.getColor(this, tagColor))

        resultNumber.text = result.displayNumber.ifBlank { result.formattedE164 }
        ratingValue.text = getString(R.string.checknumber_rating_value_format, result.score)
        ratingNote.text = when (result.status) {
            SafetyLevel.SAFE -> getString(R.string.checknumber_note_safe)
            SafetyLevel.CAUTION -> getString(R.string.checknumber_note_caution)
            SafetyLevel.DANGER -> getString(R.string.checknumber_note_danger)
        }

        carrierValue.text = result.carrier ?: getString(R.string.checknumber_carrier_unknown)
        locationValue.text = result.region ?: getString(R.string.checknumber_location_unknown)
        reportsValue.text = getString(R.string.checknumber_reports_format, result.issues.size)

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
}
