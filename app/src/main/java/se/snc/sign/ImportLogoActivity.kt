package se.snc.sign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Läs in eller redigera en logga.
 * - Beskär, ta bort bakgrunden automatiskt (mindre ↔ mer), sudda, återställ och ta bort samma färg.
 * - Ångra och Gör om i många steg. Nyp för att zooma, dra med två fingrar för att flytta.
 * - Det som blir kvar behåller sina färger; det som tas bort blir genomskinligt (PNG).
 * - Originalet och stegen sparas, så att loggan kan öppnas och redigeras igen.
 */
class ImportLogoActivity : BaseActivity() {

    companion object {
        const val EXTRA_PATH = "se.snc.sign.LOGO_PATH"
        /** Redigera en sparad logga. */
        const val EXTRA_STAMP_ID = "se.snc.sign.STAMP_ID"
        private const val WORK_MAX = 1600
    }

    private var edit: LogoEdit? = null
    private var stampId: String? = null
    private lateinit var canvas: LogoCanvas
    private lateinit var btnUndo: TextView
    private lateinit var btnRedo: TextView
    private lateinit var name: android.widget.EditText
    private lateinit var toolOptions: LinearLayout
    private lateinit var removeSwitch: View
    private val toolViews = HashMap<LogoCanvas.Tool, TextView>()
    private var renderJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = this
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = FrameLayout(c).apply { setBackgroundColor(Kit.color(c, R.color.bg)) }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        val statusBg = View(c).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(statusBg, FrameLayout.LayoutParams(-1, 0))

        // Överst: tillbaka, ångra och gör om
        stampId = intent.getStringExtra(EXTRA_STAMP_ID)
        val top = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        top.addView(Kit.text(c, "‹  " + getString(if (stampId != null) R.string.edit_logo else R.string.import_logo), 19f, isBold = true).apply {
            minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.drawable.bg_row); setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        btnUndo = Kit.button(c, "↶") { edit?.undo(); changed() }.apply { textSize = 20f; contentDescription = getString(R.string.undo) }
        btnRedo = Kit.button(c, "↷") { edit?.redo(); changed() }.apply { textSize = 20f; contentDescription = getString(R.string.redo) }
        top.addView(btnUndo, LinearLayout.LayoutParams(dp(52), dp(44)))
        top.addView(btnRedo, LinearLayout.LayoutParams(dp(52), dp(44)).apply { marginStart = dp(8) })
        col.addView(top)

        // Bilden
        canvas = LogoCanvas(c) { changed() }
        col.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))

        // Verktygen
        val panel = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        val scroll = ScrollView(c).apply { addView(panel); setBackgroundResource(R.drawable.bg_sheet) }
        col.addView(scroll, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.42f).toInt()))

        val tools = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        for ((t, label) in listOf(
            LogoCanvas.Tool.CROP to "⌗  " + getString(R.string.tool_crop),
            LogoCanvas.Tool.ERASE to "⌫  " + getString(R.string.tool_erase),
            LogoCanvas.Tool.RESTORE to "✚  " + getString(R.string.tool_restore),
            LogoCanvas.Tool.WAND to "✦  " + getString(R.string.tool_wand),
        )) {
            val v = Kit.text(c, label, 12f, R.color.ink, isBold = true).apply {
                gravity = Gravity.CENTER; minHeight = dp(44); setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { selectTool(t) }
            }
            toolViews[t] = v
            tools.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (tools.childCount > 0) marginStart = dp(6) })
        }
        panel.addView(tools)
        toolOptions = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(toolOptions, Kit.lp(c, top = 8))

        // Bakgrund i förhandsvisningen
        panel.addView(Kit.text(c, getString(R.string.preview_on), 12.5f, R.color.dim), Kit.lp(c, top = 12))
        panel.addView(Kit.segmented(c, listOf(getString(R.string.backdrop_checker), getString(R.string.backdrop_paper), getString(R.string.backdrop_dark)), 0) { i ->
            canvas.backdrop = when (i) { 1 -> LogoCanvas.Backdrop.PAPER; 2 -> LogoCanvas.Backdrop.DARK; else -> LogoCanvas.Backdrop.CHECKER }
        }, Kit.lp(c, top = 4))

        // Automatisk bakgrundsborttagning
        panel.addView(Kit.divider(c))
        removeSwitch = Kit.switch(c, getString(R.string.remove_background), true, getString(R.string.remove_background_sub)) { on ->
            edit?.let { it.change(it.settings.copy(removeBg = on)); changed() }
        }
        panel.addView(removeSwitch)
        panel.addView(Kit.text(c, getString(R.string.bg_less_more), 12.5f, R.color.dim), Kit.lp(c, top = 6))
        val strength = SeekBar(c).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val e = edit ?: return
                    e.change(e.settings.copy(strength = (progress - 50) / 50f, removeBg = true)); changed()
                }
            })
        }
        panel.addView(strength, Kit.lp(c, top = 2))
        panel.addView(Kit.switch(c, getString(R.string.darken_white), false, getString(R.string.darken_white_sub)) { on ->
            edit?.let { it.change(it.settings.copy(darken = on)); changed() }
        }, Kit.lp(c, top = 4))
        panel.addView(Kit.text(c, getString(R.string.logo_screen_tip), 12f, R.color.dim), Kit.lp(c, top = 8))

        // Namn och spara
        panel.addView(Kit.divider(c))
        name = Kit.input(c, getString(R.string.seal_name), "")
        panel.addView(name)
        panel.addView(Kit.button(c, getString(R.string.save_as_seal), primary = true) { save() }, Kit.lp(c, top = 10))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            statusBg.updateLayoutParams<ViewGroup.LayoutParams> { height = b.top }
            col.updatePadding(top = b.top, left = b.left, right = b.right)
            panel.updatePadding(bottom = dp(10) + b.bottom)
            insets
        }
        selectTool(LogoCanvas.Tool.CROP)
        load(strength)
    }

    private fun load(strength: SeekBar) {
        val c = this
        lifecycleScope.launch {
            val id = stampId
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (id != null) {
                        // Redigera: originalet (eller den sparade bilden för äldre loggor) och stegen
                        val orig = Store.stampOrig(c, id).takeIf { it.exists() } ?: Store.stampFile(c, id)
                        val bmp = BitmapFactory.decodeFile(orig.path) ?: return@runCatching null
                        val e = LogoEdit(Store.scaleDown(bmp, WORK_MAX).copy(Bitmap.Config.ARGB_8888, false))
                        val json = runCatching { JSONObject(Store.stampEdit(c, id).readText()) }.getOrNull()
                        if (json != null && Store.stampOrig(c, id).exists()) e.load(json)
                        else e.change(e.settings.copy(removeBg = false)) // äldre logga är redan klar
                        Triple(e, Store.stampName(c, id), e.render())
                    } else {
                        val path = intent.getStringExtra(EXTRA_PATH)
                        var label: String? = null
                        val bmp = if (path != null) BitmapFactory.decodeFile(path) else intent.data?.let { u ->
                            label = runCatching {
                                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                            }.getOrNull()?.substringBeforeLast('.')
                            contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
                        }
                        if (bmp == null) return@runCatching null
                        val e = LogoEdit(Store.scaleDown(bmp, WORK_MAX).copy(Bitmap.Config.ARGB_8888, false))
                        Triple(e, label ?: "", e.render())
                    }
                }.getOrNull()
            }
            if (loaded == null) { Toast.makeText(c, R.string.stamp_failed, Toast.LENGTH_LONG).show(); finish(); return@launch }
            val (e, label, bmp) = loaded
            edit = e
            canvas.edit = e
            canvas.result = bmp
            name.setText(label)
            strength.progress = ((e.settings.strength + 1f) * 50f).toInt()
            ((removeSwitch as LinearLayout).getChildAt(1) as android.widget.Switch).isChecked = e.settings.removeBg
            updateUndo()
        }
    }

    private fun selectTool(t: LogoCanvas.Tool) {
        val c = this
        canvas.tool = t
        toolViews.forEach { (k, v) ->
            val sel = k == t
            v.setBackgroundResource(if (sel) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
            v.setTextColor(Kit.color(c, if (sel) R.color.on_gold else R.color.ink))
        }
        toolOptions.removeAllViews()
        when (t) {
            LogoCanvas.Tool.CROP -> {
                toolOptions.addView(Kit.text(c, getString(R.string.crop_help), 12.5f, R.color.muted))
                toolOptions.addView(Kit.button(c, getString(R.string.crop_reset)) {
                    edit?.let { it.change(it.settings.copy(crop = android.graphics.RectF(0f, 0f, 1f, 1f))); changed() }
                }, Kit.lp(c, top = 6))
            }
            LogoCanvas.Tool.ERASE, LogoCanvas.Tool.RESTORE -> {
                toolOptions.addView(Kit.text(c, getString(if (t == LogoCanvas.Tool.ERASE) R.string.erase_help else R.string.restore_help), 12.5f, R.color.muted))
                toolOptions.addView(sizeSlider(R.string.brush_size, (canvas.brushDp - 6f) / 74f) { canvas.brushDp = 6f + it * 74f }, Kit.lp(c, top = 6))
            }
            LogoCanvas.Tool.WAND -> {
                toolOptions.addView(Kit.text(c, getString(R.string.wand_help), 12.5f, R.color.muted))
                toolOptions.addView(sizeSlider(R.string.wand_tolerance, canvas.wandTolerance) { canvas.wandTolerance = it }, Kit.lp(c, top = 6))
                toolOptions.addView(Kit.switch(c, getString(R.string.wand_everywhere), !canvas.wandContiguous, getString(R.string.wand_everywhere_sub)) {
                    canvas.wandContiguous = !it
                })
            }
        }
    }

    private fun sizeSlider(label: Int, value: Float, set: (Float) -> Unit): View {
        val c = this
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Kit.text(c, getString(label), 13f, R.color.ink), LinearLayout.LayoutParams(-2, -2))
        row.addView(SeekBar(c).apply {
            max = 100; progress = (value.coerceIn(0f, 1f) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { set(v / 100f) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    /** Något har ändrats: räkna om bilden (lite fördröjt så att snabba ändringar slås ihop). */
    private fun changed() {
        updateUndo()
        val e = edit ?: return
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            delay(40)
            val b = withContext(Dispatchers.Default) { runCatching { e.render() }.getOrNull() } ?: return@launch
            canvas.result = b
        }
    }

    private fun updateUndo() {
        val e = edit
        val u = e?.canUndo == true; val r = e?.canRedo == true
        btnUndo.isEnabled = u; btnUndo.alpha = if (u) 1f else 0.35f
        btnRedo.isEnabled = r; btnRedo.alpha = if (r) 1f else 0.35f
    }

    private fun save() {
        val e = edit ?: return
        val label = name.text.toString().trim().ifBlank { getString(R.string.my_logo) }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.Default) {
                runCatching {
                    val result = e.final() ?: return@runCatching false
                    Store.saveLogo(this@ImportLogoActivity, stampId, result, e.work, e.toJson(label), label)
                    true
                }.getOrDefault(false)
            }
            if (!ok) { Toast.makeText(this@ImportLogoActivity, R.string.logo_empty, Toast.LENGTH_LONG).show(); return@launch }
            Toast.makeText(this@ImportLogoActivity, tr(R.string.seal_saved, label), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
