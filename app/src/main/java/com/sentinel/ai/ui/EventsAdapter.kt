package com.sentinel.ai.ui

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.sentinel.ai.R
import com.sentinel.ai.databinding.ItemEventBinding
import com.sentinel.ai.model.GuardianEvent
import com.sentinel.ai.model.RiskLevel
import com.sentinel.ai.utils.EventLocalization
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun bind(event: GuardianEvent) {
            val localized = EventLocalization.localizeEvent(itemView.context, event.source, event.content)
            binding.eventTitle.text = localized.source
            binding.eventMessage.text = localized.content
            binding.eventTime.text = formatTime(event.timestamp)

            val (primaryLabel, primaryBg, primaryText, iconRes, iconBg) = when (event.riskLevel) {
                RiskLevel.SAFE -> TagStyle(
                    itemView.context.getString(R.string.activitylog_item3_tag_primary),
                    R.color.profile_icon_bg_green,
                    R.color.bottom_nav_active_green,
                    R.drawable.intro_icon_shield,
                    R.color.profile_icon_bg_green
                )
                RiskLevel.WARNING -> TagStyle(
                    itemView.context.getString(R.string.activitylog_item2_tag),
                    R.color.profile_icon_bg_yellow,
                    R.color.home_accent_yellow,
                    R.drawable.ic_profile_notifications_none,
                    R.color.profile_icon_bg_yellow
                )
                RiskLevel.CRITICAL -> TagStyle(
                    itemView.context.getString(R.string.activitylog_item1_tag_primary),
                    R.color.activity_chip_bg_critical,
                    R.color.home_accent_red,
                    R.drawable.ic_profile_security,
                    R.color.activity_chip_bg_critical
                )
            }

            binding.eventIcon.setImageResource(iconRes)
            binding.eventIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(itemView.context, primaryText)
            )
            ViewCompat.setBackgroundTintList(
                binding.eventIconContainer,
                ColorStateList.valueOf(ContextCompat.getColor(itemView.context, iconBg))
            )

            bindChip(binding.eventTagPrimary, primaryLabel, primaryBg, primaryText)

            val secondaryTag = when {
                event.riskLevel == RiskLevel.CRITICAL -> TagStyle(
                    itemView.context.getString(R.string.activitylog_item1_tag_secondary),
                    R.color.profile_icon_bg_gray,
                    R.color.home_muted,
                    iconRes,
                    iconBg
                )
                event.riskLevel == RiskLevel.SAFE && EventLocalization.isScanSource(itemView.context, event.source) -> TagStyle(
                    itemView.context.getString(R.string.activitylog_item3_tag_secondary),
                    R.color.profile_icon_bg_blue,
                    R.color.profile_icon_blue,
                    iconRes,
                    iconBg
                )
                else -> null
            }

            if (secondaryTag == null) {
                binding.eventTagSecondary.visibility = View.GONE
            } else {
                binding.eventTagSecondary.visibility = View.VISIBLE
                bindChip(
                    binding.eventTagSecondary,
                    secondaryTag.label,
                    secondaryTag.bgColorRes,
                    secondaryTag.textColorRes
                )
            }
        }

        private fun formatTime(timestamp: Long): String {
            val now = System.currentTimeMillis()
            return if (isSameDay(timestamp, now)) {
                val diff = now - timestamp
                if (diff < DateUtils.MINUTE_IN_MILLIS * 2) {
                    itemView.context.getString(R.string.activitylog_time_just_now)
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

        private fun isSameDay(time1: Long, time2: Long): Boolean {
            val cal1 = Calendar.getInstance().apply { timeInMillis = time1 }
            val cal2 = Calendar.getInstance().apply { timeInMillis = time2 }
            return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
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

    private data class TagStyle(
        val label: String,
        val bgColorRes: Int,
        val textColorRes: Int,
        val iconRes: Int,
        val iconBgRes: Int
    )
}
