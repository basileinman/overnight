package app.flashcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
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
import android.util.Size
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    companion object {
        private const val ORANGE = 0xFFFF7A1F.toInt()
        private const val WHITE = 0xE6FFFFFF.toInt()
        private const val BURST_COUNT = 5
        private const val BURST_INTERVAL_MS = 1000L
        private const val MAX_RAW_EDGE = 2600f
    }

    private lateinit var root: View
    private lateinit var previewFrame: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var flashOverlay: View
    private lateinit var counterText: TextView
    private lateinit var statusText: TextView
    private lateinit var modeBtn: TextView
    private lateinit var focusBtn: TextView
    private lateinit var burstBtn: TextView
    private lateinit var blurBtn: TextView
    private lateinit var flipBtn: TextView
    private lateinit var thumb: ImageView
    private lateinit var shutter: View

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var processExecutor: ExecutorService
    private val main = Handler(Looper.getMainLooper())
    private val look = Look()
    private val prefs by lazy { getSharedPreferences("flashcam", MODE_PRIVATE) }

    private var pmMode = false
    private var groupMode = false
    private var burst = false
    private var drag = false
    private var front = false
    @Volatile private var busy = false
    private var lastUri: Uri? = null

    private lateinit var orientationListener: OrientationEventListener

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPerms()) {
                startCamera()
            } else {
                statusText.text = "CAMERA PERMISSION NEEDED"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraExecutor = Executors.newSingleThreadExecutor()
        processExecutor = Executors.newSingleThreadExecutor()

        root = findViewById(R.id.root)
        previewFrame = findViewById(R.id.previewFrame)
        previewView = findViewById(R.id.preview)
        flashOverlay = findViewById(R.id.flashOverlay)
        counterText = findViewById(R.id.counter)
        statusText = findViewById(R.id.status)
        modeBtn = findViewById(R.id.modeBtn)
        focusBtn = findViewById(R.id.focusBtn)
        burstBtn = findViewById(R.id.burstBtn)
        blurBtn = findViewById(R.id.blurBtn)
        flipBtn = findViewById(R.id.flipBtn)
        thumb = findViewById(R.id.thumb)
        shutter = findViewById(R.id.shutter)

        // keep controls clear of the status / nav bars (Android 15 forces edge-to-edge)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // 2:3 viewfinder (35mm frame); the 3:4 camera feed is center-cropped to match
        val screenW = resources.displayMetrics.widthPixels
        previewFrame.layoutParams = (previewFrame.layoutParams as LinearLayout.LayoutParams).apply {
            height = screenW * 3 / 2
        }
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        pmMode = prefs.getBoolean("pm", false)
        groupMode = prefs.getBoolean("group", false)
        burst = prefs.getBoolean("burst", false)
        drag = prefs.getBoolean("drag", false)
        front = prefs.getBoolean("front", false)
        refreshUi()
        refreshRoll()

        previewView.setOnClickListener { shoot() }
        shutter.setOnClickListener { shoot() }
        modeBtn.setOnClickListener {
            if (busy) return@setOnClickListener
            pmMode = !pmMode; save(); refreshUi(); bindCamera()
        }
        focusBtn.setOnClickListener {
            groupMode = !groupMode; save(); refreshUi(); bindCamera()
        }
        burstBtn.setOnClickListener { burst = !burst; save(); refreshUi() }
        blurBtn.setOnClickListener { drag = !drag; save(); refreshUi() }
        flipBtn.setOnClickListener {
            if (busy) return@setOnClickListener
            front = !front; save(); refreshUi(); bindCamera()
        }
        thumb.setOnClickListener { openLast() }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
            }
        }

        if (hasPerms()) startCamera() else permLauncher.launch(neededPerms())
    }

    override fun onStart() {
        super.onStart()
        orientationListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        processExecutor.shutdown()
    }

    // ---------- permissions ----------

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else arrayOf(Manifest.permission.CAMERA)

    private fun hasPerms() = neededPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    // ---------- camera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val wantFacing = if (front) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        var info: CameraInfo? = provider.availableCameraInfos.firstOrNull { it.lensFacing == wantFacing }
        if (info == null) {
            // device lacks that lens; fall back to whatever exists
            front = !front
            refreshUi()
            info = provider.availableCameraInfos.firstOrNull()
        }
        if (info == null) {
            statusText.text = "NO CAMERA"
            return
        }
        val isFront = info.lensFacing == CameraSelector.LENS_FACING_FRONT
        val selector = CameraSelector.Builder().requireLensFacing(info.lensFacing).build()

        val minFocus = try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        } catch (t: Throwable) { 0f }
        val hasFlash = info.hasFlashUnit()

        val previewBuilder = Preview.Builder().setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
        )
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(if (!isFront && hasFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(3264, 2448),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )

        // Fixed focus like a point-and-shoot: ~0.8 m for portrait, ~2.2 m for groups.
        if (!isFront && minFocus > 0f) {
            val diopters = min(if (groupMode) 1f / 2.2f else 1f / 0.8f, minFocus)
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
            Camera2Interop.Extender(captureBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
        }

        val preview = previewBuilder.build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val capture = captureBuilder.build()

        try {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(this, selector, preview, capture)
            imageCapture = capture
            // Underexpose so the flash lights the subject and the background drops off naturally.
            val exp = camera.cameraInfo.exposureState
            if (exp.isExposureCompensationSupported) {
                val step = exp.exposureCompensationStep.toFloat()
                if (step > 0f) {
                    val ev = if (pmMode) -0.5f else -1.0f
                    val idx = Math.round(ev / step)
                    camera.cameraControl.setExposureCompensationIndex(
                        idx.coerceIn(exp.exposureCompensationRange.lower, exp.exposureCompensationRange.upper)
                    )
                }
            }
        } catch (t: Throwable) {
            imageCapture = null
            toast("Camera error: ${t.message}")
        }
    }

    // ---------- shooting ----------

    private fun shoot() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true
        shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        val n = if (burst) BURST_COUNT else 1
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val params = Look.Params.forMode(if (pmMode) Look.Mode.PM else Look.Mode.AM, drag)
        val stripFrames: MutableList<Bitmap> = Collections.synchronizedList(ArrayList())
        captureNext(ic, 0, n, stamp, params, stripFrames)
    }

    private fun captureNext(
        ic: ImageCapture, i: Int, n: Int, stamp: String,
        params: Look.Params, stripFrames: MutableList<Bitmap>,
    ) {
        if (n > 1) {
            counterText.visibility = View.VISIBLE
            counterText.text = "${i + 1}/$n"
        }
        val started = SystemClock.elapsedRealtime()
        val screenFlash = front

        val take: () -> Unit = {
            ic.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val frame: Bitmap? = try {
                        uprightScaled(image)
                    } catch (t: Throwable) {
                        null
                    } finally {
                        image.close()
                    }
                    main.post {
                        if (screenFlash) hideScreenFlash()
                        if (frame == null) {
                            toast("Capture failed")
                            endShoot(stamp, n, stripFrames)
                            return@post
                        }
                        processExecutor.execute { processAndSave(frame, stamp, i, n, params, stripFrames) }
                        if (i + 1 < n) {
                            val wait = max(150L, BURST_INTERVAL_MS - (SystemClock.elapsedRealtime() - started))
                            main.postDelayed({ captureNext(ic, i + 1, n, stamp, params, stripFrames) }, wait)
                        } else {
                            endShoot(stamp, n, stripFrames)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    main.post {
                        if (screenFlash) hideScreenFlash()
                        toast("Capture error: ${exception.message}")
                        endShoot(stamp, n, stripFrames)
                    }
                }
            })
        }

        if (screenFlash) {
            showScreenFlash()
            main.postDelayed({ take() }, 350)
        } else {
            take()
        }
    }

    /** Runs after all frames are queued; the processing queue is ordered, so this runs last. */
    private fun endShoot(stamp: String, n: Int, stripFrames: MutableList<Bitmap>) {
        counterText.visibility = View.GONE
        processExecutor.execute {
            if (n > 1 && stripFrames.size >= 2) {
                try {
                    val frames = synchronized(stripFrames) { stripFrames.toList() }
                    val date = SimpleDateFormat("dd.MM.yy", Locale.US).format(Date())
                    val strip = Strip.make(frames, "FLASHCAM  $date  ×${frames.size}")
                    saveJpeg(strip, "FLASH_${stamp}_strip.jpg")
                    strip.recycle()
                    frames.forEach { it.recycle() }
                } catch (t: Throwable) {
                    main.post { toast("Strip failed: ${t.message}") }
                }
            }
            main.post { busy = false }
        }
    }

    private fun uprightScaled(image: ImageProxy): Bitmap {
        val bmp = image.toBitmap()
        val rot = image.imageInfo.rotationDegrees
        val s = min(1f, MAX_RAW_EDGE / max(bmp.width, bmp.height))
        if (rot == 0 && s >= 1f) return bmp
        val m = Matrix()
        m.postScale(s, s)
        m.postRotate(rot.toFloat())
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun processAndSave(
        frame: Bitmap, stamp: String, i: Int, n: Int,
        params: Look.Params, stripFrames: MutableList<Bitmap>,
    ) {
        try {
            val out = look.process(frame, params, System.nanoTime())
            frame.recycle()
            val name = if (n > 1) "FLASH_${stamp}_${i + 1}.jpg" else "FLASH_$stamp.jpg"
            val uri = saveJpeg(out, name)
            if (n > 1) {
                stripFrames.add(Bitmap.createScaledBitmap(out, 720, max(1, 720 * out.height / out.width), true))
            }
            val th = Bitmap.createScaledBitmap(out, 160, max(1, 160 * out.height / out.width), true)
            out.recycle()
            main.post {
                thumb.setImageBitmap(th)
                if (uri != null) lastUri = uri
                bumpRoll()
            }
        } catch (t: Throwable) {
            main.post { toast("Processing failed: ${t.message}") }
        }
    }

    // ---------- saving ----------

    private fun saveJpeg(bmp: Bitmap, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FlashCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FlashCam")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        return uri
    }

    private fun openLast() {
        val uri = lastUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (t: Throwable) {
            toast("No gallery app found")
        }
    }

    // ---------- screen flash (front camera) ----------

    private fun showScreenFlash() {
        flashOverlay.visibility = View.VISIBLE
        val lp = window.attributes
        lp.screenBrightness = 1f
        window.attributes = lp
    }

    private fun hideScreenFlash() {
        flashOverlay.visibility = View.GONE
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    // ---------- ui state ----------

    private fun refreshUi() {
        modeBtn.text = if (pmMode) "DAY" else "reference"
        focusBtn.text = if (groupMode) "GROUP" else "PORTRAIT"
        burstBtn.text = if (burst) "×5" else "×1"
        burstBtn.setTextColor(if (burst) ORANGE else WHITE)
        blurBtn.setTextColor(if (drag) ORANGE else WHITE)
        flipBtn.text = if (front) "FRONT" else "BACK"
    }

    private fun save() {
        prefs.edit()
            .putBoolean("pm", pmMode)
            .putBoolean("group", groupMode)
            .putBoolean("burst", burst)
            .putBoolean("drag", drag)
            .putBoolean("front", front)
            .apply()
    }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun refreshRoll() {
        val count = if (prefs.getString("rollDate", "") == today()) prefs.getInt("rollCount", 0) else 0
        statusText.text = "ROLL %02d".format(count)
    }

    private fun bumpRoll() {
        val t = today()
        val count = (if (prefs.getString("rollDate", "") == t) prefs.getInt("rollCount", 0) else 0) + 1
        prefs.edit().putString("rollDate", t).putInt("rollCount", count).apply()
        statusText.text = "ROLL %02d".format(count)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
