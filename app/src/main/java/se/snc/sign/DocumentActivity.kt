package se.snc.sign

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Dokumentet: sidorna visas under varandra, fält placeras med ett tryck och signeras i liggande läge. */
class DocumentActivity : BaseActivity(), PageView.Host {

    private lateinit var list: RecyclerView
    private lateinit var hint: TextView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var btnSignField: TextView
    private lateinit var btnStamp: TextView
    private lateinit var btnPhoto: TextView

    private var source: File? = null
    private var isPdf = true
    private var pages: Pages? = null
    private var docName = "document"
    private var output: File? = null
    private var outputMime = "application/pdf"
    /** Den som signerar: jag själv (null) eller en kontakt. */
    private var signer: Contact? = null

    override val fields = ArrayList<Field>()
    private var placeType: Field.Type? = null
    private var placeStamp: String? = null
    override val placing get() = placeType != null

    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }

    private val signLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data ?: return@registerForActivityResult
        if (r.resultCode != RESULT_OK) return@registerForActivityResult
        val f = fields.find { it.id == data.getLongExtra(SignActivity.EXTRA_FIELD, -1) } ?: return@registerForActivityResult
        f.sigFile = data.getStringExtra(SignActivity.EXTRA_FILE)
        f.name = if (Prefs.showName(this)) (signer?.name ?: Prefs.name(this)) else ""
        // Datum står alltid under signaturen
        f.date = Composer.today(this)
        // Fotot läggs automatiskt till vänster om signaturen (bara när jag själv signerar)
        if (signer == null && Prefs.includePhoto(this) && Store.hasPhoto(this) &&
            fields.none { it.type == Field.Type.PHOTO && it.page == f.page }) addPhotoBeside(f)
        refresh(f.page)
        updateHint()
    }

    private val savePdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> saveTo(uri) }
    private val saveDocx = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri -> saveTo(uri) }

    private fun saveTo(uri: Uri?) {
        val out = output ?: return
        if (uri == null) return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri)?.use { s -> out.inputStream().use { it.copyTo(s) } } }.isSuccess
            }
            Toast.makeText(this@DocumentActivity, if (ok) R.string.saved else R.string.export_failed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_document)

        list = findViewById(R.id.pages)
        hint = findViewById(R.id.hint)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        btnSignField = findViewById(R.id.btnSignField)
        btnStamp = findViewById(R.id.btnStamp)
        btnPhoto = findViewById(R.id.btnPhoto)
        applyInsets()

        findViewById<TextView>(R.id.docName).setOnClickListener { finish() }
        btnSignField.setOnClickListener { if (placeType == Field.Type.SIGN) setPlacing(null) else chooseSigner() }
        btnPhoto.setOnClickListener {
            when {
                placeType == Field.Type.PHOTO -> setPlacing(null)
                !Store.hasPhoto(this) -> AlertDialog.Builder(this).setMessage(R.string.photo_missing)
                    .setPositiveButton(R.string.settings) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                    .setNegativeButton(R.string.cancel, null).show()
                else -> setPlacing(Field.Type.PHOTO)
            }
        }
        btnStamp.setOnClickListener { chooseStamp() }
        findViewById<View>(R.id.btnSave).setOnClickListener { export() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()

        val uri = when (intent.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> intent.data
        }
        if (uri == null) { finish(); return }
        open(uri)
    }

    private fun applyInsets() {
        val top = findViewById<View>(R.id.topBar)
        val bottom = findViewById<View>(R.id.toolbar)
        val scrim = findViewById<View>(R.id.topScrim)
        val tp = top.paddingTop; val bp = bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.docRoot)) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            top.updatePadding(top = tp + b.top, left = b.left, right = b.right)
            bottom.updatePadding(bottom = bp + b.bottom, left = b.left, right = b.right)
            top.post {
                scrim.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top.height }
                hint.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top.height + (12 * resources.displayMetrics.density).toInt() }
                list.updatePadding(top = top.height + (16 * resources.displayMetrics.density).toInt(), bottom = bottom.height + (24 * resources.displayMetrics.density).toInt())
            }
            insets
        }
    }

    // ---- Inläsning ----

    private fun open(uri: Uri) {
        loading.visibility = View.VISIBLE
        loadingText.setText(R.string.loading)
        lifecycleScope.launch {
            try {
                val name = displayName(uri) ?: "document"
                docName = name
                findViewById<TextView>(R.id.docName).text = "‹  $name"
                val dir = File(cacheDir, "doc").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, if (name.contains('.')) name else "$name.bin")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { i -> file.outputStream().use { i.copyTo(it) } }
                        ?: error("no stream")
                }
                val head = file.inputStream().use { s -> ByteArray(5).also { s.read(it) } }
                val mime = contentResolver.getType(uri).orEmpty()
                isPdf = String(head) == "%PDF-" || mime == "application/pdf"
                val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
                pages = when {
                    isPdf -> withContext(Dispatchers.IO) { PdfPages(file) }
                    isZip || name.endsWith(".docx", true) -> DocxRenderer(this@DocumentActivity).render(file, File(cacheDir, "docx-pages")) { i, n ->
                        loadingText.text = tr(R.string.loading_page, i, n)
                    }
                    else -> throw UnsupportedOperationException()
                }
                source = file
                val p = pages!!
                findViewById<TextView>(R.id.docMeta).text =
                    tr(R.string.doc_meta, if (isPdf) "PDF" else "WORD", p.count) + if (isPdf) "" else " · " + getString(R.string.docx_note)
                // S.N.C-loggan i vänstra hörnet på första sidan
                if (Prefs.autoSeal(this@DocumentActivity) && fields.none { it.stampId == Seal.LOGO_ID }) {
                    val ar = p.widthPt(0) / p.heightPt(0)
                    val w = 0.13f
                    fields.add(Field(System.nanoTime(), Field.Type.STAMP, 0, 0.035f, 0.025f, w, w * ar, stampId = Seal.LOGO_ID))
                }
                list.adapter?.notifyDataSetChanged()
                updateHint()
            } catch (e: UnsupportedOperationException) {
                fail(R.string.unsupported)
            } catch (e: SecurityException) {
                fail(R.string.open_protected)
            } catch (e: Exception) {
                fail(R.string.open_failed)
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun fail(msg: Int) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    override fun onDestroy() {
        super.onDestroy()
        pages?.close()
        cache.evictAll()
    }

    // ---- Fält ----

    private fun setPlacing(type: Field.Type?, stamp: String? = null) {
        placeType = type; placeStamp = stamp
        btnSignField.isSelected = type == Field.Type.SIGN
        btnStamp.isSelected = type == Field.Type.STAMP
        btnPhoto.isSelected = type == Field.Type.PHOTO
        list.adapter?.notifyDataSetChanged()
        updateHint()
    }

    private fun updateHint() {
        val text = when {
            placeType == Field.Type.SIGN -> getString(R.string.tap_to_place)
            placeType == Field.Type.STAMP -> getString(R.string.tap_to_place_stamp)
            placeType == Field.Type.PHOTO -> getString(R.string.tap_to_place_photo)
            pages != null && fields.none { it.type == Field.Type.SIGN } -> getString(R.string.no_fields)
            else -> null
        }
        hint.text = text
        hint.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    override fun place(page: Int, fx: Float, fy: Float) {
        val p = pages ?: return
        val type = placeType ?: return
        val ar = p.widthPt(page) / p.heightPt(page) // bredd/höjd
        val w: Float; val h: Float
        when (type) {
            Field.Type.SIGN -> { w = 0.4f; h = (w * ar) / 3f }
            Field.Type.PHOTO -> { w = 0.13f; h = w * ar * 4f / 3f }
            Field.Type.STAMP -> { w = 0.22f; h = w * ar }
        }
        val f = Field(
            id = System.nanoTime(), type = type, page = page,
            x = (fx - w / 2).coerceIn(0f, 1f - w), y = (fy - h / 2).coerceIn(0f, 1f - h), w = w, h = h,
            stampId = placeStamp,
        )
        fields.add(f)
        setPlacing(null)
        if (type == Field.Type.SIGN) sign(f)
    }

    /** Lägger fotot till vänster om signaturen; flyttar signaturen åt höger om det behövs för att få plats. */
    private fun addPhotoBeside(sig: Field) {
        val p = pages ?: return
        val ar = p.widthPt(sig.page) / p.heightPt(sig.page)
        val h = sig.h
        val w = (h / ar) * 3f / 4f
        val gap = 0.012f
        if (sig.x < w + gap) sig.x = (w + gap).coerceAtMost(1f - sig.w)
        val x = (sig.x - gap - w).coerceAtLeast(0f)
        fields.add(Field(System.nanoTime(), Field.Type.PHOTO, sig.page, x, sig.y, w, h))
    }

    /** Vem ska signera? Jag själv eller någon i kontaktlistan. */
    private fun chooseSigner() {
        val contacts = Contacts.all(this)
        if (contacts.isEmpty()) { signer = null; setPlacing(Field.Type.SIGN); return }
        val me = Prefs.name(this).ifBlank { getString(R.string.me) }
        val names = listOf(tr(R.string.signer_me, me)) + contacts.map { it.name } + getString(R.string.contact_add)
        AlertDialog.Builder(this)
            .setTitle(R.string.who_signs)
            .setItems(names.toTypedArray()) { _, i ->
                when {
                    i == 0 -> { signer = null; setPlacing(Field.Type.SIGN) }
                    i <= contacts.size -> { signer = contacts[i - 1]; setPlacing(Field.Type.SIGN) }
                    else -> startActivity(Intent(this, ContactsActivity::class.java))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun sign(field: Field) {
        val n = fields.filter { it.type == Field.Type.SIGN }.indexOf(field) + 1
        signLauncher.launch(
            Intent(this, SignActivity::class.java)
                .putExtra(SignActivity.EXTRA_FIELD, field.id)
                .putExtra(SignActivity.EXTRA_TITLE, tr(R.string.sign_where, n, field.page + 1) + " · " + (signer?.name ?: Prefs.name(this).ifBlank { getString(R.string.me) }))
                .putExtra(SignActivity.EXTRA_SHOW_PHOTO, signer == null)
        )
    }

    override fun remove(field: Field) {
        fields.remove(field)
        refresh(field.page)
        updateHint()
    }

    override fun changed(field: Field) {
        refresh(field.page)
    }

    private fun refresh(page: Int) {
        list.adapter?.notifyItemChanged(page)
    }

    private fun chooseStamp() {
        if (placeType == Field.Type.STAMP) { setPlacing(null); return }
        val stamps = Store.stamps(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_stamp)
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, stamps.map { it.name })) { _, i ->
                setPlacing(Field.Type.STAMP, stamps[i].id)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Spara ----

    private fun export() {
        if (pages == null || source == null) return
        if (fields.none { it.type == Field.Type.SIGN && it.done }) {
            Toast.makeText(this, R.string.nothing_to_save, Toast.LENGTH_LONG).show(); return
        }
        val go = {
            // Word kan bara sparas som Word om originalet var Word
            if (isPdf) build(false)
            else AlertDialog.Builder(this)
                .setTitle(R.string.choose_format)
                .setItems(arrayOf(getString(R.string.format_docx), getString(R.string.format_pdf))) { _, i -> build(i == 0) }
                .setNegativeButton(R.string.cancel, null).show()
        }
        if (fields.any { !it.done }) {
            AlertDialog.Builder(this).setMessage(R.string.unsigned_fields)
                .setPositiveButton(R.string.save_anyway) { _, _ -> go() }
                .setNegativeButton(R.string.cancel, null).show()
        } else go()
    }

    private fun build(asDocx: Boolean) {
        val p = pages ?: return
        val src = source ?: return
        loading.visibility = View.VISIBLE
        loadingText.setText(R.string.exporting)
        lifecycleScope.launch {
            try {
                val dir = File(cacheDir, "out").apply { deleteRecursively(); mkdirs() }
                val out = File(dir, Exporter.signedName(docName, if (asDocx) "docx" else "pdf"))
                val done = fields.filter { it.done }
                withContext(Dispatchers.IO) {
                    if (asDocx) Exporter.exportDocx(this@DocumentActivity, src, p as ImagePages, done, out)
                    else Exporter.export(this@DocumentActivity, src, isPdf, p, done, out)
                }
                output = out
                outputMime = if (asDocx) "application/vnd.openxmlformats-officedocument.wordprocessingml.document" else "application/pdf"
                showSaved(out, asDocx)
            } catch (e: Exception) {
                Toast.makeText(this@DocumentActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    private fun showSaved(out: File, asDocx: Boolean) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", out)
        val actions = arrayOf(getString(R.string.save_to_device), getString(R.string.share), getString(R.string.send_to_contact), getString(R.string.open_pdf))
        AlertDialog.Builder(this)
            .setTitle(R.string.saved_title)
            .setItems(actions) { _, i ->
                when (i) {
                    0 -> if (asDocx) saveDocx.launch(out.name) else savePdf.launch(out.name)
                    1 -> share(uri, null)
                    2 -> sendToContact(uri)
                    else -> runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, outputMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure { Toast.makeText(this, R.string.no_pdf_app, Toast.LENGTH_LONG).show() }
                }
            }
            .setNegativeButton(R.string.done, null)
            .show()
    }

    private fun share(uri: Uri, to: Contact?) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = outputMime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, tr(R.string.mail_subject, docName.substringBeforeLast('.')))
            if (to != null) {
                if (to.email.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(to.email))
                putExtra(Intent.EXTRA_TEXT, tr(R.string.mail_body, to.name, Prefs.name(this@DocumentActivity)))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    /** Skickar dokumentet till en kontakt: e-postadressen fylls i automatiskt. */
    private fun sendToContact(uri: Uri) {
        val contacts = Contacts.all(this)
        if (contacts.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.no_contacts)
                .setPositiveButton(R.string.contacts) { _, _ -> startActivity(Intent(this, ContactsActivity::class.java)) }
                .setNegativeButton(R.string.cancel, null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.send_to_contact)
            .setItems(contacts.map { c -> if (c.email.isBlank()) c.name else "${c.name} · ${c.email}" }.toTypedArray()) { _, i -> share(uri, contacts[i]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Sidlistan ----

    private inner class Holder(val view: PageView) : RecyclerView.ViewHolder(view) { var job: Job? = null }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = pages?.count ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = PageView(this@DocumentActivity, this@DocumentActivity)
            val m = (14 * resources.displayMetrics.density).toInt()
            v.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(m, m / 2, m, m)
            }
            v.elevation = 6 * resources.displayMetrics.density
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val p = pages ?: return
            h.view.page = position
            h.view.aspect = p.heightPt(position) / p.widthPt(position)
            h.view.requestLayout()
            h.job?.cancel()
            val cached = cache.get(position)
            h.view.bitmap = cached
            if (cached == null) {
                val width = (list.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).coerceAtMost(1600)
                h.job = lifecycleScope.launch {
                    val b = withContext(Dispatchers.IO) { runCatching { p.render(position, width) }.getOrNull() } ?: return@launch
                    cache.put(position, b)
                    if (h.view.page == position) h.view.bitmap = b
                }
            }
            h.view.invalidate()
        }
    }
}
