package com.sentinel.ai.ui

import android.Manifest
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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
            Toast.makeText(this, getString(R.string.calllog_permission_required), Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, getString(R.string.calllog_scanning_recent), Toast.LENGTH_SHORT).show()
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
        statusText.text = getString(R.string.calllog_status_scanning)
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
                    statusText.text = getString(R.string.calllog_status_unable_read)
                    emptyState.visibility = View.VISIBLE
                    adapter.submit(emptyList())
                    Toast.makeText(this@CallLogScanActivity, getString(R.string.calllog_read_failed), Toast.LENGTH_LONG).show()
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
                    statusText.text = getString(R.string.calllog_status_empty)
                    emptyState.visibility = View.VISIBLE
                    adapter.submit(emptyList())
                } else {
                    statusText.text = getString(R.string.calllog_status_analyzing_format, calls.size)
                    emptyState.visibility = View.GONE
                    // Initial display with Unknown status while loading
                    adapter.submit(calls.map { it to SafetyLevel.UNKNOWN })
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
                            status = SafetyLevel.UNKNOWN, // Default to Unknown on error
                            issues = listOf(getString(R.string.calllog_analysis_failed_format, e.message ?: "")),
                            reportCount = 0,
                            reportDetails = emptyList(),
                            debugExternalRaw = null,
                            debugReportRaw = null
                        )
                    }
                    item to result.status
                }
            }.awaitAll()

            // Update UI with real results
            withContext(Dispatchers.Main) {
                applyFilter() // Use applyFilter instead of direct submit
                statusText.text = getString(R.string.calllog_status_complete)

                try {
                    SentinelApp.instance.database.eventDao().insert(
                        GuardianEvent(
                            source = getString(R.string.calllog_event_source),
                            content = getString(R.string.calllog_event_content_format, calls.size),
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
            private val iconContainer: View = view.findViewById(R.id.eventIconContainer)
            private val icon: ImageView = view.findViewById(R.id.eventIcon)
            private val tagPrimary: TextView = view.findViewById(R.id.eventTagPrimary)
            private val tagSecondary: TextView = view.findViewById(R.id.eventTagSecondary)

            fun bind(item: Pair<CallLogItem, SafetyLevel>) {
                val (call, safety) = item
                val displayName = if (!call.name.isNullOrBlank()) call.name else call.number
                title.text = displayName
                
                val typeStr = when (call.type) {
                    CallLog.Calls.INCOMING_TYPE -> itemView.context.getString(R.string.call_type_incoming)
                    CallLog.Calls.OUTGOING_TYPE -> itemView.context.getString(R.string.call_type_outgoing)
                    CallLog.Calls.MISSED_TYPE -> itemView.context.getString(R.string.call_type_missed)
                    CallLog.Calls.BLOCKED_TYPE -> itemView.context.getString(R.string.call_type_blocked)
                    else -> itemView.context.getString(R.string.call_type_unknown)
                }

                val riskStr = when (safety) {
                    SafetyLevel.SAFE -> itemView.context.getString(R.string.safety_safe)
                    SafetyLevel.CAUTION -> itemView.context.getString(R.string.safety_caution)
                    SafetyLevel.DANGER -> itemView.context.getString(R.string.safety_risk)
                    SafetyLevel.UNKNOWN -> itemView.context.getString(R.string.safety_not_scanned)
                }

                message.text = itemView.context.getString(R.string.calllog_type_risk_format, typeStr, riskStr)
                time.text = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(call.date))
                
                val (chipBg, chipText, iconBg) = when (safety) {
                    SafetyLevel.SAFE -> Triple(
                        R.color.profile_icon_bg_green,
                        R.color.bottom_nav_active_green,
                        R.color.profile_icon_bg_green
                    )
                    SafetyLevel.CAUTION -> Triple(
                        R.color.activity_chip_bg_critical,
                        R.color.home_accent_red,
                        R.color.activity_chip_bg_critical
                    )
                    SafetyLevel.DANGER -> Triple(
                        R.color.activity_chip_bg_critical,
                        R.color.home_accent_red,
                        R.color.activity_chip_bg_critical
                    )
                    SafetyLevel.UNKNOWN -> Triple(
                        R.color.profile_icon_bg_gray,
                        R.color.home_muted,
                        R.color.profile_icon_bg_gray
                    )
                }

                icon.setImageResource(android.R.drawable.ic_menu_call)
                icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.splash_logo_icon))
                ViewCompat.setBackgroundTintList(
                    iconContainer,
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.context, iconBg))
                )

                bindChip(tagPrimary, riskStr, chipBg, chipText)
                bindChip(tagSecondary, typeStr, R.color.profile_icon_bg_gray, R.color.home_muted)
            }

            private fun bindChip(view: TextView, label: String, bgColorRes: Int, textColorRes: Int) {
                view.text = label
                view.setTextColor(ContextCompat.getColor(itemView.context, textColorRes))
                ViewCompat.setBackgroundTintList(
                    view,
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.context, bgColorRes))
                )
                view.visibility = View.VISIBLE
            }
        }
    }
}
