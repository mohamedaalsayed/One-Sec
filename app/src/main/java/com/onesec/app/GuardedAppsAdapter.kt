package com.onesec.app

import android.content.Context
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GuardedAppsAdapter(
    private val context: Context,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<GuardedAppsAdapter.ViewHolder>() {

    private val packages = mutableListOf<String>()
    private val pm: PackageManager = context.packageManager

    fun setPackages(list: List<String>) {
        packages.clear()
        packages.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_guarded_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pkg = packages[position]
        holder.bind(pkg)
    }

    override fun getItemCount() = packages.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val tvStats: TextView = view.findViewById(R.id.tvStats)
        private val btnRemove: TextView = view.findViewById(R.id.btnRemove)

        fun bind(pkg: String) {
            // App name and icon
            try {
                val info = pm.getApplicationInfo(pkg, 0)
                tvName.text = pm.getApplicationLabel(info).toString()
                ivIcon.setImageDrawable(pm.getApplicationIcon(info))
            } catch (e: PackageManager.NameNotFoundException) {
                tvName.text = pkg
                ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // Today's stats
            val attempts = AppPrefs.getAttemptsForPackage(context, pkg)
            val pauses = AppPrefs.getPausesForPackage(context, pkg)
            tvStats.text = when {
                attempts == 0 -> "no attempts today"
                attempts == 1 -> "1 pause today"
                else -> "$pauses paused · ${attempts - pauses} opened"
            }

            btnRemove.setOnClickListener {
                onRemove(pkg)
            }
        }
    }
}
