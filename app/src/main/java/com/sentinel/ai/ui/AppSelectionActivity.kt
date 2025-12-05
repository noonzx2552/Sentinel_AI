package com.sentinel.ai.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sentinel.ai.R
import com.sentinel.ai.utils.AllowedAppGate

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var adapter: AppAdapter
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AllowedAppGate.init(applicationContext)
        setContentView(R.layout.activity_app_selection)

        selected.addAll(AllowedAppGate.getAllowedPackages())

        adapter = AppAdapter(
            isSelected = { pkg -> selected.contains(pkg) },
            onToggle = { pkg, checked ->
                if (checked) {
                    selected.add(pkg)
                } else {
                    selected.remove(pkg)
                }
            }
        )

        findViewById<RecyclerView>(R.id.appList).apply {
            layoutManager = LinearLayoutManager(this@AppSelectionActivity)
            adapter = this@AppSelectionActivity.adapter
        }
        findViewById<View>(R.id.btnDone).setOnClickListener {
            AllowedAppGate.setAllowedPackages(applicationContext, selected)
            finish()
        }

        loadApps()
    }

    private fun loadApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val infos = pm.queryIntentActivities(mainIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .map { ai ->
                val label = ai.loadLabel(pm).toString()
                val icon = ai.loadIcon(pm)
                AppRow(ai.packageName, label, icon)
            }
            .sortedBy { it.label.lowercase() }
        // If nothing selected (should rarely happen), pick first available app to avoid empty state.
        if (selected.isEmpty()) {
            infos.firstOrNull()?.let {
                selected.add(it.pkg)
                AllowedAppGate.setAllowedPackages(applicationContext, selected)
            }
        }
        adapter.submit(infos)
    }

    data class AppRow(val pkg: String, val label: String, val icon: Drawable)

    private class AppAdapter(
        private val isSelected: (String) -> Boolean,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppViewHolder>() {
        private val items = mutableListOf<AppRow>()

        fun submit(data: List<AppRow>) {
            items.clear()
            items.addAll(data)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_option, parent, false)
            return AppViewHolder(view, isSelected, onToggle)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class AppViewHolder(
        view: View,
        private val isSelected: (String) -> Boolean,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.icon)
        private val name: TextView = view.findViewById(R.id.name)
        private val check: CheckBox = view.findViewById(R.id.check)

        fun bind(row: AppRow) {
            icon.setImageDrawable(row.icon)
            name.text = row.label
            check.setOnCheckedChangeListener(null)
            check.isChecked = isSelected(row.pkg)
            check.setOnCheckedChangeListener { _, isChecked ->
                onToggle(row.pkg, isChecked)
            }
            itemView.setOnClickListener {
                val newState = !check.isChecked
                check.isChecked = newState
                onToggle(row.pkg, newState)
            }
        }
    }
}
