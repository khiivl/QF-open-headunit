package com.andrerinas.openheadunit.thirdparty

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.ToastUtils

/**
 * Choose which installed apps may receive the phone's navigation and media data (see [ThirdPartyFrameForwarder]).
 * Nothing is selected by default. Adding an app pins its signing certificate; an app that was replaced by one signed with another
 * key stops receiving until it is approved again.
 */
class ThirdPartyAccessActivity : AppCompatActivity() {

    private class Row(val pkg: String, val label: String)

    private lateinit var store: ThirdPartyAccessStore
    private lateinit var adapter: Adapter
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.third_party_access_title)
        store = ThirdPartyAccessStore(this)
        rows = installedApps()

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.addView(TextView(this).apply { text = getString(R.string.third_party_access_explainer) })
        root.addView(Switch(this).apply {
            text = getString(R.string.third_party_album_art)
            isChecked = store.includeAlbumArt
            setPadding(0, pad, 0, pad)
            setOnCheckedChangeListener { _, on -> store.includeAlbumArt = on }
        })
        adapter = Adapter()
        root.addView(ListView(this).apply { this.adapter = this@ThirdPartyAccessActivity.adapter }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    /** launchable apps and user-installed apps, without this app, sorted by name */
    private fun installedApps(): List<Row> {
        val pm = packageManager
        return pm.getInstalledApplications(0)
            .filter { it.packageName != packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { Row(it.packageName, pm.getApplicationLabel(it).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    private fun state(row: Row): String {
        val entry = store.entries().firstOrNull { it.pkg == row.pkg } ?: return ""
        val now = ThirdPartyAccessStore.installedDigest(packageManager, row.pkg)
        return if (now == entry.certDigest) getString(R.string.third_party_state_allowed) else getString(R.string.third_party_state_changed)
    }

    private fun toggle(row: Row) {
        val allowed = store.entries().firstOrNull { it.pkg == row.pkg }
        val current = ThirdPartyAccessStore.installedDigest(packageManager, row.pkg)
        if (allowed != null && allowed.certDigest == current) {
            store.remove(row.pkg)
            adapter.notifyDataSetChanged()
            return
        }
        if (current == null) {
            ToastUtils.showToast(this, R.string.third_party_no_signature, Toast.LENGTH_LONG, force = true)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(row.label)
            .setMessage(getString(R.string.third_party_confirm, row.label, row.pkg))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                store.add(ThirdPartyForwardPolicy.Entry(row.pkg, current))
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = rows[position]
            val v = convertView as? LinearLayout ?: LinearLayout(this@ThirdPartyAccessActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val p = (12 * resources.displayMetrics.density).toInt()
                setPadding(0, p, 0, p)
                addView(CheckBox(context).apply { isClickable = false; isFocusable = false })
                addView(TextView(context).apply { tag = "label"; setPadding(p, 0, 0, 0) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val s = state(row)
            (v.getChildAt(0) as CheckBox).isChecked = s == getString(R.string.third_party_state_allowed)
            (v.findViewWithTag<TextView>("label")).text = row.label + "\n" + row.pkg + (if (s.isEmpty()) "" else "  ·  $s")
            v.setOnClickListener { toggle(row) }
            return v
        }
    }
}
