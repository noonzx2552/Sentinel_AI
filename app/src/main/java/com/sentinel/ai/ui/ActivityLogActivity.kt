package com.sentinel.ai.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sentinel.ai.R
import com.sentinel.ai.SentinelApp
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.ui.navigation.BottomTab
import com.sentinel.ai.utils.EventLocalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ActivityLogActivity : BaseActivity() {

    private lateinit var adapter: ActivityLogAdapter
    private lateinit var filterAll: TextView
    private lateinit var filterThreats: TextView
    private lateinit var filterScans: TextView
    private var currentFilter: ActivityFilter = ActivityFilter.ALL
    private var lastEvents: List<GuardianEvent> = emptyList()
    private var statsTotalView: TextView? = null
    private var statsThreatsView: TextView? = null
    private var statsSafeView: TextView? = null

    private val headerDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activity_log)

        val recyclerView = findViewById<RecyclerView>(R.id.eventsList)
        val emptyState = findViewById<TextView>(R.id.emptyState)

        adapter = ActivityLogAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        filterAll = findViewById(R.id.filterAll)
        filterThreats = findViewById(R.id.filterThreats)
        filterScans = findViewById(R.id.filterScans)
        statsTotalView = findViewById(R.id.statsTotal)
        statsThreatsView = findViewById(R.id.statsThreats)
        statsSafeView = findViewById(R.id.statsSafe)

        applyFilterUi()
        filterAll.setOnClickListener { setFilter(ActivityFilter.ALL) }
        filterThreats.setOnClickListener { setFilter(ActivityFilter.THREATS) }
        filterScans.setOnClickListener { setFilter(ActivityFilter.SCANS) }

        // Clear all button
        findViewById<View>(R.id.btnClearAll)?.setOnClickListener {
            showClearAllConfirmation()
        }

        lifecycleScope.launch {
            SentinelApp.instance.database.eventDao().getAllEvents().collect { events ->
                lastEvents = events
                val filtered = applyFilter(events)
                adapter.submitList(buildItems(filtered))
                emptyState?.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                updateStats(events)
            }
        }
    }

    override fun getCurrentTab(): BottomTab = BottomTab.ACTIVITY

    private fun buildItems(events: List<GuardianEvent>): List<ActivityLogItem> {
        if (events.isEmpty()) return emptyList()
        val items = mutableListOf<ActivityLogItem>()
        var lastHeader: String? = null
        val now = System.currentTimeMillis()
        val yesterday = now - DateUtils.DAY_IN_MILLIS

        events.forEach { event ->
            val header = when {
                isSameDay(event.timestamp, now) -> getString(R.string.activitylog_today)
                isSameDay(event.timestamp, yesterday) -> getString(R.string.activitylog_yesterday)
                else -> headerDateFormat.format(Date(event.timestamp))
            }
            if (header != lastHeader) {
                items.add(ActivityLogItem.Section(header))
                lastHeader = header
            }
            items.add(ActivityLogItem.Event(event))
        }
        return items
    }

    private fun setFilter(filter: ActivityFilter) {
        if (currentFilter == filter) return
        currentFilter = filter
        applyFilterUi()
        val filtered = applyFilter(lastEvents)
        adapter.submitList(buildItems(filtered))
    }

    private fun applyFilter(events: List<GuardianEvent>): List<GuardianEvent> = when (currentFilter) {
        ActivityFilter.ALL -> events
        ActivityFilter.THREATS -> events.filter { it.riskLevel != RiskLevel.SAFE }
        ActivityFilter.SCANS -> events.filter { EventLocalization.isScanSource(this, it.source) }
    }

    private fun applyFilterUi() {
        updateFilterChip(filterAll, currentFilter == ActivityFilter.ALL)
        updateFilterChip(filterThreats, currentFilter == ActivityFilter.THREATS)
        updateFilterChip(filterScans, currentFilter == ActivityFilter.SCANS)
    }

    private fun updateFilterChip(view: TextView, active: Boolean) {
        val bg = if (active) R.drawable.activity_filter_active_bg else R.drawable.activity_filter_inactive_bg
        val textColor = if (active) android.R.color.white else R.color.home_muted
        view.setBackgroundResource(bg)
        view.setTextColor(ContextCompat.getColor(this, textColor))
    }

    private fun updateStats(events: List<GuardianEvent>) {
        val total = events.size
        val threats = events.count { it.riskLevel != RiskLevel.SAFE }
        val safe = events.count { it.riskLevel == RiskLevel.SAFE }
        statsTotalView?.text = total.toString()
        statsThreatsView?.text = threats.toString()
        statsSafeView?.text = safe.toString()
    }

    private fun showClearAllConfirmation() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_clear_activity, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<View>(R.id.clearDialogCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<View>(R.id.clearDialogConfirm).setOnClickListener {
            dialog.dismiss()
            lifecycleScope.launch(Dispatchers.IO) {
                SentinelApp.instance.database.eventDao().clearAll()
                withContext(Dispatchers.Main) {
                    showBottomPopup(getString(R.string.activitylog_clear_done))
                }
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }

    private fun isSameDay(time1: Long, time2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = time1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = time2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        return if (isSameDay(timestamp, now)) {
            val diff = now - timestamp
            if (diff < DateUtils.MINUTE_IN_MILLIS * 2) {
                getString(R.string.activitylog_time_just_now)
            } else {
                DateUtils.getRelativeTimeSpanString(
                    timestamp,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            }
        } else {
            timeFormat.format(Date(timestamp))
        }
    }

    private sealed class ActivityLogItem {
        data class Section(val title: String) : ActivityLogItem()
        data class Event(val event: GuardianEvent) : ActivityLogItem()
    }

    private inner class ActivityLogAdapter : ListAdapter<ActivityLogItem, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is ActivityLogItem.Section -> VIEW_TYPE_HEADER
            is ActivityLogItem.Event -> VIEW_TYPE_EVENT
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_TYPE_HEADER) {
                val view = inflater.inflate(R.layout.item_event_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.item_event, parent, false)
                EventViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is ActivityLogItem.Section -> (holder as HeaderViewHolder).bind(item.title)
                is ActivityLogItem.Event -> (holder as EventViewHolder).bind(item.event)
            }
        }

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.eventHeaderTitle)

            fun bind(text: String) {
                title.text = text
            }
        }

        inner class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.eventTitle)
            private val message: TextView = view.findViewById(R.id.eventMessage)
            private val time: TextView = view.findViewById(R.id.eventTime)
            private val iconContainer: View = view.findViewById(R.id.eventIconContainer)
            private val icon: ImageView = view.findViewById(R.id.eventIcon)
            private val tagPrimary: TextView = view.findViewById(R.id.eventTagPrimary)
            private val tagSecondary: TextView = view.findViewById(R.id.eventTagSecondary)

            fun bind(event: GuardianEvent) {
                val localized = EventLocalization.localizeEvent(itemView.context, event.source, event.content)
                title.text = localized.source
                message.text = localized.content
                    .replace("\n", " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                time.text = formatTime(event.timestamp)

                val (primaryLabel, primaryBg, primaryText, iconRes, iconBg) = when (event.riskLevel) {
                    RiskLevel.SAFE -> TagStyle(
                        getString(R.string.activitylog_item3_tag_primary),
                        R.color.profile_icon_bg_green,
                        R.color.bottom_nav_active_green,
                        R.drawable.intro_icon_shield,
                        R.color.profile_icon_bg_green
                    )
                    RiskLevel.WARNING -> TagStyle(
                        getString(R.string.activitylog_item2_tag),
                        R.color.profile_icon_bg_yellow,
                        R.color.home_accent_yellow,
                        R.drawable.ic_profile_notifications_none,
                        R.color.profile_icon_bg_yellow
                    )
                    RiskLevel.CRITICAL -> TagStyle(
                        getString(R.string.activitylog_item1_tag_primary),
                        R.color.activity_chip_bg_critical,
                        R.color.home_accent_red,
                        R.drawable.ic_profile_security,
                        R.color.activity_chip_bg_critical
                    )
                }

                icon.setImageResource(iconRes)
                icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(itemView.context, primaryText))
                ViewCompat.setBackgroundTintList(
                    iconContainer,
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.context, iconBg))
                )

                bindChip(tagPrimary, primaryLabel, primaryBg, primaryText)

                val secondaryTag = when {
                    event.riskLevel == RiskLevel.CRITICAL -> TagStyle(
                        getString(R.string.activitylog_item1_tag_secondary),
                        R.color.profile_icon_bg_gray,
                        R.color.home_muted,
                        iconRes,
                        iconBg
                    )
                    event.riskLevel == RiskLevel.SAFE && EventLocalization.isScanSource(itemView.context, event.source) -> TagStyle(
                        getString(R.string.activitylog_item3_tag_secondary),
                        R.color.profile_icon_bg_blue,
                        R.color.profile_icon_blue,
                        iconRes,
                        iconBg
                    )
                    else -> null
                }

                if (secondaryTag == null) {
                    tagSecondary.visibility = View.GONE
                } else {
                    tagSecondary.visibility = View.VISIBLE
                    bindChip(tagSecondary, secondaryTag.label, secondaryTag.bgColorRes, secondaryTag.textColorRes)
                }
            }

            private fun bindChip(view: TextView, label: String, bgColorRes: Int, textColorRes: Int) {
                view.text = label
                view.setTextColor(ContextCompat.getColor(itemView.context, textColorRes))
                ViewCompat.setBackgroundTintList(
                    view,
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.context, bgColorRes))
                )
            }
        }
    }

    private data class TagStyle(
        val label: String,
        val bgColorRes: Int,
        val textColorRes: Int,
        val iconRes: Int,
        val iconBgRes: Int
    )

    private companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_EVENT = 1

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ActivityLogItem>() {
            override fun areItemsTheSame(oldItem: ActivityLogItem, newItem: ActivityLogItem): Boolean {
                return when {
                    oldItem is ActivityLogItem.Section && newItem is ActivityLogItem.Section ->
                        oldItem.title == newItem.title
                    oldItem is ActivityLogItem.Event && newItem is ActivityLogItem.Event ->
                        oldItem.event.id == newItem.event.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: ActivityLogItem, newItem: ActivityLogItem): Boolean {
                return oldItem == newItem
            }
        }
    }

    private enum class ActivityFilter {
        ALL,
        THREATS,
        SCANS
    }
}
