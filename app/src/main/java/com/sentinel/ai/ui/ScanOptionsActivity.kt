package com.sentinel.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sentinel.ai.R
import com.sentinel.ai.SentinelApp
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.LinkChecker
import com.sentinel.ai.security.NumberChecker
import com.sentinel.ai.security.SafetyLevel
import com.sentinel.ai.ui.navigation.BottomTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class ScanOptionsActivity : BaseActivity() {

    private enum class ScanTab { LINK, NUMBER }
    private var activeTab = ScanTab.LINK

    private lateinit var inputScan: EditText
    private lateinit var btnPaste: TextView
    private lateinit var btnScan: Button
    private lateinit var recentList: RecyclerView
    private lateinit var adapter: EventsAdapter
    
    private lateinit var tabLink: TextView
    private lateinit var tabNumber: TextView

    private val numberChecker by lazy { NumberChecker(this) }
    private val linkChecker by lazy { LinkChecker(this) }
    private val eventDao by lazy { SentinelApp.instance.database.eventDao() }

    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { analyzeFile(it) }
    }

    // QR Scanner launcher
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scannedContent = result.data?.getStringExtra(QrScannerActivity.EXTRA_SCANNED_CONTENT)
            if (!scannedContent.isNullOrBlank()) {
                handleQrResult(scannedContent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_options)

        inputScan = findViewById(R.id.inputScanContent)
        btnPaste = findViewById(R.id.btnPaste)
        btnScan = findViewById(R.id.btnScanAction)
        recentList = findViewById(R.id.recentScansList)
        
        tabLink = findViewById(R.id.tabLink)
        tabNumber = findViewById(R.id.tabNumber)

        tabLink.setOnClickListener { switchTab(ScanTab.LINK) }
        tabNumber.setOnClickListener { switchTab(ScanTab.NUMBER) }

        findViewById<Button>(R.id.btnClearHistory).setOnClickListener { clearHistory() }

        findViewById<View>(R.id.cardUpload).setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        findViewById<View>(R.id.cardQR).setOnClickListener {
            openQrScanner()
        }

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clipData = clipboard.primaryClip
                if (clipData != null && clipData.itemCount > 0) {
                    val text = clipData.getItemAt(0).text
                    inputScan.setText(text)
                }
            }
        }

        btnScan.setOnClickListener { 
            when (activeTab) {
                ScanTab.LINK -> checkLink()
                ScanTab.NUMBER -> checkNumber()
            }
        }

        adapter = EventsAdapter()
        recentList.layoutManager = LinearLayoutManager(this)
        recentList.adapter = adapter
        
        loadHistory()
        switchTab(ScanTab.LINK) // Default
    }

    private fun openQrScanner() {
        val intent = Intent(this, QrScannerActivity::class.java)
        qrScannerLauncher.launch(intent)
    }

    private fun analyzeFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileName = getFileName(uri)
                val fileSize = getFileSize(uri)
                val mimeType = contentResolver.getType(uri) ?: getString(R.string.common_unknown)

                // Analyze file for suspicious patterns
                var score = 70
                val issues = mutableListOf<String>()

                // Check for suspicious file extensions
                val suspiciousExtensions = listOf(".exe", ".apk", ".bat", ".cmd", ".scr", ".pif", ".js", ".vbs")
                if (suspiciousExtensions.any { fileName.lowercase().endsWith(it) }) {
                    score -= 30
                    issues.add(getString(R.string.scan_file_issue_executable))
                }

                // Check for double extensions (e.g., document.pdf.exe)
                val parts = fileName.split(".")
                if (parts.size > 2 && suspiciousExtensions.any { fileName.lowercase().endsWith(it) }) {
                    score -= 20
                    issues.add(getString(R.string.scan_file_issue_double_extension))
                }

                // Check file size (very small executables are suspicious)
                if (fileSize < 1024 && suspiciousExtensions.any { fileName.lowercase().endsWith(it) }) {
                    score -= 15
                    issues.add(getString(R.string.scan_file_issue_small_executable))
                }

                // Check for macro-enabled documents
                val macroExtensions = listOf(".docm", ".xlsm", ".pptm")
                if (macroExtensions.any { fileName.lowercase().endsWith(it) }) {
                    score -= 20
                    issues.add(getString(R.string.scan_file_issue_macro))
                }

                val finalScore = score.coerceIn(0, 100)
                val riskLevel = when {
                    finalScore >= 75 -> RiskLevel.SAFE
                    finalScore >= 50 -> RiskLevel.WARNING
                    else -> RiskLevel.CRITICAL
                }

                val issueText = if (issues.isEmpty()) getString(R.string.scan_file_no_issues) else issues.joinToString(", ")

                val event = GuardianEvent(
                    source = getString(R.string.scan_event_source_file),
                    content = getString(
                        R.string.scan_event_content_file_format,
                        fileName,
                        mimeType,
                        formatFileSize(fileSize),
                        issueText
                    ),
                    score = finalScore,
                    riskLevel = riskLevel
                )
                eventDao.insert(event)

                withContext(Dispatchers.Main) {
                    val message = when (riskLevel) {
                        RiskLevel.SAFE -> getString(R.string.scan_file_result_safe, finalScore)
                        RiskLevel.WARNING -> getString(R.string.scan_file_result_warning, finalScore)
                        RiskLevel.CRITICAL -> getString(R.string.scan_file_result_critical, finalScore)
                        else -> getString(R.string.scan_file_result_unknown, finalScore)
                    }
                    Toast.makeText(this@ScanOptionsActivity, message, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScanOptionsActivity,
                        getString(R.string.scan_file_analyze_failed_format, e.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = getString(R.string.common_unknown)
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun getFileSize(uri: Uri): Long {
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0) {
                size = cursor.getLong(sizeIndex)
            }
        }
        return size
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }

    private fun handleQrResult(content: String) {
        // Check if it's a URL
        if (content.startsWith("http://") || content.startsWith("https://") || content.contains(".")) {
            inputScan.setText(content)
            switchTab(ScanTab.LINK)
            checkLink()
        } else if (content.matches(Regex("^[+]?[0-9\\s\\-()]+$"))) {
            // Looks like a phone number
            inputScan.setText(content)
            switchTab(ScanTab.NUMBER)
            checkNumber()
        } else {
            // General text - log it and check if it looks suspicious
            lifecycleScope.launch(Dispatchers.IO) {
                var score = 70
                val issues = mutableListOf<String>()

                // Check for suspicious keywords
                val lowerContent = content.lowercase()
                val suspiciousKeywords = listOf("otp", "password", "login", "verify", "urgent", "transfer", "bank", "account")
                if (suspiciousKeywords.any { lowerContent.contains(it) }) {
                    score -= 25
                    issues.add(getString(R.string.scan_qr_issue_keywords))
                }

                val riskLevel = when {
                    score >= 75 -> RiskLevel.SAFE
                    score >= 50 -> RiskLevel.WARNING
                    else -> RiskLevel.CRITICAL
                }

                val event = GuardianEvent(
                    source = getString(R.string.scan_event_source_qr),
                    content = content.take(200),
                    score = score,
                    riskLevel = riskLevel
                )
                eventDao.insert(event)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScanOptionsActivity,
                        getString(R.string.scan_qr_scanned_format, riskLevelLabel(riskLevel)),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun switchTab(tab: ScanTab) {
        activeTab = tab
        
        // Update UI
        val activeBg = R.drawable.scan_tab_active_bg
        val inactiveBg = 0 // Transparent
        val activeColor = ContextCompat.getColor(this, R.color.splash_logo_bg)
        val inactiveColor = ContextCompat.getColor(this, R.color.home_muted)
        
        tabLink.apply {
            setBackgroundResource(if (tab == ScanTab.LINK) activeBg else inactiveBg)
            setTextColor(if (tab == ScanTab.LINK) activeColor else inactiveColor)
            setTypeface(null, if (tab == ScanTab.LINK) Typeface.BOLD else Typeface.NORMAL)
        }
        tabNumber.apply {
            setBackgroundResource(if (tab == ScanTab.NUMBER) activeBg else inactiveBg)
            setTextColor(if (tab == ScanTab.NUMBER) activeColor else inactiveColor)
            setTypeface(null, if (tab == ScanTab.NUMBER) Typeface.BOLD else Typeface.NORMAL)
        }
        
        inputScan.hint = when (tab) {
            ScanTab.LINK -> getString(R.string.scan_hint_url)
            ScanTab.NUMBER -> getString(R.string.scan_hint_number)
        }
    }

    private fun checkLink() {
        val url = inputScan.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, getString(R.string.scan_error_url_required), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = linkChecker.check(url)
                val riskLevel = when (result.status) {
                    com.sentinel.ai.security.SafetyLevel.SAFE -> RiskLevel.SAFE
                    com.sentinel.ai.security.SafetyLevel.CAUTION -> RiskLevel.WARNING
                    com.sentinel.ai.security.SafetyLevel.DANGER -> RiskLevel.CRITICAL
                    com.sentinel.ai.security.SafetyLevel.UNKNOWN -> RiskLevel.SAFE
                    else -> RiskLevel.SAFE
                }

                val event = GuardianEvent(
                    source = getString(R.string.scan_event_source_web),
                    content = getString(R.string.scan_event_content_url_format, result.domain),
                    score = result.score,
                    riskLevel = riskLevel
                )
                eventDao.insert(event)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ScanOptionsActivity,
                        getString(R.string.scan_result_format, safetyLevelLabel(result.status)),
                        Toast.LENGTH_LONG
                    ).show()
                    inputScan.text.clear()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScanOptionsActivity, getString(R.string.scan_error_network_invalid_url), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkNumber() {
        val input = inputScan.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, getString(R.string.scan_error_number_required), Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = numberChecker.check(input)
                
                val riskLevel = when (result.status) {
                    SafetyLevel.SAFE -> RiskLevel.SAFE
                    SafetyLevel.CAUTION -> RiskLevel.WARNING
                    SafetyLevel.DANGER -> RiskLevel.CRITICAL
                    SafetyLevel.UNKNOWN -> RiskLevel.SAFE
                    else -> RiskLevel.SAFE
                }

                val event = GuardianEvent(
                    source = getString(R.string.scan_event_source_number),
                    content = getString(R.string.scan_event_content_phone_format, input),
                    score = result.score,
                    riskLevel = riskLevel
                )

                eventDao.insert(event)

                withContext(Dispatchers.Main) {
                    val message = getString(
                        R.string.scan_number_result_format,
                        safetyLevelLabel(result.status),
                        result.score
                    )
                    Toast.makeText(this@ScanOptionsActivity, message, Toast.LENGTH_LONG).show()
                    inputScan.text.clear()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ScanOptionsActivity, getString(R.string.scan_error_invalid_number_format), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            eventDao.getAllEvents().collect { events ->
                adapter.submit(events)
            }
        }
    }

    private fun clearHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            eventDao.clearAll()
            withContext(Dispatchers.Main) {
                Toast.makeText(this@ScanOptionsActivity, getString(R.string.scan_history_cleared), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun riskLevelLabel(level: RiskLevel): String {
        return when (level) {
            RiskLevel.SAFE -> getString(R.string.risk_level_safe)
            RiskLevel.WARNING -> getString(R.string.risk_level_warning)
            RiskLevel.CRITICAL -> getString(R.string.risk_level_critical)
        }
    }

    private fun safetyLevelLabel(level: SafetyLevel): String {
        return when (level) {
            SafetyLevel.SAFE -> getString(R.string.safety_safe)
            SafetyLevel.CAUTION -> getString(R.string.safety_caution)
            SafetyLevel.DANGER -> getString(R.string.safety_danger)
            SafetyLevel.UNKNOWN -> getString(R.string.safety_unknown)
        }
    }

    override fun getCurrentTab(): BottomTab = BottomTab.SCAN
}
