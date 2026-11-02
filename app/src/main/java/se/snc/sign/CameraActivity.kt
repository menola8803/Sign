package se.snc.sign

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.FileOutputStream

/**
 * Tar ett foto med vald kamera.
 * - Foto till signatur 1: beskärs till porträtt 3:4.
 * - Signatur 2 på papper: beskärs till ramen, papperet görs genomskinligt och bara bläcket sparas.
 */
class CameraActivity : BaseActivity() {

    companion object {
        const val EXTRA_TARGET = "se.snc.sign.TARGET"
        const val EXTRA_LENS = "se.snc.sign.LENS"
        const val TARGET_PHOTO = "photo"
        const val TARGET_SIG2 = "sig2"
    }

    private val target by lazy { intent.getStringExtra(EXTRA_TARGET) ?: TARGET_PHOTO }
    private var lens = Camera.FRONT

    private var capture: ImageCapture? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, R.string.camera_permission, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        val bar = findViewById<View>(R.id.cameraBar)
        val pb = bar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.cameraRoot)) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            bar.updatePadding(bottom = pb + b.bottom)
            insets
        }
        lens = intent.getStringExtra(EXTRA_LENS) ?: if (target == TARGET_SIG2) Camera.BACK else Prefs.photoCamera(this)
        if (target == TARGET_SIG2) {
            // Bred ram för en signatur på papper
            val d = resources.displayMetrics.density
            findViewById<View>(R.id.cameraFrame).layoutParams.apply { width = (320 * d).toInt(); height = (130 * d).toInt() }
            findViewById<android.widget.TextView>(R.id.cameraHint).setText(R.string.camera_hint_sig2)
        }
        findViewById<View>(R.id.btnFlip).setOnClickListener {
            lens = if (lens == Camera.FRONT) Camera.BACK else Camera.FRONT
            startCamera()
        }
        findViewById<View>(R.id.btnCameraCancel).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShutter).setOnClickListener { takePhoto() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.preview).surfaceProvider)
                }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                capture = imageCapture
                provider.unbindAll()
                var selector = if (lens == Camera.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    // Telefonen saknar den kameran: använd den andra
                    lens = if (lens == Camera.FRONT) Camera.BACK else Camera.FRONT
                    selector = if (lens == Camera.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                }
                provider.bindToLifecycle(this, selector, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val cap = capture ?: return
        findViewById<View>(R.id.btnShutter).isEnabled = false
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val raw = image.toBitmap()
                image.close()
                // Rätta rotationen. Selfies speglas som i förhandsvisningen; papper speglas aldrig så att texten blir rättvänd.
                val m = Matrix().apply {
                    postRotate(rotation.toFloat())
                    if (lens == Camera.FRONT && target == TARGET_PHOTO) postScale(-1f, 1f)
                }
                val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                if (target == TARGET_SIG2) {
                    val frame = findViewById<View>(R.id.cameraFrame)
                    val prev = findViewById<View>(R.id.preview)
                    val ok = Store.saveSig2(this@CameraActivity, cropToFrame(upright, frame, prev))
                    if (!ok) {
                        Toast.makeText(this@CameraActivity, R.string.sig2_not_found, Toast.LENGTH_LONG).show()
                        findViewById<View>(R.id.btnShutter).isEnabled = true
                        return
                    }
                } else {
                    save(cropPortrait(upright))
                }
                setResult(RESULT_OK)
                finish()
            }

            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@CameraActivity, R.string.camera_error, Toast.LENGTH_LONG).show()
                findViewById<View>(R.id.btnShutter).isEnabled = true
            }
        })
    }

    /** Beskär bilden till ramen på skärmen (förhandsvisningen fyller skärmen, "center crop"). */
    private fun cropToFrame(b: Bitmap, frame: View, preview: View): Bitmap {
        val vw = preview.width.toFloat(); val vh = preview.height.toFloat()
        if (vw <= 0f || vh <= 0f) return b
        val scale = maxOf(vw / b.width, vh / b.height)
        val offX = (b.width * scale - vw) / 2f; val offY = (b.height * scale - vh) / 2f
        val margin = 0.06f
        val fl = frame.left - frame.width * margin; val ft = frame.top - frame.height * margin
        val fr = frame.right + frame.width * margin; val fb = frame.bottom + frame.height * margin
        val x0 = ((fl + offX) / scale).toInt().coerceIn(0, b.width - 1)
        val y0 = ((ft + offY) / scale).toInt().coerceIn(0, b.height - 1)
        val x1 = ((fr + offX) / scale).toInt().coerceIn(x0 + 1, b.width)
        val y1 = ((fb + offY) / scale).toInt().coerceIn(y0 + 1, b.height)
        return Bitmap.createBitmap(b, x0, y0, x1 - x0, y1 - y0)
    }

    private fun cropPortrait(b: Bitmap): Bitmap {
        val ar = 3f / 4f
        var w = b.width.toFloat(); var h = b.height.toFloat()
        if (w / h > ar) w = h * ar else h = w / ar
        val x = ((b.width - w) / 2f).toInt(); val y = ((b.height - h) / 2f).toInt()
        val cropped = Bitmap.createBitmap(b, x, y, w.toInt(), h.toInt())
        return Bitmap.createScaledBitmap(cropped, 600, 800, true)
    }

    private fun save(b: Bitmap) {
        val f = Store.photoFile(this)
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        Composer.forget(f.path)
    }
}
