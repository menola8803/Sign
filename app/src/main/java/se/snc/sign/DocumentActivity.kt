package se.snc.sign

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Dokumentet: sidorna visas under varandra. Fält placeras med ett tryck, signaturer görs i liggande läge,
 * stämplar kan vridas. Allt sparas löpande i Sparade dokument, så att man kan fortsätta senare.
 */
class DocumentActivity : BaseActivity(), PageView.Host {

    companion object {
        /** Fortsätt med ett sparat dokument. */
        const val EXTRA_LIB_ID = "se.snc.sign.LIB_ID"
        /** Dokumentet kommer från "Fota dokument". */
        const val EXTRA_SCANNED = "se.snc.sign.SCANNED"
    }

    private lateinit var list: RecyclerView
    private lateinit var hint: TextView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var btnSignField: TextView
    private lateinit var btnStamp: TextView
    private lateinit var btnPhoto: TextView
    private lateinit var rotatePanel: View
    private lateinit var rotateValue: TextView
    private lateinit var rotateSeek: SeekBar

    private var doc: LibDoc? = null
    private var pages: Pages? = null
    private val isPdf get() = doc?.format == DocFormat.PDF || doc?.format == DocFormat.SCAN

    override val fields = ArrayList<Field>()
    private var placeType: Field.Type? = null
    private var placeStamp: String? = null
    override val placing get() = placeType != null

    /** Vem nästa signaturfält gäller: en profil (1–3) eller en kontakt. */
    private var nextProfile: Profile? = null
    private var nextContact: Contact? = null
    /** Fältet som väntar på resultat från signering eller kamera. */
    private var pending: Field? = null
    private var pendingProfile: Profile? = null
    private var pendingPhoto = true
    private var rotating: Field? = null

    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }

    // ---- Resultat från andra skärmar ----

    private val signLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val f = pending ?: return@registerForActivityResult
        val path = r.data?.getStringExtra(SignActivity.EXTRA_FILE)
        if (r.resultCode != RESULT_OK || path == null) { dropIfEmpty(f); return@registerForActivityResult }
        val p = pendingProfile
        if (p != null) {
            p.saveFromPad(r.data)
            fill(f, p, pendingPhoto)
        } else {
            // En kontakt signerar på den här telefonen: sparas inte som profil
            val d = doc ?: return@registerForActivityResult
            f.sigFile = d.asset(File(path), "sig")
            f.name = nextContact?.name ?: f.signer
            f.date = Composer.today(this)
            changed(f)
        }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val f = pending ?: return@registerForActivityResult
        val p = pendingProfile ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK && p.hasSignature) fill(f, p, pendingPhoto) else dropIfEmpty(f)
    }

    private val sigFileLauncher = registerForActivityResult(OpenInDownloads()) { uri ->
        val f = pending ?: return@registerForActivityResult
        val p = pendingProfile ?: return@registerForActivityResult
        if (uri == null) { dropIfEmpty(f); return@registerForActivityResult }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching false
                    val ink = InkExtractor.extract(Store.scaleDown(bmp, 2000)) ?: return@runCatching false
                    p.saveSignature(ink, Profile.SOURCE_FILE); true
                }.getOrDefault(false)
            }
            if (ok) fill(f, p, pendingPhoto) else {
                Toast.makeText(this@DocumentActivity, R.string.sig2_not_found, Toast.LENGTH_LONG).show(); dropIfEmpty(f)
            }
        }
    }

    // ---- Start ----

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
        rotatePanel = findViewById(R.id.rotatePanel)
        rotateValue = findViewById(R.id.rotateValue)
        rotateSeek = findViewById(R.id.rotateSeek)
        applyInsets()

        findViewById<TextView>(R.id.docName).setOnClickListener { finish() }
        btnSignField.setOnClickListener { if (placeType == Field.Type.SIGN) setPlacing(null) else chooseSigner() }
        btnPhoto.setOnClickListener { choosePhoto() }
        btnStamp.setOnClickListener { chooseStamp() }
        findViewById<View>(R.id.btnSave).setOnClickListener { export() }
        bindRotatePanel()

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = Adapter()

        val libId = intent.getStringExtra(EXTRA_LIB_ID)
        if (libId != null) { openSaved(libId); return }
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
        val statusBg = findViewById<View>(R.id.statusBg)
        val tp = top.paddingTop; val bp = bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.docRoot)) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            statusBg.updateLayoutParams<ViewGroup.LayoutParams> { height = b.top }
            top.updatePadding(top = tp + b.top, left = b.left, right = b.right)
            bottom.updatePadding(bottom = bp + b.bottom, left = b.left, right = b.right)
            top.post {
                val dd = resources.displayMetrics.density
                scrim.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top.height }
                hint.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = top.height + (12 * dd).toInt() }
                rotatePanel.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bottom.height + (10 * dd).toInt() }
                list.updatePadding(top = top.height + (16 * dd).toInt(), bottom = bottom.height + (24 * dd).toInt())
            }
            insets
        }
    }

    // ---- Inläsning ----

    private fun setTitle(d: LibDoc) {
        findViewById<TextView>(R.id.docName).text = "‹  ${d.name}"
        val p = pages ?: return
        findViewById<TextView>(R.id.docMeta).text = tr(R.string.doc_meta, d.format.label, p.count)
    }

    /** Öppnar ett nytt dokument: formatet känns igen, det sparas i Sparade dokument och sidorna ritas upp. */
    private fun open(uri: Uri) {
        loading.visibility = View.VISIBLE
        loadingText.setText(R.string.loading)
        lifecycleScope.launch {
            try {
                val scanned = intent.getBooleanExtra(EXTRA_SCANNED, false)
                val name = if (scanned) tr(R.string.scan_name, Profile.stamp(this@DocumentActivity, System.currentTimeMillis()).replace(" · ", " "))
                    else displayName(uri) ?: "document"
                val tmp = File(cacheDir, "incoming").apply { deleteRecursively(); mkdirs() }.let { File(it, "file") }
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: error("no stream")
                }
                var format = DocFormat.detect(tmp, name) ?: throw UnsupportedOperationException()
                if (scanned && format == DocFormat.PDF) format = DocFormat.SCAN
                if (format == DocFormat.PDF || format == DocFormat.SCAN) {
                    if (!withContext(Dispatchers.IO) { pdfOpens(tmp) }) {
                        val pw = askPassword() ?: run { finish(); return@launch }
                        val ok = withContext(Dispatchers.IO) { decrypt(tmp, pw) }
                        if (!ok) { fail(R.string.wrong_password); return@launch }
                    }
                }
                val d = withContext(Dispatchers.IO) { LibDoc.create(this@DocumentActivity, tmp, name, format) }
                doc = d
                renderPages(d)
                addCornerStamp(d)
                saveDoc(thumb = true)
                setTitle(d)
                list.adapter?.notifyDataSetChanged()
                updateHint()
            } catch (e: UnsupportedOperationException) {
                fail(R.string.unsupported)
            } catch (e: Exception) {
                fail(R.string.open_failed)
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    /** Fortsätter med ett sparat dokument. */
    private fun openSaved(id: String) {
        val d = LibDoc.get(this, id) ?: run { finish(); return }
        doc = d
        loading.visibility = View.VISIBLE
        loadingText.setText(R.string.loading)
        lifecycleScope.launch {
            try {
                pages = withContext(Dispatchers.IO) {
                    if (d.format == DocFormat.PDF || d.format == DocFormat.SCAN) PdfPages(d.source)
                    else ImagePages((0 until d.pageCount).map { d.pageFile(it) }, d.pageSizes, d.anchors)
                }
                fields.clear(); fields.addAll(d.fields)
                setTitle(d)
                list.adapter?.notifyDataSetChanged()
                updateHint()
            } catch (e: Exception) {
                fail(R.string.open_failed)
            } finally {
                loading.visibility = View.GONE
            }
        }
    }

    /** Ritar upp sidorna och sparar sidbilderna i dokumentets mapp. */
    private suspend fun renderPages(d: LibDoc) {
        val progress: (Int, Int) -> Unit = { i, n -> loadingText.text = tr(R.string.loading_page, i, n) }
        when (d.format) {
            DocFormat.PDF, DocFormat.SCAN -> {
                val p = withContext(Dispatchers.IO) { PdfPages(d.source) }
                pages = p
                d.pageSizes = (0 until p.count).map { p.widthPt(it) to p.heightPt(it) }
                withContext(Dispatchers.IO) {
                    for (i in 0 until p.count) {
                        val b = p.render(i, 1000)
                        FileOutputStream(d.pageFile(i)).use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    }
                }
            }
            DocFormat.DOCX -> setImagePages(d, DocxRenderer(this).render(d.source, d.pagesDir, progress))
            DocFormat.ODT -> setImagePages(d, DocxRenderer(this).render(d.source, d.pagesDir, progress, mode = "odt"))
            DocFormat.DOC -> {
                val conv = withContext(Dispatchers.IO) { DocToHtml.convert(d.source) }
                setImagePages(d, DocxRenderer(this).render(d.source, d.pagesDir, progress, mode = "html", html = conv.html, layoutJson = conv.layoutJson))
            }
        }
    }

    private fun setImagePages(d: LibDoc, p: ImagePages) {
        pages = p
        d.pageSizes = (0 until p.count).map { p.widthPt(it) to p.heightPt(it) }
        d.anchors = p.anchors
    }

    /** Den valda stämpeln (standard: S.N.C-loggan) i vänstra hörnet på första sidan. */
    private fun addCornerStamp(d: LibDoc) {
        val p = pages ?: return
        val corner = Prefs.cornerStamp(this)
        if (!Prefs.autoSeal(this) || fields.any { it.stampId == corner }) return
        val ar = p.widthPt(0) / p.heightPt(0)
        val bmp = Store.stampBitmap(this, corner)
        val ratio = if (bmp != null && bmp.width > 0) bmp.height.toFloat() / bmp.width else 1f
        val w = 0.13f
        fields.add(Field(System.nanoTime(), Field.Type.STAMP, 0, 0.035f, 0.025f, w, (w * ar * ratio).coerceAtMost(0.25f), stampId = corner))
        d.fields = fields
    }

    private fun pdfOpens(f: File): Boolean = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).close() }
    }.isSuccess

    /** Tar bort lösenordsskyddet så att dokumentet kan visas och signeras. */
    private fun decrypt(f: File, password: String): Boolean = runCatching {
        PDFBoxResourceLoader.init(applicationContext)
        val out = File(f.parentFile, "decrypted.pdf")
        PDDocument.load(f, password).use { it.isAllSecurityToBeRemoved = true; it.save(out) }
        out.copyTo(f, overwrite = true); true
    }.getOrDefault(false)

    private suspend fun askPassword(): String? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setHint(R.string.password); setTextColor(ContextCompat.getColor(context, R.color.ink))
            setBackgroundResource(R.drawable.bg_button_outline)
            val p = (12 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        }
        var answered = false
        Sheet(this).title(getString(R.string.password_title), getString(R.string.password_text))
            .view(input)
            .buttons(getString(R.string.cancel), getString(R.string.open_doc), { answered = true; cont.resumeWith(Result.success(input.text.toString())) })
            .also { s -> s.dialog.setOnDismissListener { if (!answered && cont.isActive) cont.resumeWith(Result.success(null)) } }
            .show()
    }

    private fun fail(msg: Int) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        doc?.let { d -> if (d.signTotal == 0 && fields.none { it.type != Field.Type.STAMP }) d.delete() }
        doc = null
        finish()
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    override fun onPause() {
        super.onPause()
        saveDoc(thumb = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        pages?.close()
        cache.evictAll()
    }

    /** Sparar fälten (och miniatyren) i Sparade dokument. */
    private fun saveDoc(thumb: Boolean = false) {
        val d = doc ?: return
        d.fields = fields
        d.save()
        if (thumb) {
            val first = cache.get(0)
            lifecycleScope.launch(Dispatchers.IO) { runCatching { d.makeThumb(this@DocumentActivity, first) } }
        }
    }

    // ---- Fält ----

    private fun setPlacing(type: Field.Type?, stamp: String? = null) {
        placeType = type; placeStamp = stamp
        btnSignField.isSelected = type == Field.Type.SIGN
        btnStamp.isSelected = type == Field.Type.STAMP
        btnPhoto.isSelected = type == Field.Type.PHOTO
        if (type != null) hideRotate()
        list.adapter?.notifyDataSetChanged()
        updateHint()
    }

    private fun updateHint() {
        val text = when (placeType) {
            Field.Type.SIGN -> tr(R.string.tap_to_place_for, nextProfile?.signerName?.ifBlank { null } ?: nextContact?.name ?: getString(R.string.me))
            Field.Type.STAMP -> getString(R.string.tap_to_place_stamp)
            Field.Type.PHOTO -> getString(R.string.tap_to_place_photo)
            null -> if (pages != null && fields.none { it.type == Field.Type.SIGN }) getString(R.string.no_fields) else null
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
            Field.Type.STAMP -> {
                val bmp = placeStamp?.let { Store.stampBitmap(this, it) }
                val ratio = if (bmp != null && bmp.width > 0) bmp.height.toFloat() / bmp.width else 1f
                w = 0.22f; h = (w * ar * ratio).coerceAtMost(0.4f)
            }
        }
        val f = Field(
            id = System.nanoTime(), type = type, page = page,
            x = (fx - w / 2).coerceIn(0f, 1f - w), y = (fy - h / 2).coerceIn(0f, 1f - h), w = w, h = h,
            stampId = placeStamp,
        )
        if (type == Field.Type.PHOTO) {
            val pr = photoProfile
            if (pr?.hasPhoto == true) { f.sigFile = doc?.asset(pr.photoFile, "photo"); f.profile = pr.n }
        }
        fields.add(f)
        val profile = nextProfile; val contact = nextContact
        setPlacing(null)
        if (type == Field.Type.SIGN) {
            f.signer = profile?.signerName ?: contact?.name ?: ""
            f.profile = profile?.n ?: 0
            if (profile != null) startProfileSign(f, profile) else signAsContact(f)
        } else changed(f)
        if (type == Field.Type.STAMP) { rotating = f; selected(f) }
    }

    /** Signatur med en profil: finns en sparad signatur frågar appen först om den ska användas. */
    private fun startProfileSign(f: Field, p: Profile) {
        if (p.hasSignature) askUseSaved(f, p) else newSignature(f, p, withPhoto = p.showPhoto)
    }

    /** Frågan innan den sparade signaturen och fotot används, så att det inte blir fel. */
    private fun askUseSaved(f: Field, p: Profile) {
        val dd = resources.displayMetrics.density
        val preview = ImageView(this).apply {
            setBackgroundResource(R.drawable.bg_stamp)
            val pad = (8 * dd).toInt(); setPadding(pad, pad, pad, pad)
            val demo = Field(0, Field.Type.SIGN, 0, 0f, 0f, 1f, 1f, sigFile = p.sigFile.path,
                name = if (p.showName) p.signerName else "", date = if (p.showDate) Composer.today(this@DocumentActivity) else "")
            setImageBitmap(previewWithPhoto(demo, p, p.showPhoto && p.hasPhoto))
            adjustViewBounds = true
        }
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(stampLine("✍ " + tr(sourceText(p.sigSource), Profile.stamp(this@DocumentActivity, p.sigAt))))
            if (p.hasPhoto) addView(stampLine("📷 " + tr(R.string.photo_taken_at, Profile.stamp(this@DocumentActivity, p.photoAt))))
            addView(TextView(this@DocumentActivity).apply {
                setText(R.string.check_right_person); textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.dim)); setPadding(0, (8 * dd).toInt(), 0, 0)
            })
        }
        var handled = false
        val s = Sheet(this).title(tr(R.string.use_saved_title, p.n), p.signerName.ifBlank { null })
            .view(preview).view(times, 8)
        val useLabel = if (p.showPhoto && p.hasPhoto) R.string.use_sig_and_photo else R.string.use_signature
        s.view(s.button(getString(useLabel), true) { handled = true; s.dialog.dismiss(); fill(f, p, p.showPhoto) }
            .apply { layoutParams = LinearLayout.LayoutParams(-1, (48 * dd).toInt()) }, 14)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        row1.addView(s.button(getString(R.string.edit_signature), false) {
            handled = true; s.dialog.dismiss(); newSignature(f, p, p.showPhoto)
        }, LinearLayout.LayoutParams(0, (44 * dd).toInt(), 1f).apply { marginEnd = (8 * dd).toInt() })
        row1.addView(s.button(getString(R.string.new_photo), false) {
            handled = true; s.dialog.dismiss()
            pending = f; pendingProfile = p; pendingPhoto = true
            cameraLauncher.launch(Intent(this, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_PROFILE, p.n))
        }, LinearLayout.LayoutParams(0, (44 * dd).toInt(), 1f))
        s.view(row1, 8)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        row2.addView(s.button(getString(R.string.without_photo), false) { handled = true; s.dialog.dismiss(); fill(f, p, false) },
            LinearLayout.LayoutParams(0, (44 * dd).toInt(), 1f).apply { marginEnd = (8 * dd).toInt() })
        row2.addView(s.button(getString(R.string.cancel), false) { s.dialog.dismiss() }, LinearLayout.LayoutParams(0, (44 * dd).toInt(), 1f))
        s.view(row2, 8)
        s.dialog.setOnDismissListener { if (!handled) dropIfEmpty(f) }
        s.show()
    }

    private fun stampLine(t: String) = TextView(this).apply {
        text = t; textSize = 12.5f
        setTextColor(ContextCompat.getColor(context, R.color.mint))
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.jakarta_bold)
        setPadding(0, (3 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun sourceText(src: String) = when (src) {
        Profile.SOURCE_PHOTO -> R.string.sig_photographed_at
        Profile.SOURCE_FILE -> R.string.sig_inserted_at
        else -> R.string.sig_drawn_at
    }

    /** Förhandsvisning: signaturen med namn och datum, och fotot till vänster. */
    private fun previewWithPhoto(sig: Field, p: Profile, withPhoto: Boolean): Bitmap? {
        val sigBmp = Composer.compose(this, sig, 900, 300) ?: return null
        if (!withPhoto) return sigBmp
        val photo = p.photo() ?: return sigBmp
        val out = Bitmap.createBitmap(1150, 300, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawBitmap(photo, null, android.graphics.RectF(0f, 25f, 188f, 275f), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        c.drawBitmap(sigBmp, 230f, 0f, null)
        return out
    }

    /**
     * Ny eller ändrad signatur. Alla signaturer ritas på samma sätt (befintlig signatur öppnas så att den kan ändras).
     * Signatur 2 och 3 kan dessutom fotas på papper eller hämtas från en bildfil.
     */
    private fun newSignature(f: Field, p: Profile, withPhoto: Boolean) {
        pending = f; pendingProfile = p; pendingPhoto = withPhoto
        val photo = p.photoFile.takeIf { withPhoto && it.exists() }?.path
        if (p.n == 1) { launchDraw(f, p, photo); return }
        var handled = false
        val s = Sheet(this).title(tr(R.string.new_signature_n, p.n), p.signerName.ifBlank { null })
            .row("✍", getString(if (p.hasSignature) R.string.edit_signature else R.string.draw_on_screen)) { handled = true; launchDraw(f, p, photo) }
            .row("📷", getString(R.string.sig_photo_paper), getString(if (p.camera == Camera.FRONT) R.string.camera_front else R.string.camera_back)) {
                handled = true
                cameraLauncher.launch(Intent(this, CameraActivity::class.java)
                    .putExtra(CameraActivity.EXTRA_TARGET, CameraActivity.TARGET_SIG2).putExtra(CameraActivity.EXTRA_PROFILE, p.n))
            }
            .row("⤓", getString(R.string.sig_from_file), getString(R.string.sig_file_formats)) { handled = true; sigFileLauncher.launch(SIG_MIMES) }
        s.dialog.setOnDismissListener { if (!handled) dropIfEmpty(f) }
        s.show()
    }

    private fun launchDraw(f: Field, p: Profile, photoPath: String?) {
        val n = fields.filter { it.type == Field.Type.SIGN }.indexOf(f) + 1
        signLauncher.launch(p.drawIntent(this, tr(R.string.sign_where, n, f.page + 1) + " · " + p.label(this), photoPath)
            .putExtra(SignActivity.EXTRA_FIELD, f.id))
    }

    private fun launchDraw(f: Field, who: String, photoPath: String?) {
        val n = fields.filter { it.type == Field.Type.SIGN }.indexOf(f) + 1
        signLauncher.launch(
            Intent(this, SignActivity::class.java)
                .putExtra(SignActivity.EXTRA_FIELD, f.id)
                .putExtra(SignActivity.EXTRA_TITLE, tr(R.string.sign_where, n, f.page + 1) + " · " + who)
                .putExtra(SignActivity.EXTRA_PHOTO_PATH, photoPath)
        )
    }

    /** En kontakt signerar direkt på den här telefonen. */
    private fun signAsContact(f: Field) {
        pending = f; pendingProfile = null
        launchDraw(f, nextContact?.name ?: "", null)
    }

    /** Lägger in profilens signatur, namn, datum och (om valt) fotot bredvid. Bilderna kopieras in i dokumentet. */
    private fun fill(f: Field, p: Profile, withPhoto: Boolean) {
        val d = doc ?: return
        val pg = pages ?: return
        val sig = p.signature() ?: return
        f.sigFile = d.asset(p.sigFile, "sig")
        f.name = if (p.showName) p.signerName else ""
        f.date = if (p.showDate) Composer.today(this) else ""
        f.profile = p.n
        // Fältets proportioner efter signaturen (plus plats för namn och datum)
        val lines = (if (f.name.isNotBlank()) 1 else 0) + (if (f.date.isNotBlank()) 1 else 0)
        val ratio = sig.width.toFloat() / sig.height
        val wPt = f.w * pg.widthPt(f.page)
        val hPt = (wPt / ratio).coerceIn(wPt / 6f, wPt / 1.6f) * (1f + 0.32f * lines)
        val cy = f.y + f.h / 2f
        f.h = (hPt / pg.heightPt(f.page)).coerceAtMost(0.4f)
        f.y = (cy - f.h / 2f).coerceIn(0f, 1f - f.h)
        if (withPhoto && p.hasPhoto && fields.none { it.type == Field.Type.PHOTO && it.page == f.page && it.profile == p.n }) {
            addPhotoBeside(f, d.asset(p.photoFile, "photo"), p.n)
        }
        changed(f)
        updateHint()
    }

    /** Lägger fotot till vänster om signaturen; flyttar signaturen åt höger om det behövs för att få plats. */
    private fun addPhotoBeside(sig: Field, photoPath: String, profile: Int) {
        val p = pages ?: return
        val ar = p.widthPt(sig.page) / p.heightPt(sig.page)
        val h = sig.h
        val w = (h / ar) * 3f / 4f
        val gap = 0.012f
        if (sig.x < w + gap) sig.x = (w + gap).coerceAtMost(1f - sig.w)
        val x = (sig.x - gap - w).coerceAtLeast(0f)
        fields.add(Field(System.nanoTime(), Field.Type.PHOTO, sig.page, x, sig.y, w, h, sigFile = photoPath, profile = profile))
    }

    /** Ett signaturfält som aldrig fick någon signatur tas bort igen. */
    private fun dropIfEmpty(f: Field) {
        if (!f.done) { fields.remove(f); refresh(f.page); saveDoc(); updateHint() }
    }

    /**
     * Vem ska signera? Signatur 1, 2 och 3 (som syns i Inställningar) eller en kontakt.
     * En avstängd signatur 2 visas också och slås på när man väljer den, så att alla signaturer går att använda
     * i alla dokument (även fotograferade).
     */
    private fun chooseSigner() {
        val s = Sheet(this).title(getString(R.string.who_signs))
        for (p in Profile.all(this).filter { it.visible }) {
            val sub = when {
                !p.enabled -> getString(R.string.sig_off_turn_on)
                p.hasSignature -> tr(R.string.saved_at, Profile.stamp(this, p.sigAt))
                else -> getString(R.string.no_signature_saved)
            }
            s.row(p.n.toString(), p.label(this), sub) {
                if (!p.enabled) p.enabled = true
                nextProfile = p; nextContact = null; setPlacing(Field.Type.SIGN)
            }
        }
        for (c in ContactBook.all(this)) {
            s.row("👤", c.name, getString(R.string.draws_on_this_phone)) { nextProfile = null; nextContact = c; setPlacing(Field.Type.SIGN) }
        }
        s.row("+", getString(R.string.contact_add)) { startActivity(Intent(this, ContactsActivity::class.java)) }
        s.show()
    }

    /** Fotot: från signatur 1 eller en annan påslagen signatur som har ett foto. */
    private var photoProfile: Profile? = null
    private fun choosePhoto() {
        if (placeType == Field.Type.PHOTO) { setPlacing(null); return }
        val withPhoto = Profile.usable(this).filter { it.hasPhoto }
        when {
            withPhoto.isEmpty() -> Sheet(this).title(getString(R.string.photo_missing_title), getString(R.string.photo_missing))
                .buttons(getString(R.string.cancel), getString(R.string.settings), { startActivity(Intent(this, SettingsActivity::class.java)) }).show()
            withPhoto.size == 1 -> { photoProfile = withPhoto[0]; setPlacing(Field.Type.PHOTO) }
            else -> {
                val s = Sheet(this).title(getString(R.string.choose_photo))
                withPhoto.forEach { p -> s.row(p.n.toString(), p.label(this), tr(R.string.photo_taken_at, Profile.stamp(this, p.photoAt))) { photoProfile = p; setPlacing(Field.Type.PHOTO) } }
                s.show()
            }
        }
    }

    override fun sign(field: Field) {
        val p = if (field.profile in 1..3) Profile(this, field.profile) else null
        if (p != null && p.usable) { nextProfile = p; startProfileSign(field, p) } else { nextContact = null; signAsContact(field) }
    }

    override fun remove(field: Field) {
        fields.remove(field)
        if (rotating?.id == field.id) hideRotate()
        refresh(field.page)
        saveDoc()
        updateHint()
    }

    override fun changed(field: Field) {
        refresh(field.page)
        saveDoc()
    }

    private fun refresh(page: Int) {
        list.adapter?.notifyItemChanged(page)
    }

    // ---- Stämplar och vridning ----

    private fun chooseStamp() {
        if (placeType == Field.Type.STAMP) { setPlacing(null); return }
        val stamps = Store.stamps(this)
        val s = Sheet(this).title(getString(R.string.choose_stamp), getString(R.string.choose_stamp_sub))
        for (st in stamps) {
            val row = layoutInflater.inflate(R.layout.item_stamp, s.body, false)
            row.findViewById<ImageView>(R.id.stampImage).setImageBitmap(Store.stampBitmap(this, st.id))
            row.findViewById<TextView>(R.id.stampName).text = st.name
            row.findViewById<TextView>(R.id.stampKind).setText(if (st.other) R.string.other_seal_kind else R.string.stamp_builtin)
            row.findViewById<View>(R.id.stampState).visibility = View.GONE
            row.findViewById<View>(R.id.stampCheck).visibility = View.GONE
            row.findViewById<View>(R.id.stampRemove).visibility = View.GONE
            row.setOnClickListener { s.dialog.dismiss(); setPlacing(Field.Type.STAMP, st.id) }
            s.view(row, 6)
        }
        s.show()
    }

    private fun bindRotatePanel() {
        rotateSeek.max = 90
        rotateSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                if (!fromUser) return
                val f = rotating ?: return
                f.rot = (v - 45).toFloat(); showAngle(f); refresh(f.page)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { rotating?.let { changed(it) } }
        })
        fun step(delta: Float?) {
            val f = rotating ?: return
            f.rot = if (delta == null) 0f else (f.rot + delta).coerceIn(-180f, 180f)
            showAngle(f); changed(f)
        }
        findViewById<View>(R.id.rotMinus).setOnClickListener { step(-5f) }
        findViewById<View>(R.id.rotZero).setOnClickListener { step(null) }
        findViewById<View>(R.id.rotPlus).setOnClickListener { step(5f) }
        findViewById<View>(R.id.rotDone).setOnClickListener { hideRotate() }
    }

    private fun showAngle(f: Field) {
        rotateValue.text = "%+d°".format(Math.round(f.rot)).replace("+0°", "0°")
        rotateSeek.progress = (f.rot + 45).toInt().coerceIn(0, 90)
    }

    override fun selected(field: Field) {
        if (field.id == -1L || field.type != Field.Type.STAMP) { hideRotate(); return }
        rotating = field
        rotatePanel.visibility = View.VISIBLE
        showAngle(field)
        list.adapter?.notifyDataSetChanged()
    }

    override fun rotating(field: Field) = showAngle(field)

    private fun hideRotate() {
        rotating = null
        rotatePanel.visibility = View.GONE
        for (i in 0 until list.childCount) (list.getChildAt(i) as? PageView)?.selectedId = -1L
    }

    // ---- Spara ----

    private fun export() {
        val d = doc ?: return
        if (pages == null) return
        if (fields.none { it.type == Field.Type.SIGN && it.done }) {
            Toast.makeText(this, R.string.nothing_to_save, Toast.LENGTH_LONG).show(); return
        }
        val go = { chooseFormat(d) }
        if (fields.any { !it.done }) {
            Sheet(this).title(getString(R.string.unsigned_title), getString(R.string.unsigned_fields))
                .buttons(getString(R.string.cancel), getString(R.string.save_anyway), { go() }).show()
        } else go()
    }

    /**
     * Spara som: namn (förifyllt), "Spara som ny kopia" och ett eller flera format som tydliga knappar
     * (tryck för att välja, guldram + bock = valt). Samma format som originalet är förvalt.
     * Den stora guldknappen sparar: "Spara som PDF" eller "Spara (2 format)".
     */
    private fun chooseFormat(d: LibDoc) {
        val dd = resources.displayMetrics.density
        val s = Sheet(this).title(getString(R.string.save_as_title), tr(R.string.original_name, d.name + "." + d.format.ext))
        val nameInput = Kit.input(this, getString(R.string.doc_name), d.name, android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        s.view(Kit.text(this, getString(R.string.doc_name), 12.5f, R.color.dim), 8)
        s.view(nameInput, 4)
        var asCopy = d.signedFile != null
        s.view(Kit.switch(this, getString(R.string.save_as_copy), asCopy, getString(R.string.save_as_copy_sub)) { asCopy = it }, 6)
        s.view(Kit.text(this, getString(R.string.choose_formats), 12.5f, R.color.dim), 12)

        val selected = LinkedHashSet<OutFormat>()
        selected += when (d.format) { DocFormat.DOCX, DocFormat.DOC -> OutFormat.DOCX; DocFormat.ODT -> OutFormat.ODT; else -> OutFormat.PDF }
        lateinit var saveBtn: TextView
        val cards = HashMap<OutFormat, View>()
        fun paint() {
            cards.forEach { (f, v) ->
                val on = f in selected
                v.isSelected = on
                (v.findViewWithTag<TextView>("check")).visibility = if (on) View.VISIBLE else View.INVISIBLE
            }
            saveBtn.isEnabled = selected.isNotEmpty(); saveBtn.alpha = if (selected.isEmpty()) 0.4f else 1f
            saveBtn.text = when (selected.size) {
                0 -> getString(R.string.choose_a_format)
                1 -> tr(R.string.save_as_format, selected.first().label)
                else -> tr(R.string.save_n_formats, selected.size)
            }
        }
        for (f in OutFormat.entries) {
            val (title, sub) = formatText(d.format, f)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_stamp_row)
                val p = (12 * dd).toInt(); setPadding(p, p, p, p)
                isClickable = true
                setOnClickListener { if (!selected.remove(f)) selected += f; paint() }
            }
            card.addView(Kit.chip(this, f.label.take(4)).apply { textSize = 12f }, LinearLayout.LayoutParams((46 * dd).toInt(), (40 * dd).toInt()))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dd).toInt(), 0, (8 * dd).toInt(), 0) }
            texts.addView(Kit.text(this, title, 15f, isBold = true))
            texts.addView(Kit.text(this, sub, 12f, R.color.dim))
            card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(Kit.text(this, "✓", 18f, R.color.gold, isBold = true).apply { tag = "check" })
            cards[f] = card
            s.view(card, 6)
        }
        if (d.format == DocFormat.DOC) s.view(Kit.text(this, getString(R.string.doc_note), 12f, R.color.dim), 6)
        saveBtn = s.button("", true) {
            if (selected.isEmpty()) return@button
            s.dialog.dismiss()
            build(d, OutFormat.entries.filter { it in selected }, nameInput.text.toString().trim().ifBlank { d.name }, asCopy)
        }.apply { textSize = 16f }
        s.view(saveBtn.apply { layoutParams = LinearLayout.LayoutParams(-1, (54 * dd).toInt()) }, 14)
        s.view(s.button(getString(R.string.cancel), false) { s.dialog.dismiss() }, 8)
        paint()
        s.show()
    }

    /** Rubrik och undertext för ett format, beroende på originalet. */
    private fun formatText(src: DocFormat, f: OutFormat): Pair<String, String> = when (f) {
        OutFormat.PDF -> getString(R.string.format_pdf) to getString(R.string.format_pdf_sub)
        OutFormat.DOCX -> getString(R.string.format_docx) to getString(when (src) {
            DocFormat.DOCX -> R.string.format_docx_sub
            DocFormat.DOC -> R.string.format_doc_to_docx_sub
            else -> R.string.format_as_images_sub
        })
        OutFormat.ODT -> getString(R.string.format_odt) to getString(if (src == DocFormat.ODT) R.string.format_odt_sub else R.string.format_as_images_sub)
        OutFormat.PNG -> getString(R.string.format_png) to getString(R.string.format_image_sub)
        OutFormat.JPG -> getString(R.string.format_jpg) to getString(R.string.format_image_sub)
    }

    private fun build(orig: LibDoc, formats: List<OutFormat>, name: String, asCopy: Boolean) {
        val p = pages ?: return
        loading.visibility = View.VISIBLE
        loadingText.setText(R.string.exporting)
        saveDoc()
        lifecycleScope.launch {
            try {
                // Ny kopia med eget namn, eller samma dokument (eventuellt med nytt namn)
                val d = withContext(Dispatchers.IO) {
                    if (asCopy) orig.copyAs(this@DocumentActivity, name) else orig.also { if (name != it.name) it.rename(name) }
                }
                if (d !== orig) {
                    doc = d
                    fields.clear(); fields.addAll(d.fields)
                    list.adapter?.notifyDataSetChanged()
                }
                setTitle(d)
                val done = fields.filter { it.done }
                val ctx = this@DocumentActivity
                withContext(Dispatchers.IO) {
                    val work = File(cacheDir, "build").apply { deleteRecursively(); mkdirs() }
                    d.clearSigned()
                    for (f in formats) {
                        withContext(Dispatchers.Main) { loadingText.text = tr(R.string.exporting_format, f.label) }
                        val out = File(work, "signed.${f.ext}")
                        when (f) {
                            OutFormat.PDF -> { Exporter.export(ctx, d.source, isPdf, p, done, out); d.addSigned(f, listOf(out)) }
                            OutFormat.DOCX -> {
                                when {
                                    d.format == DocFormat.DOCX -> Exporter.exportDocx(ctx, d.source, p as ImagePages, done, out)
                                    d.format == DocFormat.DOC && p is ImagePages -> {
                                        // Riktig Word-fil med redigerbar text; signaturerna läggs på rätt sida
                                        val ok = runCatching {
                                            val plain = File(work, "converted.docx")
                                            DocToDocx.convert(d.source, plain)
                                            Exporter.exportDocx(ctx, plain, p, done, out)
                                        }.isSuccess
                                        if (!ok) ImageDocs.docx(ctx, p, done, out, d.name)
                                    }
                                    else -> ImageDocs.docx(ctx, p, done, out, d.name)
                                }
                                d.addSigned(f, listOf(out))
                            }
                            OutFormat.ODT -> {
                                if (d.format == DocFormat.ODT) Exporter.exportOdt(ctx, d.source, p as ImagePages, done, out)
                                else ImageDocs.odt(ctx, p, done, out)
                                d.addSigned(f, listOf(out))
                            }
                            OutFormat.PNG, OutFormat.JPG ->
                                d.addSigned(f, ImageDocs.images(ctx, p, done, File(work, "img-${f.ext}"), f == OutFormat.PNG))
                        }
                    }
                    d.makeThumb(ctx, cache.get(0))
                }
                Toast.makeText(ctx, tr(R.string.saved_as_name, d.name), Toast.LENGTH_SHORT).show()
                ShareSheet.show(ctx, d)
            } catch (e: Exception) {
                Toast.makeText(this@DocumentActivity, R.string.export_failed, Toast.LENGTH_LONG).show()
            } finally {
                loading.visibility = View.GONE
            }
        }
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
            h.view.selectedId = rotating?.takeIf { it.page == position }?.id ?: -1L
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
