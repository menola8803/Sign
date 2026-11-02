package se.snc.sign

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Utvecklarsidan: version, uppdateringar och vad varje version innebär.
 * Uppdateringen hämtas från GitHub; texterna om vad som är nytt kommer från changelog.json i repot.
 */
class DevActivity : BaseActivity() {

    private lateinit var status: TextView
    private lateinit var btn: Button
    private var latest: UpdateChecker.Release? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dev)
        fadedTopScreen(findViewById(R.id.devRoot), findViewById(R.id.scroll), findViewById(R.id.statusBg), findViewById(R.id.topScrim))
        backTitle(findViewById(R.id.btnBack), getString(R.string.dev_title))

        findViewById<TextView>(R.id.headline).text = buildSpannedString {
            append(getString(R.string.dev_headline_1) + " ")
            color(ContextCompat.getColor(this@DevActivity, R.color.mint)) { append(getString(R.string.dev_headline_2)) }
        }
        findViewById<TextView>(R.id.installedValue).text =
            tr(R.string.version_value, UpdateChecker.currentVersionName(this), UpdateChecker.currentBuild(this))

        status = findViewById(R.id.devStatus)
        btn = findViewById(R.id.btnDevUpdate)
        btn.setOnClickListener {
            val r = latest
            if (r != null && r.build > UpdateChecker.currentBuild(this)) UpdateChecker.open(this, r.apkUrl) else load()
        }
        findViewById<View>(R.id.rowAllVersions).setOnClickListener { UpdateChecker.open(this, "${UpdateChecker.REPO_URL}/releases") }
        load()
    }

    private fun load() {
        btn.isEnabled = false
        status.setTextColor(ContextCompat.getColor(this, R.color.muted))
        status.setText(R.string.update_searching)
        lifecycleScope.launch {
            try {
                val r = UpdateChecker.latest(this@DevActivity)
                Prefs.setLastUpdateCheck(this@DevActivity, System.currentTimeMillis())
                latest = r
                findViewById<TextView>(R.id.latestValue).text = tr(R.string.version_value, r.versionName, r.build)
                if (r.build > UpdateChecker.currentBuild(this@DevActivity)) {
                    status.setTextColor(ContextCompat.getColor(this@DevActivity, R.color.gold))
                    status.text = tr(R.string.update_available, r.versionName)
                    btn.text = tr(R.string.update_download, r.versionName)
                } else {
                    status.setTextColor(ContextCompat.getColor(this@DevActivity, R.color.mint))
                    status.setText(R.string.update_latest)
                    btn.setText(R.string.update_check)
                }
            } catch (e: Exception) {
                status.setTextColor(ContextCompat.getColor(this@DevActivity, R.color.danger))
                status.text = e.message ?: getString(R.string.update_no_net)
            } finally {
                btn.isEnabled = true
            }
        }
        loadChangelog()
        loadCommits()
    }

    /** Vad den nuvarande versionen innebär, vad som är nytt och vad tidigare versioner innehöll. */
    private fun loadChangelog() {
        val current = findViewById<LinearLayout>(R.id.currentRelease)
        val newer = findViewById<LinearLayout>(R.id.newerReleases)
        val prev = findViewById<LinearLayout>(R.id.previousReleases)
        val newerTitle = findViewById<View>(R.id.newerTitle)
        current.removeAllViews(); newer.removeAllViews(); prev.removeAllViews()
        current.addView(line(getString(R.string.changes_loading), R.color.dim))
        lifecycleScope.launch {
            try {
                val entries = UpdateChecker.changelog(this@DevActivity)
                val mine = UpdateChecker.featureVersion(UpdateChecker.currentVersionName(this@DevActivity))
                current.removeAllViews()
                val installed = entries.find { UpdateChecker.compareVersions(it.version, mine) == 0 } ?: entries.firstOrNull()
                installed?.let { current.addView(releaseCard(it, R.string.badge_installed, true)) }
                val ahead = entries.filter { UpdateChecker.compareVersions(it.version, mine) > 0 }
                newerTitle.visibility = if (ahead.isEmpty()) View.GONE else View.VISIBLE
                ahead.forEach { newer.addView(releaseCard(it, R.string.badge_new, true)) }
                val older = entries.filter { it != installed && UpdateChecker.compareVersions(it.version, mine) < 0 }
                if (older.isEmpty()) prev.addView(line(getString(R.string.no_previous), R.color.dim))
                else older.forEach { prev.addView(releaseCard(it, null, false)) }
            } catch (e: Exception) {
                current.removeAllViews()
                current.addView(line(getString(R.string.changes_failed), R.color.dim))
            }
        }
    }

    private fun loadCommits() {
        val list = findViewById<LinearLayout>(R.id.changesList)
        list.removeAllViews()
        list.addView(line(getString(R.string.changes_loading), R.color.dim))
        lifecycleScope.launch {
            try {
                val changes = UpdateChecker.recentChanges(this@DevActivity)
                list.removeAllViews()
                changes.forEach { c ->
                    list.addView(LinearLayout(this@DevActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, dp(5), 0, dp(5))
                        addView(TextView(this@DevActivity).apply {
                            text = c.date; textSize = 12f; minWidth = dp(54)
                            setTextColor(ContextCompat.getColor(context, R.color.gold))
                            typeface = ResourcesCompat.getFont(context, R.font.jakarta_bold)
                        })
                        addView(TextView(this@DevActivity).apply {
                            text = c.title; textSize = 13f
                            setTextColor(ContextCompat.getColor(context, R.color.muted))
                        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    })
                }
            } catch (e: Exception) {
                list.removeAllViews()
                list.addView(line(getString(R.string.changes_failed), R.color.dim))
            }
        }
    }

    /** Ett kort per version: nummer, datum, märke, vad den innebär och listan med ändringar. */
    private fun releaseCard(e: UpdateChecker.Entry, badge: Int?, open: Boolean): View {
        val card = layoutInflater.inflate(R.layout.item_release, null, false)
        card.findViewById<TextView>(R.id.relVersion).text = "v${e.version}"
        card.findViewById<TextView>(R.id.relTitle).text = e.title
        card.findViewById<TextView>(R.id.relDate).text = tr(R.string.released_on, e.date)
        card.findViewById<TextView>(R.id.relBadge).apply {
            if (badge == null) visibility = View.GONE else setText(badge)
        }
        val body = card.findViewById<View>(R.id.relBody)
        val arrow = card.findViewById<TextView>(R.id.relArrow)
        card.findViewById<TextView>(R.id.relSummary).text = e.summary
        val changes = card.findViewById<LinearLayout>(R.id.relChanges)
        for ((type, text) in e.changes) {
            changes.addView(layoutInflater.inflate(R.layout.item_change, changes, false).apply {
                findViewById<TextView>(R.id.chType).apply {
                    setText(
                        when (type) {
                            "improved" -> R.string.type_improved
                            "fixed" -> R.string.type_fixed
                            else -> R.string.type_new
                        }
                    )
                    setTextColor(ContextCompat.getColor(context, when (type) { "improved" -> R.color.ink; "fixed" -> R.color.mint; else -> R.color.gold }))
                }
                findViewById<TextView>(R.id.chText).text = text
            })
        }
        fun set(o: Boolean) { body.visibility = if (o) View.VISIBLE else View.GONE; arrow.text = if (o) "⌄" else "›" }
        set(open)
        card.findViewById<View>(R.id.relHead).setOnClickListener { set(body.visibility != View.VISIBLE) }
        card.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
        return card
    }

    private fun line(s: String, color: Int) = TextView(this).apply {
        text = s; textSize = 13f
        setTextColor(ContextCompat.getColor(context, color))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
