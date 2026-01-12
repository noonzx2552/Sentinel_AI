package com.sentinel.ai.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sentinel.ai.R
import com.sentinel.ai.SentinelApp
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.ui.navigation.BottomTab
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityLogActivity : BaseActivity() {

    private lateinit var adapter: EventsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activity_log)

        val recyclerView = findViewById<RecyclerView>(R.id.eventsList)
        val emptyState = findViewById<TextView>(R.id.emptyState)

        adapter = EventsAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            SentinelApp.instance.database.eventDao().getAllEvents().collect { events ->
                adapter.submit(events)
                emptyState?.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun getCurrentTab(): BottomTab = BottomTab.ACTIVITY

    inner class EventsAdapter : RecyclerView.Adapter<EventsAdapter.EventViewHolder>() {
        private var items: List<GuardianEvent> = emptyList()

        fun submit(newItems: List<GuardianEvent>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
            return EventViewHolder(view)
        }

        override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.eventTitle)
            private val message: TextView = view.findViewById(R.id.eventMessage)
            private val time: TextView = view.findViewById(R.id.eventTime)
            private val riskIndicator: View = view.findViewById(R.id.riskIndicator)

            fun bind(event: GuardianEvent) {
                title.text = event.source
                message.text = event.content
                time.text = SimpleDateFormat("HH:mm, dd MMM", Locale.getDefault()).format(Date(event.timestamp))

                val (colorRes, _) = when (event.riskLevel) {
                    RiskLevel.SAFE -> R.color.bottom_nav_active_green to "Safe"
                    RiskLevel.WARNING -> R.color.home_accent_yellow to "Warning"
                    RiskLevel.CRITICAL -> R.color.home_accent_red to "Critical"
                }
                riskIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, colorRes))
            }
        }
    }
}
