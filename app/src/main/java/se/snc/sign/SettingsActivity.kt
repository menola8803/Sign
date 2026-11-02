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
        findViewById<Button>(R.id.btnPhoto).setOnClickListener { cameraLauncher.launch(Intent(this, CameraActivity::class.java)) }
        findViewById<Button>(R.id.btnPhotoRemove).setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.photo_remove_confirm)
                .setPositiveButton(R.string.remove) { _, _ ->
                    Store.photoFile(this).delete(); Composer.forget(Store.photoFile(this).path); renderPhoto()
                }
                .setNegativeButton(R.string.cancel, null).show()
        }
        bindSwitch(R.id.switchIncludePhoto, Prefs.includePhoto(this)) { Prefs.setIncludePhoto(this, it) }
        renderPhoto()

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
        findViewById<View>(R.id.rowAllVersions).setOnClickListener { UpdateChecker.open(this, "${UpdateChecker.REPO_URL}/releases") }
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

    private fun renderStamps() {
        val list = findViewById<LinearLayout>(R.id.stampList)
        list.removeAllViews()
        val d = resources.displayMetrics.density
        for (s in Store.stamps(this)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            }
            row.addView(ImageView(this).apply {
                setImageBitmap(Store.stampBitmap(this@SettingsActivity, s.id))
                setBackgroundResource(R.drawable.bg_stamp)
                val p = (4 * d).toInt(); setPadding(p, p, p, p)
            }, LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * d).toInt(), 0, 0, 0)
                addView(TextView(this@SettingsActivity).apply { text = s.name; setTextColor(ContextCompat.getColor(context, R.color.ink)); textSize = 15f })
                addView(TextView(this@SettingsActivity).apply {
                    setText(if (s.builtin) R.string.stamp_builtin else R.string.stamp_own)
                    setTextColor(ContextCompat.getColor(context, if (s.builtin) R.color.mint else R.color.dim)); textSize = 12f
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!s.builtin) row.addView(TextView(this).apply {
                setText(R.string.remove)
                setTextColor(ContextCompat.getColor(context, R.color.danger))
                textSize = 14f
                setPadding((12 * d).toInt(), (12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt())
                setOnClickListener { Store.deleteStamp(this@SettingsActivity, s.id); renderStamps() }
            })
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
