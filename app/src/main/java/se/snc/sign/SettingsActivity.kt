package se.snc.sign

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Inställningar: språk, signatur 1/2/(3) med samma upplägg, stämplar, kontakter och uppdateringar.
 * Signatur 3 är dold tills man trycker 7 gånger i rad på "Utvecklare" under Uppdateringar.
 */
class SettingsActivity : BaseActivity() {

    private lateinit var screen: Screen
    private lateinit var signatures: LinearLayout
    private lateinit var stampsBox: LinearLayout
    private lateinit var updateStatus: TextView
    private lateinit var btnCheck: TextView
    private var latest: UpdateChecker.Release? = null

    /** Profilen som väntar på resultat från ritning, kamera eller filval. */
    private var pendingProfile = 1

    private val drawLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra(SignActivity.EXTRA_FILE)
        if (r.resultCode == RESULT_OK && path != null) Profile(this, pendingProfile).saveSignatureFile(File(path), Profile.SOURCE_DRAWN)
        renderSignatures()
    }
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { renderSignatures() }

    private val sigFileLauncher = registerForActivityResult(OpenInDownloads()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val p = Profile(this@SettingsActivity, pendingProfile)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching false
                    val ink = InkExtractor.extract(Store.scaleDown(bmp, 2000)) ?: return@runCatching false
                    p.saveSignature(ink, Profile.SOURCE_FILE); true
                }.getOrDefault(false)
            }
            if (!ok) Toast.makeText(this@SettingsActivity, R.string.sig2_not_found, Toast.LENGTH_LONG).show()
            renderSignatures()
        }
    }

    private val logoFileLauncher = registerForActivityResult(OpenInDownloads()) { uri ->
        if (uri != null) startActivity(Intent(this, ImportLogoActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private val logoCameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra(CameraActivity.EXTRA_RESULT) ?: return@registerForActivityResult
        startActivity(Intent(this, ImportLogoActivity::class.java).putExtra(ImportLogoActivity.EXTRA_PATH, path))
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        ContactBook.setAsked(this)
        if (!ContactBook.hasPermission(this)) Toast.makeText(this, R.string.contacts_local_only, Toast.LENGTH_LONG).show()
        recreate()
    }

    // 7 tryck i rad på "Utvecklare" låser upp signatur 3
    private var taps = 0
    private var lastTap = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = Screen(this, getString(R.string.settings))
        val c = this

        // Språk
        screen.add(Kit.section(c, getString(R.string.language_section)))
        screen.add(Kit.card(c, 12).apply {
            addView(Kit.segmented(c, listOf(getString(R.string.language_en), getString(R.string.language_sv)),
                if (Prefs.language(c) == Lang.SV) 1 else 0) { i ->
                val tag = if (i == 1) Lang.SV else Lang.EN
                if (tag != Prefs.language(c)) { Prefs.setLanguage(c, tag); recreate() }
            })
        })

        // Signaturer
        screen.add(Kit.section(c, getString(R.string.signatures_section)))
        signatures = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        screen.add(signatures)

        // Stämplar
        screen.add(Kit.section(c, getString(R.string.stamps_section)))
        stampsBox = Kit.card(c)
        screen.add(stampsBox)

        // Kontakter
        screen.add(Kit.section(c, getString(R.string.contacts_section)))
        screen.add(Kit.card(c, 12).apply {
            addView(Kit.switch(c, getString(R.string.contacts_sync), ContactBook.syncing(c), getString(R.string.contacts_sync_sub)) { on ->
                ContactBook.setWantSync(c, on)
                if (on && !ContactBook.hasPermission(c)) askContacts()
            })
            addView(Kit.row(c, "👥", getString(R.string.contacts), getString(R.string.contacts_sub)) {
                startActivity(Intent(c, ContactsActivity::class.java))
            }, Kit.lp(c, top = 4))
        })

        // Uppdateringar
        screen.add(Kit.section(c, getString(R.string.updates_section)))
        screen.add(updatesCard())

        screen.show()
    }

    override fun onResume() {
        super.onResume()
        renderSignatures()
        renderStamps()
    }

    private fun askContacts() =
        contactsPermission.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))

    // ---- Signaturer ----

    private fun renderSignatures() {
        signatures.removeAllViews()
        for (p in Profile.all(this).filter { it.visible }) {
            signatures.addView(signatureCard(p), Kit.lp(this, top = if (signatures.childCount == 0) 0 else 12))
        }
    }

    /** Samma upplägg för alla signaturer: förhandsvisning, namn, datum, foto, kamera, vem som signerar, kontakter. */
    private fun signatureCard(p: Profile): View {
        val c = this
        val card = Kit.card(c)
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(Kit.chip(c, p.n.toString()), LinearLayout.LayoutParams(dp(34), dp(34)))
        val titles = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        titles.addView(Kit.text(c, tr(R.string.signature_n, p.n), 17f, isBold = true))
        titles.addView(Kit.text(c, getString(when (p.n) { 1 -> R.string.sig1_sub; 2 -> R.string.sig2_sub; else -> R.string.sig3_sub }), 12.5f, R.color.dim))
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        if (p.n > 1) {
            head.addView(android.widget.Switch(c).apply {
                isChecked = p.enabled
                setOnCheckedChangeListener { _, on ->
                    if (p.n == 3 && !on) {
                        // Avaktiverad: signatur 3 döljs och måste låsas upp igen med 7 tryck. Uppgifterna finns kvar.
                        p.enabled = false; p.unlocked = false
                        Toast.makeText(c, R.string.sig3_hidden, Toast.LENGTH_LONG).show()
                    } else p.enabled = on
                    renderSignatures()
                }
            })
        }
        card.addView(head)
        if (!p.enabled) return card

        card.addView(Kit.divider(c))

        // 1. Förhandsvisning med tid
        card.addView(Kit.text(c, getString(R.string.preview), 13f, R.color.muted))
        val previewRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sigImg = ImageView(c).apply {
            setBackgroundResource(R.drawable.bg_stamp); scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = dp(6); setPadding(pad, pad, pad, pad)
            val demo = Field(0, Field.Type.SIGN, 0, 0f, 0f, 1f, 1f, sigFile = p.sigFile.path.takeIf { p.hasSignature },
                name = if (p.showName) p.signerName else "", date = if (p.showDate) Composer.today(c) else "")
            if (p.hasSignature) setImageBitmap(Composer.compose(c, demo, 900, 300))
        }
        previewRow.addView(sigImg, LinearLayout.LayoutParams(0, dp(96), 1f))
        if (p.showPhoto) {
            val ph = ImageView(c).apply {
                setBackgroundResource(R.drawable.bg_photo); clipToOutline = true; scaleType = ImageView.ScaleType.CENTER_CROP
                val b = p.photo(); if (b != null) setImageBitmap(b) else setImageResource(R.drawable.ic_person)
            }
            previewRow.addView(ph, LinearLayout.LayoutParams(dp(72), dp(96)).apply { marginStart = dp(10) })
        }
        card.addView(previewRow, Kit.lp(c, top = 6))
        val stamp = if (p.hasSignature) tr(sourceText(p.sigSource), Profile.stamp(c, p.sigAt)) else getString(R.string.no_signature_saved)
        card.addView(Kit.text(c, "✍ $stamp", 12.5f, if (p.hasSignature) R.color.mint else R.color.dim), Kit.lp(c, top = 6))
        if (p.showPhoto) {
            val pt = if (p.hasPhoto) tr(R.string.photo_taken_at, Profile.stamp(c, p.photoAt)) else getString(R.string.no_photo_saved)
            card.addView(Kit.text(c, "📷 $pt", 12.5f, if (p.hasPhoto) R.color.mint else R.color.dim), Kit.lp(c, top = 2))
        }

        // Skapa signaturen: 1 ritas; 2 och 3 fotas på papper eller hämtas från Download
        val sigButtons = if (p.n == 1) Kit.buttonRow(c,
            Kit.button(c, getString(if (p.hasSignature) R.string.draw_new else R.string.draw_signature), primary = true) { draw(p) })
        else Kit.buttonRow(c,
            Kit.button(c, getString(R.string.sig_photo_paper), primary = true) { photographSig(p) },
            Kit.button(c, getString(R.string.sig_from_downloads)) { pendingProfile = p.n; sigFileLauncher.launch(arrayOf("image/*")) })
        card.addView(sigButtons, Kit.lp(c, top = 10))
        if (p.hasSignature) card.addView(Kit.button(c, getString(R.string.remove_signature), danger = true) {
            Sheet(this).title(getString(R.string.remove_signature), getString(R.string.remove_signature_confirm))
                .buttons(getString(R.string.cancel), getString(R.string.remove), { p.deleteSignature(); renderSignatures() }, danger = true).show()
        }, Kit.lp(c, top = 8))

        card.addView(Kit.divider(c))
        // 2–3. Namn och datum under signaturen
        card.addView(Kit.switch(c, getString(R.string.show_name), p.showName) { p.showName = it; renderSignatures() })
        card.addView(Kit.switch(c, getString(R.string.show_date), p.showDate, getString(R.string.show_date_sub)) { p.showDate = it; renderSignatures() })

        // 4. Foto bredvid signaturen
        card.addView(Kit.switch(c, getString(R.string.include_photo), p.showPhoto) { p.showPhoto = it; renderSignatures() })
        if (p.showPhoto) {
            val row = Kit.buttonRow(c,
                Kit.button(c, getString(if (p.hasPhoto) R.string.photo_retake else R.string.photo_take)) { takePhoto(p) },
                *(if (p.hasPhoto) arrayOf<View>(Kit.button(c, getString(R.string.photo_remove), danger = true) {
                    Sheet(this).title(getString(R.string.photo_remove), getString(R.string.photo_remove_confirm))
                        .buttons(getString(R.string.cancel), getString(R.string.remove), { p.deletePhoto(); renderSignatures() }, danger = true).show()
                }) else emptyArray()))
            card.addView(row, Kit.lp(c, top = 4))
        }

        // 5. Kamera: främre är standard
        card.addView(Kit.text(c, getString(R.string.camera_label), 13f, R.color.muted), Kit.lp(c, top = 14))
        card.addView(Kit.segmented(c, listOf(getString(R.string.camera_front_short), getString(R.string.camera_back_short)),
            if (p.camera == Camera.BACK) 1 else 0) { i -> p.camera = if (i == 1) Camera.BACK else Camera.FRONT }, Kit.lp(c, top = 6))

        card.addView(Kit.divider(c))
        // 6. Vem som signerar
        card.addView(Kit.text(c, getString(R.string.who_has_signed), 13f, R.color.muted))
        val name = Kit.input(c, getString(R.string.contact_name), p.signerName) { p.signerName = it }
        val email = Kit.input(c, getString(R.string.contact_email_hint), p.signerEmail, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) { p.signerEmail = it }
        val phone = Kit.input(c, getString(R.string.contact_phone_hint), p.signerPhone, InputType.TYPE_CLASS_PHONE) { p.signerPhone = it }
        card.addView(name, Kit.lp(c, top = 6)); card.addView(email, Kit.lp(c, top = 8)); card.addView(phone, Kit.lp(c, top = 8))
        // 7. Hämta från kontakter
        card.addView(Kit.button(c, "👥  " + getString(R.string.choose_from_contacts)) {
            val list = ContactBook.all(c)
            val s = Sheet(this).title(getString(R.string.choose_from_contacts))
            if (list.isEmpty()) s.view(Kit.text(c, getString(R.string.no_contacts), 14f, R.color.dim))
            list.forEach { ct ->
                s.row(ct.name.take(1).uppercase(), ct.name, listOf(ct.email, ct.phone).filter { it.isNotBlank() }.joinToString(" · ")) {
                    p.signerName = ct.name; p.signerEmail = ct.email; p.signerPhone = ct.phone
                    name.setText(ct.name); email.setText(ct.email); phone.setText(ct.phone)
                }
            }
            s.show()
        }, Kit.lp(c, top = 10))

        if (p.n == 3) {
            card.addView(Kit.divider(c))
            card.addView(Kit.button(c, getString(R.string.sig3_remove_data), danger = true) {
                Sheet(this).title(getString(R.string.sig3_remove_data), getString(R.string.sig3_remove_confirm))
                    .buttons(getString(R.string.cancel), getString(R.string.remove), {
                        p.deleteSignature(); p.deletePhoto(); p.signerName = ""; p.signerEmail = ""; p.signerPhone = ""
                        renderSignatures()
                    }, danger = true).show()
            })
        }
        return card
    }

    private fun sourceText(src: String) = when (src) {
        Profile.SOURCE_PHOTO -> R.string.sig_photographed_at
        Profile.SOURCE_FILE -> R.string.sig_inserted_at
        else -> R.string.sig_drawn_at
    }

    private fun draw(p: Profile) {
        pendingProfile = p.n
        drawLauncher.launch(Intent(this, SignActivity::class.java)
            .putExtra(SignActivity.EXTRA_TITLE, p.label(this))
            .putExtra(SignActivity.EXTRA_PHOTO_PATH, p.photoFile.takeIf { p.showPhoto && it.exists() }?.path))
    }

    private fun photographSig(p: Profile) {
        cameraLauncher.launch(Intent(this, CameraActivity::class.java)
            .putExtra(CameraActivity.EXTRA_TARGET, CameraActivity.TARGET_SIG2).putExtra(CameraActivity.EXTRA_PROFILE, p.n))
    }

    private fun takePhoto(p: Profile) {
        cameraLauncher.launch(Intent(this, CameraActivity::class.java)
            .putExtra(CameraActivity.EXTRA_TARGET, CameraActivity.TARGET_PHOTO).putExtra(CameraActivity.EXTRA_PROFILE, p.n))
    }

    // ---- Stämplar ----

    /** Tryck för att välja stämpeln i vänstra hörnet; den valda har guldram och bock. */
    private fun renderStamps() {
        val c = this
        val box = stampsBox
        box.removeAllViews()
        box.addView(Kit.switch(c, getString(R.string.auto_seal), Prefs.autoSeal(c)) { Prefs.setAutoSeal(c, it) })
        val selected = Prefs.cornerStamp(c)
        var otherHeader = false
        fun header() {
            otherHeader = true
            box.addView(Kit.text(c, getString(R.string.other_seals), 13f, R.color.gold, isBold = true), Kit.lp(c, top = 14))
        }
        for (s in Store.stamps(c)) {
            if (s.other && !otherHeader) header()
            val row = layoutInflater.inflate(R.layout.item_stamp, box, false)
            val isSel = s.id == selected
            row.isSelected = isSel
            row.findViewById<ImageView>(R.id.stampImage).setImageBitmap(Store.stampBitmap(c, s.id))
            row.findViewById<TextView>(R.id.stampName).text = s.name
            row.findViewById<TextView>(R.id.stampKind).setText(if (s.other) R.string.other_seal_kind else R.string.stamp_builtin)
            row.findViewById<TextView>(R.id.stampState).apply {
                setText(if (isSel) R.string.stamp_selected else R.string.stamp_tap_to_choose)
                setTextColor(Kit.color(c, if (isSel) R.color.gold else R.color.dim))
            }
            row.findViewById<View>(R.id.stampCheck).visibility = if (isSel) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.stampRemove).apply {
                visibility = if (s.builtin) View.GONE else View.VISIBLE
                setOnClickListener {
                    Sheet(c).title(getString(R.string.remove), tr(R.string.stamp_remove_confirm, s.name))
                        .buttons(getString(R.string.cancel), getString(R.string.remove), { Store.deleteStamp(c, s.id); renderStamps() }, danger = true).show()
                }
            }
            row.setOnClickListener {
                Prefs.setCornerStamp(c, s.id); Prefs.setAutoSeal(c, true)
                renderStamps()
                Toast.makeText(c, tr(R.string.stamp_chosen, s.name), Toast.LENGTH_SHORT).show()
            }
            box.addView(row, Kit.lp(c, top = 8))
        }
        if (!otherHeader) { header(); box.addView(Kit.text(c, getString(R.string.other_seals_empty), 13f, R.color.dim)) }
        box.addView(Kit.text(c, getString(R.string.import_logo_desc), 12.5f, R.color.dim), Kit.lp(c, top = 14))
        box.addView(Kit.buttonRow(c,
            Kit.button(c, getString(R.string.import_logo), primary = true) { logoFileLauncher.launch(arrayOf("image/*")) },
            Kit.button(c, getString(R.string.photograph_logo)) {
                logoCameraLauncher.launch(Intent(c, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_TARGET, CameraActivity.TARGET_LOGO))
            }), Kit.lp(c, top = 8))
    }

    // ---- Uppdateringar ----

    private fun updatesCard(): View {
        val c = this
        val card = Kit.card(c)
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(c).apply { setImageResource(R.drawable.sign_logo); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(52), dp(52)))
        val t = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        t.addView(Kit.text(c, "Sign", 20f, isBold = true))
        val dev = Kit.text(c, getString(R.string.developer_line), 14f, R.color.ink).apply {
            setPadding(0, dp(4), 0, dp(4)); setBackgroundResource(R.drawable.bg_row)
            setOnClickListener { devTap() }
        }
        t.addView(dev)
        t.addView(Kit.text(c, getString(R.string.company_full), 12.5f, R.color.dim))
        head.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(head)
        card.addView(Kit.divider(c))

        val info = packageManager.getPackageInfo(packageName, 0)
        fun line(label: String, value: String) = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, dp(3))
            addView(Kit.text(c, label, 14f, R.color.muted), LinearLayout.LayoutParams(0, -2, 1f))
            addView(Kit.text(c, value, 14f, R.color.ink, isBold = true))
        }
        val v = UpdateChecker.currentVersionName(c)
        card.addView(line(getString(R.string.version), UpdateChecker.featureVersion(v)))
        card.addView(line(getString(R.string.build), UpdateChecker.currentBuild(c).toString()))
        card.addView(line(getString(R.string.installed_on), Profile.stamp(c, info.lastUpdateTime)))
        updateStatus = Kit.text(c, "", 13f, R.color.muted)
        card.addView(updateStatus, Kit.lp(c, top = 8))
        showLastCheck()
        btnCheck = Kit.button(c, getString(R.string.update_check), primary = true) {
            val r = latest
            if (r != null && r.build > UpdateChecker.currentBuild(c)) UpdateChecker.open(c, r.apkUrl) else checkUpdate()
        }
        card.addView(btnCheck, Kit.lp(c, top = 10))
        card.addView(Kit.switch(c, getString(R.string.update_auto), Prefs.autoUpdate(c)) { Prefs.setAutoUpdate(c, it) }, Kit.lp(c, top = 6))
        card.addView(Kit.row(c, "★", getString(R.string.update_whats_new), getString(R.string.whats_new_sub)) {
            startActivity(Intent(c, DevActivity::class.java))
        }, Kit.lp(c, top = 4))
        return card
    }

    /** 7 tryck i rad (inom några sekunder mellan trycken) låser upp signatur 3. Ingen nedräkning visas. */
    private fun devTap() {
        val p3 = Profile(this, 3)
        if (p3.unlocked) return
        val now = SystemClock.elapsedRealtime()
        taps = if (now - lastTap < 1500) taps + 1 else 1
        lastTap = now
        if (taps >= 7) {
            taps = 0
            p3.unlocked = true; p3.enabled = true
            Toast.makeText(this, R.string.sig3_unlocked, Toast.LENGTH_LONG).show()
            renderSignatures()
        }
    }

    private fun showLastCheck() {
        val ts = Prefs.lastUpdateCheck(this)
        updateStatus.text = if (ts == 0L) getString(R.string.update_never) else tr(R.string.update_last, Profile.stamp(this, ts))
    }

    private fun checkUpdate() {
        btnCheck.isEnabled = false
        updateStatus.text = getString(R.string.update_searching)
        lifecycleScope.launch {
            try {
                val r = UpdateChecker.latest(this@SettingsActivity)
                Prefs.setLastUpdateCheck(this@SettingsActivity, System.currentTimeMillis())
                latest = r
                if (r.build > UpdateChecker.currentBuild(this@SettingsActivity)) {
                    updateStatus.text = tr(R.string.update_available, r.versionName)
                    btnCheck.text = tr(R.string.update_download, r.versionName)
                } else {
                    updateStatus.text = getString(R.string.update_latest) + " · " + tr(R.string.update_last, Profile.stamp(this@SettingsActivity, System.currentTimeMillis()))
                    btnCheck.setText(R.string.update_check)
                }
            } catch (e: Exception) {
                updateStatus.text = e.message ?: getString(R.string.update_no_net)
            } finally {
                btnCheck.isEnabled = true
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

/** Öppnar filväljaren i mappen Hämtade filer (Download). */
class OpenInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(
            android.provider.DocumentsContract.EXTRA_INITIAL_URI,
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
        )
}
