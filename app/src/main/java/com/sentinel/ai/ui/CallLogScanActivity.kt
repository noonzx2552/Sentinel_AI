package com.sentinel.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.sentinel.ai.security.NumberChecker
import com.sentinel.ai.security.SafetyLevel
import com.sentinel.ai.ui.navigation.BottomTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallLogScanActivity : BaseActivity() {

    private lateinit var adapter: CallLogAdapter
    private lateinit var emptyState: TextView
    private lateinit var statusText: TextView
    private val numberChecker by lazy { NumberChecker(this) }
    private var allScannedCalls: List<Pair<CallLogItem, SafetyLevel>> = emptyList()
    private var currentFilter: SafetyLevel? = null // null = All

    private lateinit var btnScanAll: View
    private lateinit var filterAll: TextView
    private lateinit var filterSuspicious: TextView
    private lateinit var filterSafe: TextView

    private companion object {
        const val MAX_CALL_LOG_ITEMS = 20
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            loadCallLog()
        } else {
            Toast.makeText(this, "Call Log permission is required to scan.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call_log_scan)

        val recycler = findViewById<RecyclerView>(R.id.callLogList)
        emptyState = findViewById(R.id.emptyState)
        statusText = findViewById(R.id.statusText)
        btnScanAll = findViewById(R.id.btnScanAll)
        filterAll = findViewById(R.id.filterAll)
        filterSuspicious = findViewById(R.id.filterSuspicious)
        filterSafe = findViewById(R.id.filterSafe)

        adapter = CallLogAdapter()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        setupListeners()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            loadCallLog()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    private fun setupListeners() {
        btnScanAll.setOnClickListener {
            loadCallLog()
            Toast.makeText(this, "Scanning recent calls...", Toast.LENGTH_SHORT).show()
        }

        filterAll.setOnClickListener { setFilter(null) }
        filterSuspicious.setOnClickListener { setFilter(SafetyLevel.DANGER) } // Treat Suspicious as Danger/Caution
        filterSafe.setOnClickListener { setFilter(SafetyLevel.SAFE) }
    }

    private fun setFilter(filter: SafetyLevel?) {
        currentFilter = filter
        updateFilterUI()
        applyFilter()
    }

    private fun updateFilterUI() {
        // Helper to update style
        fun updateStyle(view: TextView, isActive: Boolean) {
            if (isActive) {
                view.setBackgroundResource(R.drawable.chip_active_bg)
                view.setTextColor(ContextCompat.getColor(this, R.color.splash_logo_icon))
            } else {
                view.setBackgroundResource(R.drawable.chip_inactive_bg)
                view.setTextColor(ContextCompat.getColor(this, R.color.home_muted))
            }
        }

        updateStyle(filterAll, currentFilter == null)
        updateStyle(filterSuspicious, currentFilter == SafetyLevel.DANGER)
        updateStyle(filterSafe, currentFilter == SafetyLevel.SAFE)
    }

    private fun applyFilter() {
        val filtered = if (currentFilter == null) {
            allScannedCalls
        } else {
            allScannedCalls.filter { 
                if (currentFilter == SafetyLevel.DANGER) {
                    it.second == SafetyLevel.DANGER || it.second == SafetyLevel.CAUTION
                } else {
                    it.second == currentFilter
                }
            }
        }
        adapter.submit(filtered)
        
        if (filtered.isEmpty() && allScannedCalls.isNotEmpty()) {
             // Different message for "No results for filter" vs "Empty log"
             // But for now, keeping it simple as items won't disappear, just list shrinks.
        }
    }

    private fun loadCallLog() {
        statusText.text = "Scanning call log..."
        lifecycleScope.launch(Dispatchers.IO) {
            val calls = mutableListOf<CallLogItem>()
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.DATE,
                CallLog.Calls.TYPE,
                CallLog.Calls.CACHED_NAME
            )
            val cursor = try {
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusText.text = "Unable to read call log."
                    emptyState.visibility = View.VISIBLE
                    adapter.submit(emptyList())
                    Toast.makeText(this@CallLogScanActivity, "Call log read failed.", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            cursor?.use {
                val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)

                if (numberIdx >= 0 && dateIdx >= 0 && typeIdx >= 0) {
                    while (it.moveToNext() && calls.size < MAX_CALL_LOG_ITEMS) {
                        val number = it.getString(numberIdx) ?: continue
                        val date = it.getLong(dateIdx)
                        val type = it.getInt(typeIdx)
                        val name = if (nameIdx >= 0) it.getString(nameIdx) else null
                        calls.add(CallLogItem(number, date, type, name))
                    }
                }
            }

            // Show list immediately
            withContext(Dispatchers.Main) {
                if (calls.isEmpty()) {
                    statusText.text = "No recent calls found."
                    emptyState.visibility = View.VISIBLE
                    adapter.submit(emptyList())
                } else {
                    statusText.text = "Analyzing ${calls.size} calls..."
                    emptyState.visibility = View.GONE
                    // Initial display with Safe status while loading
                    // We don't save this to allScannedCalls yet to avoid flickering filters
                    adapter.submit(calls.map { it to SafetyLevel.SAFE })
                }
            }

            if (calls.isEmpty()) return@launch

            // Analyze in parallel
            allScannedCalls = calls.map { item ->
                async {
                    val result = try {
                        numberChecker.check(item.number)
                    } catch (e: Exception) {
                        // Fallback for invalid numbers
                        com.sentinel.ai.security.NumberCheckResult(
                            rawInput = item.number,
                            formattedE164 = item.number,
                            displayNumber = item.number,
                            region = null,
                            carrier = null,
                            countryName = null,
                            externalLineType = null,
                            numberType = io.michaelrocks.libphonenumber.android.PhoneNumberUtil.PhoneNumberType.UNKNOWN,
                            score = 0,
                            status = SafetyLevel.SAFE,
                            issues = listOf("Analysis failed: ${e.message}")
                        )
                    }
                    item to result.status
                }
            }.awaitAll()

            // Update UI with real results
            withContext(Dispatchers.Main) {
                applyFilter() // Use applyFilter instead of direct submit
                statusText.text = "Scan complete."

                try {
                    SentinelApp.instance.database.eventDao().insert(
                        GuardianEvent(
                            source = "Call Log Scan",
                            content = "Scanned ${calls.size} recent calls.",
                            score = 100,
                            riskLevel = RiskLevel.SAFE
                        )
                    )
                } catch (e: Exception) {
                    // ignore db error
                }
            }
        }
    }

    override fun getCurrentTab(): BottomTab = BottomTab.SCAN

    data class CallLogItem(val number: String, val date: Long, val type: Int, val name: String?)

    inner class CallLogAdapter : RecyclerView.Adapter<CallLogAdapter.ViewHolder>() {
        private var items: List<Pair<CallLogItem, SafetyLevel>> = emptyList()

        fun submit(newItems: List<Pair<CallLogItem, SafetyLevel>>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false) // Reusing item_event for now
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.eventTitle)
            private val message: TextView = view.findViewById(R.id.eventMessage)
            private val time: TextView = view.findViewById(R.id.eventTime)
            private val riskIndicator: View = view.findViewById(R.id.riskIndicator)

            fun bind(item: Pair<CallLogItem, SafetyLevel>) {
                val (call, safety) = item
                val displayName = if (!call.name.isNullOrBlank()) call.name else call.number
                title.text = displayName
                
                val typeStr = when (call.type) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.BLOCKED_TYPE -> "Blocked"
                    else -> "Unknown"
                }

                val riskStr = when (safety) {
                    SafetyLevel.SAFE -> "Safe"
                    SafetyLevel.CAUTION -> "Caution"
                    SafetyLevel.DANGER -> "Risk"
                }

                message.text = "$typeStr • $riskStr"
                time.text = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(call.date))
                
                val colorRes = when (safety) {
                    SafetyLevel.SAFE -> R.color.bottom_nav_active_green
                    SafetyLevel.CAUTION -> R.color.home_accent_yellow
                    SafetyLevel.DANGER -> R.color.home_accent_red
                }
                riskIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, colorRes))
            }
        }
    }
}
