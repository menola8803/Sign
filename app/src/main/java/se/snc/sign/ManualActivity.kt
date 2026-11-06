package se.snc.sign

import android.animation.LayoutTransition
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

/**
 * Manualen: kapitel som fälls ut med ett tryck, och sökning. Texterna finns på svenska och engelska
 * (strängresurser manual_*) och följer språkvalet. Uppdateras vid varje ny version.
 */
class ManualActivity : BaseActivity() {

    private class Chapter(val icon: String, val title: Int, val body: Int)

    private val chapters = listOf(
        Chapter("1", R.string.manual_start_t, R.string.manual_start_b),
        Chapter("⌗", R.string.manual_scan_t, R.string.manual_scan_b),
        Chapter("📄", R.string.manual_open_t, R.string.manual_open_b),
        Chapter("✍", R.string.manual_place_t, R.string.manual_place_b),
        Chapter("✎", R.string.manual_sigs_t, R.string.manual_sigs_b),
        Chapter("👤", R.string.manual_who_t, R.string.manual_who_b),
        Chapter("⤓", R.string.manual_save_t, R.string.manual_save_b),
        Chapter("▤", R.string.manual_saved_t, R.string.manual_saved_b),
        Chapter("⤴", R.string.manual_share_t, R.string.manual_share_b),
        Chapter("◎", R.string.manual_stamps_t, R.string.manual_stamps_b),
        Chapter("📷", R.string.manual_camera_t, R.string.manual_camera_b),
        Chapter("👥", R.string.manual_contacts_t, R.string.manual_contacts_b),
        Chapter("↻", R.string.manual_updates_t, R.string.manual_updates_b),
        Chapter("A", R.string.manual_language_t, R.string.manual_language_b),
    )

    private lateinit var list: LinearLayout
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val screen = Screen(this, getString(R.string.manual))
        screen.add(Kit.text(c, getString(R.string.manual_intro), 13.5f, R.color.muted), 4)
        screen.add(Kit.input(c, "🔍  " + getString(R.string.manual_search), "", InputType.TYPE_CLASS_TEXT) { query = it.trim(); render() }, 12)
        list = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        screen.add(list, 12)
        screen.add(Kit.text(c, tr(R.string.manual_version, UpdateChecker.featureVersion(UpdateChecker.currentVersionName(c))), 12f, R.color.dim).apply {
            gravity = Gravity.CENTER; setPadding(0, 24, 0, 8)
        })
        screen.show()
        render()
    }

    private fun render() {
        val c = this
        val d = resources.displayMetrics.density
        list.removeAllViews()
        val q = query.lowercase(Lang.locale(this))
        val shown = chapters.filter { ch -> q.isEmpty() || getString(ch.title).lowercase(Lang.locale(c)).contains(q) || getString(ch.body).lowercase(Lang.locale(c)).contains(q) }
        if (shown.isEmpty()) { list.addView(Kit.text(c, getString(R.string.no_matches), 14f, R.color.dim)); return }
        for (ch in shown) {
            val card = Kit.card(c, 12).apply { layoutTransition = LayoutTransition().apply { enableTransitionType(LayoutTransition.CHANGING) } }
            val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.drawable.bg_row) }
            head.addView(Kit.chip(c, ch.icon), LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt()))
            head.addView(Kit.text(c, getString(ch.title), 15.5f, isBold = true).apply { setPadding((12 * d).toInt(), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            val arrow = Kit.text(c, "›", 22f, R.color.gold)
            head.addView(arrow)
            val body = Kit.text(c, getString(ch.body), 14f, R.color.ink).apply {
                setLineSpacing(0f, 1.25f); setPadding(0, (10 * d).toInt(), 0, (2 * d).toInt())
                visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
            }
            arrow.text = if (body.visibility == View.VISIBLE) "⌄" else "›"
            head.setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "⌄" else "›"
            }
            card.addView(head); card.addView(body)
            list.addView(card, Kit.lp(c, top = if (list.childCount == 0) 0 else 8))
        }
    }
}
