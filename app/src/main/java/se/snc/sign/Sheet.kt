package se.snc.sign

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import java.io.File

/** Ett ark som glider upp nerifrån, i appens stil. Byggs med rader, rutnät och knappar. */
class Sheet(private val activity: Activity) {

    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    val dialog = Dialog(activity)
    val body = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(22))
    }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val frame = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            addView(View(activity).apply { setBackgroundResource(R.drawable.bg_grab) },
                LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10) })
            addView(ScrollView(activity).apply { addView(body) })
        }
        dialog.setContentView(frame)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            attributes = attributes.apply { windowAnimations = android.R.style.Animation_InputMethod }
        }
    }

    private fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        text = s; textSize = size; setTextColor(ContextCompat.getColor(activity, color))
        typeface = ResourcesCompat.getFont(activity, if (bold) R.font.jakarta_bold else R.font.jakarta)
    }

    fun title(s: CharSequence, sub: CharSequence? = null): Sheet {
        body.addView(text(s, 17f, R.color.ink, true))
        if (sub != null) body.addView(text(sub, 12.5f, R.color.dim).apply { setPadding(0, dp(2), 0, dp(4)) })
        return this
    }

    fun view(v: View, top: Int = 10): Sheet {
        body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        return this
    }

    /** En rad med ikon (emoji eller bild), rubrik och undertext. */
    fun row(icon: String, title: CharSequence, sub: CharSequence? = null, danger: Boolean = false, onClick: () -> Unit): Sheet {
        val r = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9)); setBackgroundResource(R.drawable.bg_row)
            setOnClickListener { dialog.dismiss(); onClick() }
        }
        r.addView(text(icon, 16f, if (danger) R.color.danger else R.color.gold).apply {
            gravity = Gravity.CENTER; setBackgroundResource(R.drawable.bg_icon_chip)
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        col.addView(text(title, 15f, if (danger) R.color.danger else R.color.ink, true))
        if (sub != null) col.addView(text(sub, 12f, R.color.dim))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(r, LinearLayout.LayoutParams(-1, -2))
        return this
    }

    /** Rutnät med appikoner (t.ex. de vanligaste apparna att dela till). */
    fun grid(items: List<Triple<android.graphics.drawable.Drawable, String, () -> Unit>>): Sheet {
        if (items.isEmpty()) return this
        val g = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(6)) }
        for ((icon, label, click) in items.take(4)) {
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { dialog.dismiss(); click() }
            }
            cell.addView(ImageView(activity).apply {
                setImageDrawable(icon); setBackgroundResource(R.drawable.bg_icon_chip); setPadding(dp(9), dp(9), dp(9), dp(9))
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            cell.addView(text(label, 11f, R.color.muted).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(-1, -2))
            g.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(g)
        return this
    }

    /** Två knappar bredvid varandra: avbryt till vänster, huvudknapp till höger. */
    fun buttons(cancel: String?, ok: String?, onOk: (() -> Unit)? = null, danger: Boolean = false): Sheet {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        if (cancel != null) row.addView(button(cancel, false) { dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
        if (ok != null) row.addView(button(ok, true, danger) { dialog.dismiss(); onOk?.invoke() }, LinearLayout.LayoutParams(0, dp(46), 1.4f))
        body.addView(row)
        return this
    }

    fun button(label: String, primary: Boolean, danger: Boolean = false, onClick: () -> Unit) = TextView(activity).apply {
        text = label; gravity = Gravity.CENTER; textSize = 14f; minHeight = dp(46)
        setPadding(dp(10), 0, dp(10), 0)
        typeface = ResourcesCompat.getFont(activity, R.font.jakarta_bold)
        setBackgroundResource(if (danger) R.drawable.bg_button_danger else if (primary) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
        setTextColor(ContextCompat.getColor(activity, if (primary || danger) R.color.on_gold else R.color.ink))
        if (danger) setTextColor(0xFFFFFFFF.toInt())
        setOnClickListener { onClick() }
    }

    fun show(): Sheet { dialog.show(); return this }
}

/** Dela-menyn för ett sparat dokument: snabbval för vanliga appar, skicka till kontakt, dela, öppna, spara, ta bort. */
object ShareSheet {

    private val PREFERRED = listOf(
        "com.whatsapp", "com.google.android.gm", "com.google.android.apps.docs", "com.microsoft.office.outlook",
        "org.telegram.messenger", "org.thoughtcrime.securesms", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.microsoft.skydrive", "com.dropbox.android", "com.microsoft.teams", "com.Slack",
    )

    /** Det som delas: en eller flera filer (t.ex. en bild per sida) och deras typ. */
    class Pick(val files: List<File>, val mime: String, val label: String)

    /** Filerna för ett format; utan sparat format delas originalet. */
    fun pick(doc: LibDoc, f: OutFormat?): Pick {
        if (f != null) {
            val files = doc.outputFiles(f)
            if (files.isNotEmpty()) return Pick(files, f.mime, f.label)
        }
        val src = doc.source
        val mime = DocFormat.entries.firstOrNull { it.ext == src.extension.lowercase() }?.mime ?: "application/pdf"
        return Pick(listOf(src), mime, doc.format.label)
    }

    /** Alla sparade format på en gång. */
    fun pickAll(doc: LibDoc): Pick {
        val outs = doc.outputs
        return Pick(outs.flatMap { doc.outputFiles(it) }, if (outs.size == 1) outs[0].mime else "*/*", "")
    }

    private fun shareName(doc: LibDoc, f: File, index: Int, count: Int): String {
        val signed = f.name.startsWith("signed") || f.parentFile?.name?.startsWith("signed") == true
        val suffix = (if (signed) "-signed" else "") + (if (count > 1 && f.parentFile?.name?.startsWith("signed-") == true) "-page%02d".format(index + 1) else "")
        return doc.name + suffix + "." + f.extension
    }

    /** Filerna kopieras med läsbara namn så att mottagaren ser rätt filnamn. */
    fun shareUris(activity: Activity, doc: LibDoc, p: Pick): ArrayList<Uri> {
        val dir = File(activity.cacheDir, "out").apply { deleteRecursively(); mkdirs() }
        val out = ArrayList<Uri>()
        p.files.forEachIndexed { i, f ->
            val copy = File(dir, shareName(doc, f, i, p.files.size).replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            f.copyTo(copy, overwrite = true)
            out += FileProvider.getUriForFile(activity, "${activity.packageName}.files", copy)
        }
        return out
    }

    /** En fil (bakåtkompatibelt): huvudformatet eller originalet. */
    fun shareUri(activity: Activity, doc: LibDoc): Pair<Uri, String> {
        val p = pick(doc, doc.outputs.firstOrNull())
        return shareUris(activity, doc, Pick(p.files.take(1), p.mime, p.label))[0] to p.mime
    }

    /** Finns flera sparade format frågar appen först vilket som ska delas. */
    fun show(activity: Activity, doc: LibDoc, onDeleted: (() -> Unit)? = null) {
        val outs = doc.outputs
        if (outs.size <= 1) { showFor(activity, doc, pick(doc, outs.firstOrNull()), onDeleted); return }
        val s = Sheet(activity).title(activity.getString(R.string.share_which_format), doc.name)
        for (f in outs) {
            val files = doc.outputFiles(f)
            val sub = if (f.images) activity.tr(R.string.n_images, files.size) else activity.getString(formatSub(f))
            s.row(f.label.take(4), activity.tr(R.string.share_as, f.label), sub) { showFor(activity, doc, pick(doc, f), onDeleted) }
        }
        s.row("⧉", activity.getString(R.string.share_all_formats), outs.joinToString(" + ") { it.label }) { showFor(activity, doc, pickAll(doc), onDeleted) }
        s.show()
    }

    private fun formatSub(f: OutFormat) = when (f) {
        OutFormat.PDF -> R.string.format_pdf_sub
        OutFormat.DOCX -> R.string.format_docx_sub
        OutFormat.ODT -> R.string.format_odt_sub
        else -> R.string.format_image_sub
    }

    private fun sendIntent(activity: Activity, doc: LibDoc, uris: ArrayList<Uri>, mime: String) =
        (if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)).apply {
            type = mime
            putExtra(Intent.EXTRA_SUBJECT, activity.tr(R.string.mail_subject, doc.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun showFor(activity: Activity, doc: LibDoc, p: Pick, onDeleted: (() -> Unit)?) {
        val uris = shareUris(activity, doc, p)
        val send = sendIntent(activity, doc, uris, p.mime)
        val pm = activity.packageManager
        val targets = pm.queryIntentActivities(send, 0)
            .filter { it.activityInfo.packageName in PREFERRED }
            .sortedBy { PREFERRED.indexOf(it.activityInfo.packageName) }
            .distinctBy { it.activityInfo.packageName }
        val sheet = Sheet(activity)
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val d = activity.resources.displayMetrics.density
            addView(ImageView(activity).apply {
                setImageBitmap(BitmapFactory.decodeFile(doc.thumb.path)); setBackgroundColor(0xFFFFFFFF.toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, LinearLayout.LayoutParams((40 * d).toInt(), (52 * d).toInt()))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding((12 * d).toInt(), 0, 0, 0)
                addView(TextView(activity).apply {
                    text = doc.name + if (p.label.isNotBlank()) " · " + p.label else ""; textSize = 15f; setTextColor(ContextCompat.getColor(activity, R.color.ink))
                    typeface = ResourcesCompat.getFont(activity, R.font.jakarta_bold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                addView(StatusChip.make(activity, doc))
            })
        }
        sheet.view(header, 4)
        sheet.grid(targets.take(4).map { ri ->
            Triple(ri.loadIcon(pm), ri.loadLabel(pm).toString()) {
                runCatching { activity.startActivity(Intent(send).setClassName(ri.activityInfo.packageName, ri.activityInfo.name)) }
                Unit
            }
        })
        sheet.row("👥", activity.getString(R.string.send_to_contact), activity.getString(R.string.send_to_contact_sub)) { sendToContact(activity, send) }
        sheet.row("⤴", activity.getString(R.string.share_all), activity.getString(R.string.share_all_sub)) {
            activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share)))
        }
        sheet.row("📄", activity.getString(R.string.open_doc)) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uris[0], if (p.mime == "*/*") null else p.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.no_viewer_app, Toast.LENGTH_LONG).show()
            }
        }
        sheet.row("⤓", activity.getString(R.string.save_to_downloads)) { saveToDownloads(activity, doc, p) }
        if (onDeleted != null) sheet.row("🗑", activity.getString(R.string.delete), danger = true) { confirmDelete(activity, doc, onDeleted) }
        sheet.show()
    }

    fun confirmDelete(activity: Activity, doc: LibDoc, onDeleted: () -> Unit) {
        Sheet(activity)
            .title(activity.getString(R.string.delete_doc_title), doc.name)
            .view(TextView(activity).apply {
                setText(R.string.delete_doc_text); textSize = 13f; setTextColor(ContextCompat.getColor(activity, R.color.muted))
            })
            .buttons(activity.getString(R.string.cancel), activity.getString(R.string.delete), { doc.delete(); onDeleted() }, danger = true)
            .show()
    }

    private fun sendToContact(activity: Activity, send: Intent) {
        val list = ContactBook.all(activity).filter { it.email.isNotBlank() || it.phone.isNotBlank() }
        if (list.isEmpty()) {
            Toast.makeText(activity, R.string.no_contacts, Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(activity, ContactsActivity::class.java))
            return
        }
        val s = Sheet(activity).title(activity.getString(R.string.send_to_contact))
        for (c in list) {
            s.row(c.name.take(1).uppercase(), c.name, listOf(c.email, c.phone).filter { it.isNotBlank() }.joinToString(" · ")) {
                val i = Intent(send).apply {
                    if (c.email.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(c.email))
                    putExtra(Intent.EXTRA_TEXT, activity.tr(R.string.mail_body, c.name, Profile(activity, 1).signerName))
                }
                activity.startActivity(Intent.createChooser(i, activity.getString(R.string.send_to_contact)))
            }
        }
        s.show()
    }

    /** Sparar filerna i mappen Download (Hämtade filer). */
    fun saveToDownloads(activity: Activity, doc: LibDoc, p: Pick = pick(doc, doc.outputs.firstOrNull())) {
        val ok = p.files.withIndex().all { (i, f) ->
            val mime = when (f.extension.lowercase()) { "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; else -> OutFormat.of(f.extension)?.mime ?: p.mime }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, shareName(doc, f, i, p.files.size))
                put(MediaStore.Downloads.MIME_TYPE, mime)
            }
            runCatching {
                val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert")
                activity.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
            }.isSuccess
        }
        Toast.makeText(activity, if (ok) R.string.saved_downloads else R.string.export_failed, Toast.LENGTH_LONG).show()
    }
}

/**
 * Liten etikett i hörnet med dokumentets status:
 * grön "Sign 1 2" = signaturfält 1 och 2 är signerade (bara siffror, i fältens ordning),
 * röd "Unsigned" = inget fält är signerat än.
 */
object StatusChip {
    fun make(activity: Activity, doc: LibDoc): TextView = TextView(activity).apply {
        val d = activity.resources.displayMetrics.density
        textSize = 11f
        typeface = ResourcesCompat.getFont(activity, R.font.jakarta_bold)
        setPadding((7 * d).toInt(), (2 * d).toInt(), (7 * d).toInt(), (2 * d).toInt())
        val nums = doc.signedNumbers
        if (nums.isEmpty()) {
            setText(R.string.status_unsigned); setTextColor(ContextCompat.getColor(activity, R.color.danger)); setBackgroundResource(R.drawable.bg_chip_no)
        } else {
            text = activity.getString(R.string.status_sign) + " " + nums.joinToString(" ")
            setTextColor(ContextCompat.getColor(activity, R.color.mint)); setBackgroundResource(R.drawable.bg_chip_ok)
        }
        contentDescription = if (nums.isEmpty()) activity.getString(R.string.status_unsigned)
            else activity.tr(R.string.status_fields_signed, nums.joinToString(", "))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = (4 * d).toInt() }
    }

    /** Förklaringen av märkena (ⓘ vid Sparade dokument). */
    fun explain(activity: Activity) {
        val d = activity.resources.displayMetrics.density
        fun example(label: String, ok: Boolean, text: String) = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            addView(TextView(activity).apply {
                this.text = label; textSize = 11f; typeface = ResourcesCompat.getFont(activity, R.font.jakarta_bold)
                setPadding((7 * d).toInt(), (2 * d).toInt(), (7 * d).toInt(), (2 * d).toInt())
                setTextColor(ContextCompat.getColor(activity, if (ok) R.color.mint else R.color.danger))
                setBackgroundResource(if (ok) R.drawable.bg_chip_ok else R.drawable.bg_chip_no)
            })
            addView(TextView(activity).apply {
                this.text = text; textSize = 13f; setTextColor(ContextCompat.getColor(activity, R.color.muted)); setPadding((12 * d).toInt(), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        Sheet(activity).title(activity.getString(R.string.status_help_title))
            .view(example(activity.getString(R.string.status_sign) + " 1 2", true, activity.getString(R.string.status_help_sign)), 8)
            .view(example(activity.getString(R.string.status_unsigned), false, activity.getString(R.string.status_help_unsigned)), 4)
            .view(TextView(activity).apply {
                setText(R.string.status_help_more); textSize = 13f; setTextColor(ContextCompat.getColor(activity, R.color.dim))
            }, 8)
            .buttons(null, activity.getString(R.string.done), {})
            .show()
    }
}
