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

    fun fileOf(doc: LibDoc): Pair<File, String> {
        val f = doc.signedFile ?: doc.source
        val ext = f.extension.lowercase()
        val mime = DocFormat.entries.firstOrNull { it.ext == ext }?.mime ?: "application/pdf"
        return f to mime
    }

    private fun shareName(doc: LibDoc, f: File): String {
        val suffix = if (doc.signedFile != null) "-signed" else ""
        return doc.name + suffix + "." + f.extension
    }

    /** Filen kopieras med ett läsbart namn så att mottagaren ser rätt filnamn. */
    fun shareUri(activity: Activity, doc: LibDoc): Pair<Uri, String> {
        val (f, mime) = fileOf(doc)
        val dir = File(activity.cacheDir, "out").apply { deleteRecursively(); mkdirs() }
        val copy = File(dir, shareName(doc, f).replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        f.copyTo(copy, overwrite = true)
        return FileProvider.getUriForFile(activity, "${activity.packageName}.files", copy) to mime
    }

    fun show(activity: Activity, doc: LibDoc, onDeleted: (() -> Unit)? = null) {
        val (uri, mime) = shareUri(activity, doc)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, activity.tr(R.string.mail_subject, doc.name))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
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
                    text = doc.name; textSize = 15f; setTextColor(ContextCompat.getColor(activity, R.color.ink))
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
                activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, R.string.no_viewer_app, Toast.LENGTH_LONG).show()
            }
        }
        sheet.row("⤓", activity.getString(R.string.save_to_downloads)) { saveToDownloads(activity, doc) }
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

    /** Sparar filen i mappen Download (Hämtade filer). */
    fun saveToDownloads(activity: Activity, doc: LibDoc) {
        val (f, mime) = fileOf(doc)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, shareName(doc, f))
            put(MediaStore.Downloads.MIME_TYPE, mime)
        }
        val ok = runCatching {
            val uri = activity.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert")
            activity.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        }.isSuccess
        Toast.makeText(activity, if (ok) R.string.saved_downloads else R.string.export_failed, Toast.LENGTH_LONG).show()
    }
}

/** Liten etikett med dokumentets status: Signerad / 1 av 2 signerade / Inte signerad. */
object StatusChip {
    fun make(activity: Activity, doc: LibDoc): TextView = TextView(activity).apply {
        val d = activity.resources.displayMetrics.density
        textSize = 11f
        typeface = ResourcesCompat.getFont(activity, R.font.jakarta_bold)
        setPadding((7 * d).toInt(), (2 * d).toInt(), (7 * d).toInt(), (2 * d).toInt())
        when (doc.status) {
            LibDoc.Status.SIGNED -> { setText(R.string.status_signed); setTextColor(ContextCompat.getColor(activity, R.color.mint)); setBackgroundResource(R.drawable.bg_chip_ok) }
            LibDoc.Status.PARTLY -> { text = activity.tr(R.string.status_partly, doc.signDone, doc.signTotal); setTextColor(ContextCompat.getColor(activity, R.color.gold)); setBackgroundResource(R.drawable.bg_chip_part) }
            LibDoc.Status.NOT_SIGNED -> { setText(R.string.status_not_signed); setTextColor(ContextCompat.getColor(activity, R.color.danger)); setBackgroundResource(R.drawable.bg_chip_no) }
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = (4 * d).toInt() }
    }
}
