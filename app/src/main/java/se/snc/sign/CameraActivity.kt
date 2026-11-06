package se.snc.sign

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Tar ett foto med vald kamera, i högsta upplösning och bästa kvalitet.
 * - Autofokus hela tiden, tryck för att fokusera, nyp med två fingrar för att zooma, dubbeltryck = 1×.
 * - Innan bilden tas fokuserar kameran i ramen och väntar tills skärpan sitter.
 * - Foto: beskärs till porträtt 3:4. Signatur på papper: bara bläcket sparas. Logga: beskärs till den fyrkantiga ramen.
 */
class CameraActivity : BaseActivity() {

    companion object {
        const val EXTRA_TARGET = "se.snc.sign.TARGET"
        const val EXTRA_LENS = "se.snc.sign.LENS"
        const val EXTRA_PROFILE = "se.snc.sign.PROFILE"
        /** Sökväg till fotot för TARGET_LOGO. */
        const val EXTRA_RESULT = "se.snc.sign.RESULT"
        const val TARGET_PHOTO = "photo"
        /** Signatur på papper för signatur 1, 2 eller 3. */
        const val TARGET_SIG2 = "sig"
        /** En logga eller stämpel som sedan får bakgrunden borttagen. */
        const val TARGET_LOGO = "logo"
    }

    private val target by lazy { intent.getStringExtra(EXTRA_TARGET) ?: TARGET_PHOTO }
    private val profile by lazy { Profile(this, intent.getIntExtra(EXTRA_PROFILE, 1)) }
    private var lens = Camera.FRONT

    private var capture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var hasAutofocus = true
    private lateinit var previewView: PreviewView
    private lateinit var frame: View
    private lateinit var ring: View
    private lateinit var zoomLabel: TextView
    private lateinit var hint: TextView

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
        previewView = findViewById(R.id.preview)
        frame = findViewById(R.id.cameraFrame)
        ring = findViewById(R.id.focusRing)
        zoomLabel = findViewById(R.id.zoomLabel)
        hint = findViewById(R.id.cameraHint)

        // Främre kameran är standard för foto och signatur; bakre för loggor
        lens = intent.getStringExtra(EXTRA_LENS) ?: if (target == TARGET_LOGO) Camera.BACK else profile.camera
        val d = resources.displayMetrics.density
        when (target) {
            TARGET_SIG2 -> {
                frame.layoutParams.apply { width = (320 * d).toInt(); height = (130 * d).toInt() }
                hint.setText(R.string.camera_hint_sig2)
            }
            TARGET_LOGO -> {
                frame.layoutParams.apply { width = (280 * d).toInt(); height = (280 * d).toInt() }
                hint.setText(R.string.camera_hint_logo)
            }
            else -> hint.setText(R.string.camera_hint)
        }
        findViewById<View>(R.id.btnFlip).setOnClickListener {
            lens = if (lens == Camera.FRONT) Camera.BACK else Camera.FRONT
            startCamera()
        }
        findViewById<View>(R.id.btnCameraCancel).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShutter).setOnClickListener { focusThenShoot() }
        bindGestures()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                // Högsta upplösningen i 4:3, samma bildformat som kamerans sensor
                val selector43 = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
                val preview = Preview.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                    .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(selector43)
                    .setJpegQuality(95)
                    .build()
                capture = imageCapture
                provider.unbindAll()
                var selector = if (lens == Camera.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    // Telefonen saknar den kameran: använd den andra
                    lens = if (lens == Camera.FRONT) Camera.BACK else Camera.FRONT
                    selector = if (lens == Camera.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                }
                val cam = provider.bindToLifecycle(this, selector, preview, imageCapture)
                camera = cam
                zoomLabel.visibility = View.GONE
                // Fokusera mitt i ramen när kameran har startat
                previewView.post {
                    val (cx, cy) = frameCenter()
                    val action = FocusMeteringAction.Builder(previewView.meteringPointFactory.createPoint(cx, cy)).build()
                    hasAutofocus = cam.cameraInfo.isFocusMeteringSupported(action)
                    if (hasAutofocus) cam.cameraControl.startFocusAndMetering(action)
                    showFocusTip()
                }
            } catch (e: Exception) {
                Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Många främre kameror har fast fokus. Då visas ett tips om att använda bakre kameran för papper och loggor. */
    private fun showFocusTip() {
        val base = getString(when (target) { TARGET_SIG2 -> R.string.camera_hint_sig2; TARGET_LOGO -> R.string.camera_hint_logo; else -> R.string.camera_hint })
        hint.text = if (!hasAutofocus && target != TARGET_PHOTO) base + "\n" + getString(R.string.camera_no_af) else base
    }

    private fun frameCenter(): Pair<Float, Float> =
        (frame.left + frame.width / 2f) to (frame.top + frame.height / 2f)

    @SuppressLint("ClickableViewAccessibility")
    private fun bindGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val z = cam.cameraInfo.zoomState.value ?: return false
                val ratio = (z.zoomRatio * det.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                cam.cameraControl.setZoomRatio(ratio)
                showZoom(ratio)
                return true
            }
            override fun onScaleEnd(det: ScaleGestureDetector) {
                // Fokusera om efter zoom
                val (cx, cy) = frameCenter(); focusAt(cx, cy, showRing = false)
            }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { focusAt(e.x, e.y, showRing = true); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                camera?.cameraControl?.setZoomRatio(1f); showZoom(1f); return true
            }
        })
        previewView.setOnTouchListener { _, e ->
            scale.onTouchEvent(e)
            if (!scale.isInProgress) taps.onTouchEvent(e)
            true
        }
    }

    private fun showZoom(ratio: Float) {
        zoomLabel.text = String.format(Lang.locale(this), "%.1f×", ratio)
        zoomLabel.visibility = View.VISIBLE
        zoomLabel.removeCallbacks(hideZoom)
        if (ratio <= 1.01f) zoomLabel.postDelayed(hideZoom, 900)
    }
    private val hideZoom = Runnable { zoomLabel.visibility = View.GONE }

    /** Fokuserar (och mäter ljus) där man trycker. En ring visar var. */
    private fun focusAt(x: Float, y: Float, showRing: Boolean) {
        val cam = camera ?: return
        val action = FocusMeteringAction.Builder(previewView.meteringPointFactory.createPoint(x, y))
            .setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) return
        cam.cameraControl.startFocusAndMetering(action)
        if (showRing) {
            ring.animate().cancel()
            ring.x = x - ring.width / 2f; ring.y = y - ring.height / 2f
            ring.alpha = 1f; ring.scaleX = 1.3f; ring.scaleY = 1.3f
            ring.visibility = View.VISIBLE
            ring.animate().scaleX(1f).scaleY(1f).setDuration(220).withEndAction {
                ring.animate().alpha(0f).setStartDelay(900).setDuration(300).withEndAction { ring.visibility = View.GONE }
            }
        }
    }

    /** Fokuserar i ramen, väntar tills skärpan sitter (högst 2,5 s) och tar sedan bilden. */
    private fun focusThenShoot() {
        val cam = camera ?: return
        val shutter = findViewById<View>(R.id.btnShutter)
        if (!shutter.isEnabled) return
        shutter.isEnabled = false
        val (cx, cy) = frameCenter()
        val action = FocusMeteringAction.Builder(previewView.meteringPointFactory.createPoint(cx, cy))
            .disableAutoCancel().build()
        if (!hasAutofocus || !cam.cameraInfo.isFocusMeteringSupported(action)) { takePhoto(); return }
        var shot = false
        val shoot = Runnable { if (!shot) { shot = true; takePhoto() } }
        val f = cam.cameraControl.startFocusAndMetering(action)
        f.addListener({ shoot.run() }, ContextCompat.getMainExecutor(this))
        shutter.postDelayed(shoot, 2500)
    }

    private fun takePhoto() {
        val cap = capture ?: return
        val shutter = findViewById<View>(R.id.btnShutter)
        cap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rotation = image.imageInfo.rotationDegrees
                val raw = image.toBitmap()
                image.close()
                camera?.cameraControl?.cancelFocusAndMetering()
                // Rätta rotationen. Selfies speglas som i förhandsvisningen; papper och loggor speglas aldrig så att texten blir rättvänd.
                val m = Matrix().apply {
                    postRotate(rotation.toFloat())
                    if (lens == Camera.FRONT && target == TARGET_PHOTO) postScale(-1f, 1f)
                }
                val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                when (target) {
                    TARGET_SIG2 -> {
                        val ink = InkExtractor.extract(Store.scaleDown(cropToFrame(upright, 0.06f), 2400))
                        if (ink == null) {
                            Toast.makeText(this@CameraActivity, R.string.sig2_not_found, Toast.LENGTH_LONG).show()
                            shutter.isEnabled = true
                            return
                        }
                        profile.saveSignature(ink, Profile.SOURCE_PHOTO)
                    }
                    TARGET_LOGO -> {
                        val f = File(cacheDir, "logo-${System.currentTimeMillis()}.jpg")
                        FileOutputStream(f).use { Store.scaleDown(cropToFrame(upright, 0.04f), 2400).compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, f.path))
                        finish()
                        return
                    }
                    else -> profile.savePhoto(cropPortrait(upright))
                }
                setResult(RESULT_OK)
                finish()
            }

            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@CameraActivity, R.string.camera_error, Toast.LENGTH_LONG).show()
                shutter.isEnabled = true
            }
        })
    }

    /** Beskär bilden till ramen på skärmen (förhandsvisningen fyller skärmen, "center crop"). */
    private fun cropToFrame(b: Bitmap, margin: Float): Bitmap {
        val vw = previewView.width.toFloat(); val vh = previewView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return b
        val scale = maxOf(vw / b.width, vh / b.height)
        val offX = (b.width * scale - vw) / 2f; val offY = (b.height * scale - vh) / 2f
        val fl = frame.left - frame.width * margin; val ft = frame.top - frame.height * margin
        val fr = frame.right + frame.width * margin; val fb = frame.bottom + frame.height * margin
        val x0 = ((fl + offX) / scale).toInt().coerceIn(0, b.width - 1)
        val y0 = ((ft + offY) / scale).toInt().coerceIn(0, b.height - 1)
        val x1 = ((fr + offX) / scale).toInt().coerceIn(x0 + 1, b.width)
        val y1 = ((fb + offY) / scale).toInt().coerceIn(y0 + 1, b.height)
        return Bitmap.createBitmap(b, x0, y0, x1 - x0, y1 - y0)
    }

    /** Porträtt 3:4 i 1200 × 1600 (krymps aldrig mer än så). */
    private fun cropPortrait(b: Bitmap): Bitmap {
        val ar = 3f / 4f
        var w = b.width.toFloat(); var h = b.height.toFloat()
        if (w / h > ar) w = h * ar else h = w / ar
        val x = ((b.width - w) / 2f).toInt(); val y = ((b.height - h) / 2f).toInt()
        val cropped = Bitmap.createBitmap(b, x, y, w.toInt(), h.toInt())
        return if (cropped.width > 1200) Bitmap.createScaledBitmap(cropped, 1200, 1600, true) else cropped
    }
}
