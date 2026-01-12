package com.sentinel.ai.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sentinel.ai.R
import com.sentinel.ai.SentinelApp
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.security.NumberChecker
import com.sentinel.ai.security.SafetyLevel
import com.sentinel.ai.ui.navigation.BottomTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanOptionsActivity : BaseActivity() {

    private lateinit var inputScan: EditText
    private lateinit var btnPaste: TextView
    private lateinit var btnScan: Button
    private lateinit var recentList: RecyclerView
    private lateinit var adapter: EventsAdapter

    private val numberChecker by lazy { NumberChecker(this) }
    private val eventDao by lazy { SentinelApp.instance.database.eventDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_options)

        inputScan = findViewById(R.id.inputScanContent)
        btnPaste = findViewById(R.id.btnPaste)
        btnScan = findViewById(R.id.btnScanAction)
        recentList = findViewById(R.id.recentScansList)

        findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            clearHistory()
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

        btnScan.setOnClickListener { checkNumber() }

        adapter = EventsAdapter()
        recentList.layoutManager = LinearLayoutManager(this)
        recentList.adapter = adapter
        
        loadHistory()
    }

    private fun checkNumber() {
        val input = inputScan.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, "Please enter a number or link.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = numberChecker.check(input)
            
            val riskLevel = when (result.status) {
                SafetyLevel.SAFE -> RiskLevel.SAFE
                SafetyLevel.CAUTION -> RiskLevel.WARNING
                SafetyLevel.DANGER -> RiskLevel.CRITICAL
            }

            val event = GuardianEvent(
                source = "Manual Scan",
                content = "Scanned: $input",
                score = result.score,
                riskLevel = riskLevel
            )

            eventDao.insert(event)

            withContext(Dispatchers.Main) {
                val message = "Result: ${result.status.name} (Score: ${result.score})"
                Toast.makeText(this@ScanOptionsActivity, message, Toast.LENGTH_LONG).show()
                inputScan.text.clear()
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
                Toast.makeText(this@ScanOptionsActivity, "Activity history cleared.", Toast.LENGTH_SHORT).show()
                loadHistory()
            }
        }
    }

    override fun getCurrentTab(): BottomTab = BottomTab.SCAN
}
