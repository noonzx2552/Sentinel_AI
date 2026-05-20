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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
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
import java.util.Locale

class ScanOptionsActivity : BaseActivity() {

    private enum class ScanTab { LINK, NUMBER }
    private var activeTab = ScanTab.LINK

    private lateinit var inputScan: EditText
    private lateinit var btnPaste: TextView
    private lateinit var btnScan: Button
    private lateinit var recentList: RecyclerView
    private lateinit var adapter: EventsAdapter
    private lateinit var fileResultCard: MaterialCardView
    private lateinit var fileResultIcon: ImageView
    private lateinit var tvFileResultTitle: TextView
    private lateinit var tvFileResultMeta: TextView
    private lateinit var tvFileScore: TextView
    private lateinit var tvFileIssues: TextView
    
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
        fileResultCard = findViewById(R.id.fileResultCard)
        fileResultIcon = findViewById(R.id.fileResultIcon)
        tvFileResultTitle = findViewById(R.id.tvFileResultTitle)
        tvFileResultMeta = findViewById(R.id.tvFileResultMeta)
        tvFileScore = findViewById(R.id.tvFileScore)
        tvFileIssues = findViewById(R.id.tvFileIssues)
        
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
                ScanTab.LINK -> openCheckLinkDetail()
                ScanTab.NUMBER -> openCheckNumberDetail()
            }
        }

        adapter = EventsAdapter()
        recentList.layoutManager = LinearLayoutManager(this)
        recentList.adapter = adapter

        loadHistory()
        switchTab(ScanTab.LINK) // Default

        // Auto-detect clipboard content on start
        autoDetectClipboard()
    }

    private fun autoDetectClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (!clipboard.hasPrimaryClip()) return
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return
            if (text.isBlank() || text.length > 500) return

            when {
                text.startsWith("http://") || text.startsWith("https://") || Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").containsMatchIn(text) -> {
                    inputScan.setText(text)
                    switchTab(ScanTab.LINK)
                }
                text.matches(Regex("^\\+?[0-9\\s\\-()]{7,20}$")) -> {
                    inputScan.setText(text)
                    switchTab(ScanTab.NUMBER)
                }
            }
        } catch (_: Exception) {}
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
                val analysis = inspectFile(uri, fileName, fileSize, mimeType)
                val finalScore = analysis.score
                val riskLevel = when {
                    finalScore >= 75 -> RiskLevel.SAFE
                    finalScore >= 50 -> RiskLevel.WARNING
                    else -> RiskLevel.CRITICAL
                }

                val issueText = if (analysis.issues.isEmpty()) {
                    getString(R.string.scan_file_no_issues)
                } else {
                    analysis.issues.joinToString(", ")
                }

                val event = GuardianEvent(
                    source = getString(R.string.scan_event_source_file),
                    content = getString(
                        R.string.scan_event_content_file_format,
                        fileName,
                        analysis.detectedType,
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
                    showFileResult(fileName, analysis, riskLevel)
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

    private data class FileAnalysis(
        val score: Int,
        val detectedType: String,
        val shortHash: String,
        val issues: List<String>
    )

    private fun inspectFile(uri: Uri, fileName: String, fileSize: Long, mimeType: String): FileAnalysis {
        val lowerName = fileName.lowercase(Locale.US)
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val issues = mutableListOf<String>()
        var score = 92

        val suspiciousExtensions = setOf("exe", "apk", "bat", "cmd", "scr", "pif", "js", "vbs", "ps1", "jar", "msi")
        val macroExtensions = setOf("docm", "xlsm", "pptm")
        val archiveExtensions = setOf("zip", "rar", "7z")
        val trustedDocumentExtensions = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "jpg", "jpeg", "png")

        if (extension in suspiciousExtensions) {
            score -= 38
            issues.add(getString(R.string.scan_file_issue_executable))
        }

        val nameParts = lowerName.split('.').filter { it.isNotBlank() }
        if (nameParts.size > 2 && extension in suspiciousExtensions) {
            score -= 22
            issues.add(getString(R.string.scan_file_issue_double_extension))
        }

        if (fileSize in 1 until 1024 && extension in suspiciousExtensions) {
            score -= 14
            issues.add(getString(R.string.scan_file_issue_small_executable))
        }

        if (extension in macroExtensions) {
            score -= 24
            issues.add(getString(R.string.scan_file_issue_macro))
        }

        if (extension in archiveExtensions && Regex("(invoice|payment|urgent|verify|otp|bank|login|parcel)").containsMatchIn(lowerName)) {
            score -= 18
            issues.add(getString(R.string.scan_file_issue_archive_script))
        }

        if (extension.isBlank() || (extension !in trustedDocumentExtensions && extension !in suspiciousExtensions && extension !in macroExtensions && extension !in archiveExtensions)) {
            score -= 8
            issues.add(getString(R.string.scan_file_issue_unknown_type))
        }

        if (fileSize > 50L * 1024L * 1024L) {
            score -= 6
            issues.add(getString(R.string.scan_file_issue_large))
        }

        val signature = readSignature(uri)
        val detectedType = detectFileType(signature, mimeType)
        if (hasMimeMismatch(extension, detectedType, mimeType)) {
            score -= 16
            issues.add(getString(R.string.scan_file_issue_mime_mismatch))
        }

        return FileAnalysis(
            score = score.coerceIn(0, 100),
            detectedType = detectedType,
            shortHash = computeSha256(uri).take(12),
            issues = issues.distinct()
        )
    }

    private fun readSignature(uri: Uri): ByteArray {
        return contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(16)
            val count = input.read(buffer)
            if (count > 0) buffer.copyOf(count) else ByteArray(0)
        } ?: ByteArray(0)
    }

    private fun detectFileType(signature: ByteArray, mimeType: String): String {
        val hex = signature.joinToString("") { "%02X".format(it) }
        return when {
            hex.startsWith("25504446") -> "PDF"
            hex.startsWith("89504E47") -> "PNG"
            hex.startsWith("FFD8FF") -> "JPEG"
            hex.startsWith("504B0304") -> "ZIP/Office"
            hex.startsWith("4D5A") -> "Windows executable"
            hex.startsWith("7F454C46") -> "Linux executable"
            else -> mimeType
        }
    }

    private fun hasMimeMismatch(extension: String, detectedType: String, mimeType: String): Boolean {
        if (extension.isBlank() || mimeType == getString(R.string.common_unknown)) return false
        val type = detectedType.lowercase(Locale.US)
        return when (extension) {
            "pdf" -> type != "pdf"
            "png" -> type != "png"
            "jpg", "jpeg" -> type != "jpeg"
            "docx", "xlsx", "pptx", "zip" -> type != "zip/office"
            "exe" -> type != "windows executable" && !mimeType.contains("executable", ignoreCase = true)
            else -> false
        }
    }

    private fun computeSha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun showFileResult(fileName: String, analysis: FileAnalysis, riskLevel: RiskLevel) {
        fileResultCard.visibility = View.VISIBLE
        tvFileResultTitle.text = fileName
        tvFileResultMeta.text = getString(
            R.string.scan_file_meta_format,
            analysis.detectedType,
            getString(R.string.scan_event_source_file),
            getString(R.string.scan_file_hash_format, analysis.shortHash)
        )
        tvFileScore.text = getString(R.string.scan_file_score_format, analysis.score)
        tvFileIssues.text = if (analysis.issues.isEmpty()) {
            getString(R.string.scan_file_no_issues)
        } else {
            analysis.issues.joinToString("\n") { getString(R.string.list_bullet_format, it) }
        }
        val icon = if (riskLevel == RiskLevel.CRITICAL) R.drawable.ic_exclamation else R.drawable.ic_check
        val tint = when (riskLevel) {
            RiskLevel.SAFE -> R.color.splash_logo_bg
            RiskLevel.WARNING -> R.color.home_accent_yellow
            RiskLevel.CRITICAL -> R.color.home_accent_red
        }
        fileResultIcon.setImageResource(icon)
        fileResultIcon.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, tint))
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
        if (content.startsWith("http://") || content.startsWith("https://") || content.contains(".")) {
            inputScan.setText(content)
            switchTab(ScanTab.LINK)
            openCheckLinkDetail()
        } else if (content.matches(Regex("^[+]?[0-9\\s\\-()]+$"))) {
            inputScan.setText(content)
            switchTab(ScanTab.NUMBER)
            openCheckNumberDetail()
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
        val activeColor = ContextCompat.getColor(this, R.color.splash_logo_icon)
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

    private fun openCheckLinkDetail() {
        val url = inputScan.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, getString(R.string.scan_error_url_required), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CheckLinkActivity::class.java)
        intent.putExtra(CheckLinkActivity.EXTRA_URL, url)
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in_scale, R.anim.fade_out)
    }

    private fun openCheckNumberDetail() {
        val input = inputScan.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, getString(R.string.scan_error_number_required), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CheckNumberActivity::class.java)
        intent.putExtra(CheckNumberActivity.EXTRA_NUMBER, input)
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in_scale, R.anim.fade_out)
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
