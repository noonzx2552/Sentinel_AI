package com.sentinel.ai.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sentinel.ai.R
import com.sentinel.ai.databinding.ItemEventBinding
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.RiskLevel

class EventsAdapter : RecyclerView.Adapter<EventsAdapter.EventViewHolder>() {

    private val items = mutableListOf<GuardianEvent>()

    fun submit(list: List<GuardianEvent>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class EventViewHolder(private val binding: ItemEventBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(event: GuardianEvent) {
            binding.eventTitle.text = event.source
            binding.eventMessage.text = event.content
            // eventScore is not in the layout, skipping display for now or mapping it to something else if needed
            // For now, removing the reference to avoid build error
            
            val color = when (event.riskLevel) {
                RiskLevel.SAFE -> R.color.sentinel_on_surface
                RiskLevel.WARNING -> R.color.sentinel_warning
                RiskLevel.CRITICAL -> R.color.sentinel_critical
            }
            binding.eventMessage.setTextColor(ContextCompat.getColor(binding.root.context, color))
        }
    }
}
