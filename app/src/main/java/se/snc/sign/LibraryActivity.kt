package se.snc.sign

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sparade dokument: miniatyr av första sidan, status och filter. Tryck = förhandsvisning. */
class LibraryActivity : BaseActivity() {

    private lateinit var grid: LinearLayout
    private var filter = 0 // 0 alla, 1 signerade, 2 delvis, 3 ej signerade

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val screen = Screen(this, getString(R.string.saved_documents))
        screen.add(Kit.segmented(c, listOf(getString(R.string.filter_all), getString(R.string.status_signed_short),
            getString(R.string.status_partly_short), getString(R.string.status_not_short)), filter) { filter = it; render() }, 8)
        grid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        screen.add(grid, 12)
        screen.show()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val c = this
        grid.removeAllViews()
        val docs = LibDoc.all(c).filter {
            when (filter) {
                1 -> it.status == LibDoc.Status.SIGNED
                2 -> it.status == LibDoc.Status.PARTLY
                3 -> it.status == LibDoc.Status.NOT_SIGNED
                else -> true
            }
        }
        if (docs.isEmpty()) {
            grid.addView(Kit.text(c, getString(R.string.no_saved_docs), 14f, R.color.dim).apply { gravity = Gravity.CENTER; setPadding(0, dp(40), 0, 0) })
            return
        }
        docs.chunked(2).forEach { pair ->
            val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, d ->
                row.addView(DocCard.make(this, d) { openDoc(d) },
                    LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
            if (pair.size == 1) row.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(10) })
            grid.addView(row, Kit.lp(c, top = if (grid.childCount == 0) 0 else 10))
        }
    }

    private fun openDoc(d: LibDoc) = startActivity(Intent(this, DocPreviewActivity::class.java).putExtra(DocumentActivity.EXTRA_LIB_ID, d.id))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** Ett dokumentkort: miniatyr, namn, status och datum. Används på hemskärmen och i Sparade dokument. */
object DocCard {
    fun make(a: BaseActivity, d: LibDoc, onClick: () -> Unit): View {
        val dd = a.resources.displayMetrics.density
        val card = Kit.card(a, 8)
        card.setOnClickListener { onClick() }
        card.setOnLongClickListener { ShareSheet.show(a, d) { a.recreate() }; true }
        val thumb = ImageView(a).apply {
            scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true
            setBackgroundColor(0xFFFFFFFF.toInt())
            BitmapFactory.decodeFile(d.thumb.path)?.let { setImageBitmap(it) }
        }
        val frame = FrameLayout(a)
        frame.addView(thumb, FrameLayout.LayoutParams(-1, (150 * dd).toInt()))
        frame.addView(StatusChip.make(a, d), FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = (6 * dd).toInt(); marginEnd = (6 * dd).toInt()
        })
        frame.clipToOutline = true
        card.addView(frame)
        card.addView(Kit.text(a, d.name, 13.5f, isBold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, Kit.lp(a, top = 6))
        card.addView(Kit.text(a, d.format.label + " · " + Profile.stamp(a, d.updated), 11.5f, R.color.dim).apply { maxLines = 1 })
        return card
    }
}

/** Stor förhandsvisning av ett sparat dokument: bläddra mellan sidorna, se vem som saknas, fortsätt, dela eller ta bort. */
class DocPreviewActivity : BaseActivity() {

    private lateinit var doc: LibDoc

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        doc = LibDoc.get(this, intent.getStringExtra(DocumentActivity.EXTRA_LIB_ID) ?: "") ?: run { finish(); return }
        build()
    }

    private fun build() {
        val c = this
        val d = doc
        val screen = Screen(this, d.name)
        val dd = resources.displayMetrics.density

        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(StatusChip.make(c, d))
        val counter = Kit.text(c, "1 / ${d.pageCount}", 13f, R.color.muted).apply { gravity = Gravity.END }
        head.addView(counter, LinearLayout.LayoutParams(0, -2, 1f))
        screen.add(head, 6)

        // Sidorna, en i taget (svep åt sidan)
        val pager = RecyclerView(c)
        pager.layoutManager = LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false)
        PagerSnapHelper().attachToRecyclerView(pager)
        val h = (resources.displayMetrics.heightPixels * 0.56f).toInt()
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = d.pageCount
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                ImageView(c).apply {
                    layoutParams = RecyclerView.LayoutParams(parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels, h)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val p = (6 * dd).toInt(); setPadding(p, p, p, p)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val iv = holder.itemView as ImageView
                iv.setImageDrawable(null)
                lifecycleScope.launch {
                    val b = withContext(Dispatchers.IO) { renderPage(position) }
                    iv.setImageBitmap(b)
                }
            }
        }
        pager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, state: Int) {
                val i = (rv.layoutManager as LinearLayoutManager).findFirstCompletelyVisibleItemPosition()
                if (i >= 0) counter.text = "${i + 1} / ${d.pageCount}"
            }
        })
        screen.add(pager.apply { layoutParams = LinearLayout.LayoutParams(-1, h) }, 8)

        val signed = d.signedBy
        val waiting = d.waitingFor
        if (signed.isNotEmpty()) screen.add(Kit.text(c, "✓ " + tr(R.string.signed_by, signed.joinToString(", ")), 13.5f, R.color.mint), 10)
        if (waiting.isNotEmpty()) screen.add(Kit.text(c, "⏳ " + tr(R.string.waiting_for, waiting.joinToString(", ")), 13.5f, R.color.danger), 4)
        screen.add(Kit.text(c, d.format.label + " · " + tr(R.string.updated_at, Profile.stamp(c, d.updated)), 12.5f, R.color.dim), 4)

        screen.add(Kit.button(c, getString(if (d.status == LibDoc.Status.SIGNED) R.string.open_and_edit else R.string.continue_signing), primary = true) {
            startActivity(Intent(c, DocumentActivity::class.java).putExtra(DocumentActivity.EXTRA_LIB_ID, d.id))
        }, 14)
        screen.add(Kit.buttonRow(c,
            Kit.button(c, getString(R.string.share)) { ShareSheet.show(c, d) { finish() } },
            Kit.button(c, getString(R.string.delete), danger = true) { ShareSheet.confirmDelete(c, d) { finish() } }), 8)
        screen.show()
    }

    /** Sidbilden med alla fält ritade ovanpå (osignerade fält som röda streckade rutor). */
    private fun renderPage(i: Int): Bitmap? {
        val src = BitmapFactory.decodeFile(doc.pageFile(i).path) ?: return null
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        LibDoc.drawFields(this, canvas, doc.fields.filter { it.page == i }, out.width.toFloat(), out.height.toFloat())
        return out
    }
}
