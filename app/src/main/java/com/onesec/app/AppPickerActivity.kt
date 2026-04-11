package com.onesec.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AppPickerActivity : AppCompatActivity() {

    private lateinit var etSearch: EditText
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: PickerAdapter
    private lateinit var btnBack: TextView

    data class AppItem(
        val packageName: String,
        val label: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        etSearch = findViewById(R.id.etSearch)
        recycler = findViewById(R.id.recyclerApps)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }

        adapter = PickerAdapter(this) { pkg, isAdded ->
            if (isAdded) {
                AppPrefs.addGuardedPackage(this, pkg)
            } else {
                AppPrefs.removeGuardedPackage(this, pkg)
            }
            adapter.notifyItemChanged(adapter.indexOfPackage(pkg))
        }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        // Load apps in background thread to keep UI smooth
        Thread {
            val apps = loadUserApps()
            runOnUiThread {
                adapter.setApps(apps)
            }
        }.start()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun loadUserApps(): List<AppItem> {
        val pm = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        @Suppress("DEPRECATION")
        val launchables = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)

        val deduped = LinkedHashMap<String, String>()
        for (resolveInfo in launchables) {
            val pkg = resolveInfo.activityInfo?.packageName ?: continue
            if (pkg == packageName) continue

            val label = resolveInfo.loadLabel(pm).toString().ifBlank { pkg }
            deduped[pkg] = label
        }

        return deduped.entries
            .map { (pkg, label) -> AppItem(packageName = pkg, label = label) }
            .sortedBy { it.label.lowercase() }
    }

    // ---- Adapter ----

    inner class PickerAdapter(
        private val ctx: Context,
        private val onToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<PickerAdapter.VH>() {

        private val pm = ctx.packageManager
        private val allApps = mutableListOf<AppItem>()
        private val filtered = mutableListOf<AppItem>()

        fun setApps(list: List<AppItem>) {
            allApps.clear()
            allApps.addAll(list)
            filtered.clear()
            filtered.addAll(list)
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            filtered.clear()
            if (query.isBlank()) {
                filtered.addAll(allApps)
            } else {
                val q = query.lowercase()
                allApps.filterTo(filtered) { it.label.lowercase().contains(q) }
            }
            notifyDataSetChanged()
        }

        fun indexOfPackage(pkg: String): Int = filtered.indexOfFirst { it.packageName == pkg }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(ctx).inflate(R.layout.item_app_pick, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(filtered[position])
        }

        override fun getItemCount() = filtered.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
            private val tvName: TextView = view.findViewById(R.id.tvName)
            private val tvAdded: TextView = view.findViewById(R.id.tvAdded)

            fun bind(app: AppItem) {
                tvName.text = app.label
                try {
                    ivIcon.setImageDrawable(pm.getApplicationIcon(app.packageName))
                } catch (_: Exception) {
                    ivIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                val isGuarded = AppPrefs.isGuarded(ctx, app.packageName)
                tvAdded.visibility = if (isGuarded) View.VISIBLE else View.GONE

                itemView.setOnClickListener {
                    val nowGuarded = AppPrefs.isGuarded(ctx, app.packageName)
                    onToggle(app.packageName, !nowGuarded)
                    tvAdded.visibility = if (!nowGuarded) View.VISIBLE else View.GONE
                }
            }
        }
    }
}
