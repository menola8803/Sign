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

/** Tar ett foto med frontkameran. Fotot beskärs till porträtt 3:4 och sparas privat i appen. */
class CameraActivity : BaseActivity() {

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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture)
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
                // Rätta rotationen och spegla som i förhandsvisningen
                val m = Matrix().apply { postRotate(rotation.toFloat()); postScale(-1f, 1f) }
                val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                save(cropPortrait(upright))
                setResult(RESULT_OK)
                finish()
            }

            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@CameraActivity, R.string.camera_error, Toast.LENGTH_LONG).show()
                findViewById<View>(R.id.btnShutter).isEnabled = true
            }
        })
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
