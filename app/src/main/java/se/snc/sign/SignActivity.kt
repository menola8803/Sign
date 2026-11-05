package se.snc.sign

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import java.io.File

/**
 * Signering. Skärmen vänds till liggande läge (manifestet: sensorLandscape) så att det finns plats
 * att signera. Om ett foto används visas det till vänster, så man ser hur fältet blir.
 */
class SignActivity : BaseActivity() {

    companion object {
        const val EXTRA_FIELD = "se.snc.sign.FIELD"
        const val EXTRA_TITLE = "se.snc.sign.TITLE"
        const val EXTRA_FILE = "se.snc.sign.FILE"
        const val EXTRA_SHOW_PHOTO = "se.snc.sign.SHOW_PHOTO"
        /** Sökväg till fotot som visas bredvid ytan (signaturens profil). */
        const val EXTRA_PHOTO_PATH = "se.snc.sign.PHOTO_PATH"
    }

    private lateinit var pad: SignatureView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = true
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<View>(R.id.signRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = b.left, right = b.right, top = b.top, bottom = b.bottom)
            insets
        }

        pad = findViewById(R.id.pad)
        pad.ink = Prefs.ink(this)
        findViewById<TextView>(R.id.signWhere).text = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val photo = findViewById<ImageView>(R.id.signPhoto)
        val photoPath = intent.getStringExtra(EXTRA_PHOTO_PATH)
        val bmp = photoPath?.let { android.graphics.BitmapFactory.decodeFile(it) }
        if (bmp != null) {
            photo.visibility = View.VISIBLE
            photo.setImageBitmap(bmp)
        }

        val blue = findViewById<View>(R.id.inkBlue)
        val black = findViewById<View>(R.id.inkBlack)
        fun selectInk(c: Int) {
            pad.ink = c; Prefs.setInk(this, c)
            blue.isSelected = c == Ink.BLUE; black.isSelected = c == Ink.BLACK
        }
        selectInk(Prefs.ink(this))
        blue.setOnClickListener { selectInk(Ink.BLUE) }
        black.setOnClickListener { selectInk(Ink.BLACK) }

        val hint = findViewById<View>(R.id.signHint)
        pad.onChange = { hint.visibility = if (pad.isEmpty) View.VISIBLE else View.INVISIBLE }

        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<View>(R.id.btnUndo).setOnClickListener { pad.undo() }
        findViewById<View>(R.id.btnClear).setOnClickListener { pad.clear() }
        findViewById<View>(R.id.btnDone).setOnClickListener { done() }
    }

    private fun done() {
        val bmp = pad.toTrimmedBitmap()
        if (bmp == null) {
            Toast.makeText(this, R.string.sign_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val f = Store.savePng(bmp, File(Store.signatureDir(this), "sig-${System.currentTimeMillis()}.png"))
        setResult(RESULT_OK, Intent().putExtra(EXTRA_FIELD, intent.getLongExtra(EXTRA_FIELD, -1)).putExtra(EXTRA_FILE, f.path))
        finish()
    }
}
