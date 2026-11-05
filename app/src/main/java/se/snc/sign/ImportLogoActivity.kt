package se.snc.sign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Läser in en logga och tar bort bakgrunden så att bara symbol och text blir kvar.
 * Förhandsvisningen visar loggan på vitt papper. Sparas som sigill under "Other Seals".
 */
class ImportLogoActivity : BaseActivity() {

    companion object { const val EXTRA_PATH = "se.snc.sign.LOGO_PATH" }

    private var src: Bitmap? = null
    private var result: Bitmap? = null
    private var removeBg = true
    private var strength = 0.5f
    private var darken = false
    private var job: Job? = null
    private lateinit var preview: ImageView
    private lateinit var paperDark: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val screen = Screen(this, getString(R.string.import_logo))
        val d = resources.displayMetrics.density

        // Förhandsvisning på papper (vitt) och på mörkt, så man ser att bakgrunden är borta
        val paper = FrameLayout(c).apply { setBackgroundResource(R.drawable.bg_paper_pad) }
        preview = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; val p = (18 * d).toInt(); setPadding(p, p, p, p) }
        paper.addView(preview, FrameLayout.LayoutParams(-1, (230 * d).toInt()))
        screen.add(paper, 8)
        paperDark = FrameLayout(c).apply { setBackgroundResource(R.drawable.bg_card) }
        val darkImg = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; val p = (10 * d).toInt(); setPadding(p, p, p, p); id = android.R.id.icon }
        paperDark.addView(darkImg, FrameLayout.LayoutParams(-1, (90 * d).toInt()))
        screen.add(Kit.text(c, getString(R.string.logo_on_dark), 12.5f, R.color.dim), 10)
        screen.add(paperDark, 6)

        val card = Kit.card(c)
        card.addView(Kit.switch(c, getString(R.string.remove_background), removeBg) { removeBg = it; update() })
        card.addView(Kit.text(c, getString(R.string.bg_strength), 13f, R.color.muted), Kit.lp(c, top = 10))
        card.addView(SeekBar(c).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { strength = v / 100f; update() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, Kit.lp(c, top = 4))
        card.addView(Kit.switch(c, getString(R.string.darken_white), darken, getString(R.string.darken_white_sub)) { darken = it; update() }, Kit.lp(c, top = 6))
        screen.add(card, 14)

        val name = Kit.input(c, getString(R.string.seal_name), "")
        screen.add(name, 12)
        screen.add(Kit.button(c, getString(R.string.save_as_seal), primary = true) {
            val r = result ?: return@button
            val label = name.text.toString().ifBlank { getString(R.string.my_logo) }
            Store.addStampBitmap(c, r, label)
            Toast.makeText(c, tr(R.string.seal_saved, label), Toast.LENGTH_SHORT).show()
            finish()
        }, 12)
        screen.show()

        lifecycleScope.launch {
            val (bmp, label) = withContext<Pair<Bitmap?, String?>>(Dispatchers.IO) {
                val path = intent.getStringExtra(EXTRA_PATH)
                if (path != null) BitmapFactory.decodeFile(path) to null
                else intent.data?.let { u ->
                    val b = runCatching { contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
                    val n = runCatching {
                        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                    }.getOrNull()
                    b to n?.substringBeforeLast('.')
                } ?: (null to null)
            }
            if (bmp == null) { Toast.makeText(c, R.string.stamp_failed, Toast.LENGTH_LONG).show(); finish(); return@launch }
            src = Store.scaleDown(bmp, 1400)
            if (label != null) name.setText(label)
            update()
        }
    }

    /** Räknar om bilden en kort stund efter senaste ändringen. */
    private fun update() {
        val s = src ?: return
        job?.cancel()
        job = lifecycleScope.launch {
            delay(120)
            val r = withContext(Dispatchers.Default) { BackgroundRemover.process(s, removeBg, strength, darken) } ?: return@launch
            result = r
            preview.setImageBitmap(r)
            paperDark.findViewById<ImageView>(android.R.id.icon).setImageBitmap(r)
        }
    }
}
