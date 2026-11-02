package se.snc.sign

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SettingsActivity : BaseActivity() {

    private val fmt by lazy {
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Lang.locale(this)).withZone(ZoneId.systemDefault())
    }

    private lateinit var updateStatus: TextView
    private lateinit var btnCheck: Button
    private var latest: UpdateChecker.Release? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        Prefs.setIncludePhoto(this, true)
        renderPhoto()
    }

    private val sig2CameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { renderSig2() }

    /** Öppnar filväljaren i mappen Hämtade filer (Download). */
    private class OpenInDownloads : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
            super.createIntent(context, input).putExtra(
                android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
            )
    }

    private val sig2FileLauncher = registerForActivityResult(OpenInDownloads()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Store.saveSig2(this@SettingsActivity, uri) }.getOrDefault(false) }
            if (!ok) Toast.makeText(this@SettingsActivity, R.string.sig2_not_found, Toast.LENGTH_LONG).show()
            renderSig2()
        }
    }

    private val stampLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val name = displayName(uri)
            val ok = withContext(Dispatchers.IO) { runCatching { Store.addStamp(this@SettingsActivity, uri, name) }.getOrDefault(false) }
            if (!ok) Toast.makeText(this@SettingsActivity, R.string.stamp_failed, Toast.LENGTH_LONG).show()
            renderStamps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        fadedTopScreen(findViewById(R.id.settingsRoot), findViewById(R.id.scroll), findViewById(R.id.statusBg), findViewById(R.id.topScrim))
        backTitle(findViewById(R.id.btnBack), getString(R.string.settings))

        // Språk
        findViewById<RadioGroup>(R.id.languageGroup).apply {
            check(if (Prefs.language(this@SettingsActivity) == Lang.SV) R.id.langSv else R.id.langEn)
            setOnCheckedChangeListener { _, id ->
                val tag = if (id == R.id.langSv) Lang.SV else Lang.EN
                if (tag != Prefs.language(this@SettingsActivity)) {
                    Prefs.setLanguage(this@SettingsActivity, tag)
                    recreate()
                }
            }
        }

        // Signatur
        findViewById<EditText>(R.id.nameInput).apply {
            setText(Prefs.name(this@SettingsActivity))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { Prefs.setName(this@SettingsActivity, s?.toString().orEmpty()) }
            })
        }
        bindSwitch(R.id.switchShowName, Prefs.showName(this)) { Prefs.setShowName(this, it) }

        // Foto
        findViewById<Button>(R.id.btnPhoto).setOnClickListener {
            cameraLauncher.launch(Intent(this, CameraActivity::class.java).putExtra(CameraActivity.EXTRA_LENS, Prefs.photoCamera(this)))
        }
        findViewById<RadioGroup>(R.id.photoCameraGroup).apply {
            check(if (Prefs.photoCamera(this@SettingsActivity) == Camera.BACK) R.id.camBack else R.id.camFront)
            setOnCheckedChangeListener { _, id -> Prefs.setPhotoCamera(this@SettingsActivity, if (id == R.id.camBack) Camera.BACK else Camera.FRONT) }
        }
        findViewById<Button>(R.id.btnPhotoRemove).setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.photo_remove_confirm)
                .setPositiveButton(R.string.remove) { _, _ ->
                    Store.photoFile(this).delete(); Composer.forget(Store.photoFile(this).path); renderPhoto()
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
        bindSwitch(R.id.switchIncludePhoto, Prefs.includePhoto(this)) { Prefs.setIncludePhoto(this, it) }
        renderPhoto()

        // Signatur 2 (tillval)
        findViewById<Switch>(R.id.switchSig2).apply {
            isChecked = Prefs.sig2Enabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, on -> Prefs.setSig2Enabled(this@SettingsActivity, on); renderSig2() }
        }
        findViewById<Button>(R.id.btnSig2Camera).setOnClickListener {
            val lenses = arrayOf(getString(R.string.camera_back), getString(R.string.camera_front))
            AlertDialog.Builder(this).setTitle(R.string.choose_camera)
                .setItems(lenses) { _, i ->
                    sig2CameraLauncher.launch(
                        Intent(this, CameraActivity::class.java)
                            .putExtra(CameraActivity.EXTRA_TARGET, CameraActivity.TARGET_SIG2)
                            .putExtra(CameraActivity.EXTRA_LENS, if (i == 0) Camera.BACK else Camera.FRONT)
                    )
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
        findViewById<Button>(R.id.btnSig2File).setOnClickListener { sig2FileLauncher.launch(arrayOf("image/*")) }
        findViewById<Button>(R.id.btnSig2Remove).setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.sig2_remove_confirm)
                .setPositiveButton(R.string.remove) { _, _ -> Store.deleteSig2(this); renderSig2() }
                .setNegativeButton(R.string.cancel, null).show()
        }
        findViewById<EditText>(R.id.sig2NameInput).apply {
            setText(Prefs.sig2Name(this@SettingsActivity))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { Prefs.setSig2Name(this@SettingsActivity, s?.toString().orEmpty()) }
            })
        }
        bindSwitch(R.id.switchSig2Date, Prefs.sig2Date(this)) { Prefs.setSig2Date(this, it) }
        renderSig2()

        // Stämplar
        findViewById<Button>(R.id.btnAddStamp).setOnClickListener { stampLauncher.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }
        bindSwitch(R.id.switchAutoSeal, Prefs.autoSeal(this)) { Prefs.setAutoSeal(this, it) }
        renderStamps()

        // Uppdateringar
        updateStatus = findViewById(R.id.updateStatus)
        btnCheck = findViewById(R.id.btnCheckUpdate)
        findViewById<TextView>(R.id.versionValue).text =
            tr(R.string.version_value, UpdateChecker.currentVersionName(this), UpdateChecker.currentBuild(this))
        showLastCheck()
        btnCheck.setOnClickListener {
            val r = latest
            if (r != null && r.build > UpdateChecker.currentBuild(this)) UpdateChecker.open(this, r.apkUrl) else checkUpdate()
        }
        bindSwitch(R.id.switchAutoUpdate, Prefs.autoUpdate(this)) { Prefs.setAutoUpdate(this, it) }

        // Utvecklare
        findViewById<View>(R.id.rowDev).setOnClickListener { startActivity(Intent(this, DevActivity::class.java)) }
    }

    private fun bindSwitch(id: Int, value: Boolean, set: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> set(on) }
        }
    }

    private fun renderPhoto() {
        val img = findViewById<ImageView>(R.id.photoPreview)
        val has = Store.hasPhoto(this)
        val bmp = if (has) Store.photo(this) else null
        if (bmp != null) img.setImageBitmap(bmp) else img.setImageResource(R.drawable.ic_person)
        findViewById<Button>(R.id.btnPhoto).setText(if (has) R.string.photo_retake else R.string.photo_take)
        findViewById<Button>(R.id.btnPhotoRemove).visibility = if (has) View.VISIBLE else View.GONE
        findViewById<Switch>(R.id.switchIncludePhoto).isChecked = Prefs.includePhoto(this)
    }

    private fun renderSig2() {
        val on = Prefs.sig2Enabled(this)
        findViewById<View>(R.id.sig2Options).visibility = if (on) View.VISIBLE else View.GONE
        val bmp = if (Store.hasSig2(this)) Store.sig2(this) else null
        findViewById<ImageView>(R.id.sig2Preview).setImageBitmap(bmp)
        findViewById<View>(R.id.sig2Empty).visibility = if (bmp == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnSig2Remove).visibility = if (bmp == null) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.btnSig2Camera).setText(if (bmp == null) R.string.sig2_photo_paper else R.string.sig2_photo_again)
    }

    /** Stämplarna med bild. Tryck för att välja vilken som läggs i vänstra hörnet; den valda har guldram och bock. */
    private fun renderStamps() {
        val list = findViewById<LinearLayout>(R.id.stampList)
        list.removeAllViews()
        val selected = Prefs.cornerStamp(this)
        for (s in Store.stamps(this)) {
            val row = layoutInflater.inflate(R.layout.item_stamp, list, false)
            val isSel = s.id == selected
            row.isSelected = isSel
            row.findViewById<ImageView>(R.id.stampImage).setImageBitmap(Store.stampBitmap(this, s.id))
            row.findViewById<TextView>(R.id.stampName).text = s.name
            row.findViewById<TextView>(R.id.stampKind).setText(if (s.builtin) R.string.stamp_builtin else R.string.stamp_own)
            row.findViewById<TextView>(R.id.stampState).apply {
                setText(if (isSel) R.string.stamp_selected else R.string.stamp_tap_to_choose)
                setTextColor(ContextCompat.getColor(context, if (isSel) R.color.gold else R.color.dim))
            }
            row.findViewById<View>(R.id.stampCheck).visibility = if (isSel) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.stampRemove).apply {
                visibility = if (s.builtin) View.GONE else View.VISIBLE
                setOnClickListener {
                    AlertDialog.Builder(this@SettingsActivity).setMessage(tr(R.string.stamp_remove_confirm, s.name))
                        .setPositiveButton(R.string.remove) { _, _ -> Store.deleteStamp(this@SettingsActivity, s.id); renderStamps() }
                        .setNegativeButton(R.string.cancel, null).show()
                }
            }
            row.contentDescription = s.name + if (isSel) ", " + getString(R.string.stamp_selected) else ""
            row.setOnClickListener {
                Prefs.setCornerStamp(this, s.id)
                if (!Prefs.autoSeal(this)) { Prefs.setAutoSeal(this, true); findViewById<Switch>(R.id.switchAutoSeal).isChecked = true }
                renderStamps()
                Toast.makeText(this, tr(R.string.stamp_chosen, s.name), Toast.LENGTH_SHORT).show()
            }
            list.addView(row)
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun showLastCheck() {
        val ts = Prefs.lastUpdateCheck(this)
        updateStatus.text = if (ts == 0L) getString(R.string.update_never)
        else tr(R.string.update_last, fmt.format(Instant.ofEpochMilli(ts)))
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
                    updateStatus.text = getString(R.string.update_latest)
                    btnCheck.setText(R.string.update_check)
                }
            } catch (e: Exception) {
                updateStatus.text = e.message ?: getString(R.string.update_no_net)
            } finally {
                btnCheck.isEnabled = true
            }
        }
    }
}
