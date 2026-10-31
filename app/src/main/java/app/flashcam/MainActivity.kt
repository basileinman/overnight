package app.flashcam

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.content.res.ColorStateList
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.OrientationEventListener
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    companion object {
        private const val ORANGE = 0xFFFF7A1F.toInt()
        private const val WHITE = 0xE6FFFFFF.toInt()
        private const val BURST_COUNT = 5
        private const val BURST_INTERVAL_MS = 1000L
        private const val MAX_RAW_EDGE = 2600
        private const val SCREEN_FLASH_MS = 250L
        private const val MAX_PENDING = 3
    }

    private lateinit var root: View
    private lateinit var previewFrame: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var flashOverlay: View
    private lateinit var counterText: TextView
    private lateinit var statusText: TextView
    private lateinit var modeBtn: TextView
    private lateinit var focusBtn: TextView
    private lateinit var flashBtn: TextView
    private lateinit var burstBtn: TextView
    private lateinit var blurBtn: TextView
    private lateinit var flipBtn: ImageView
    private lateinit var thumb: ImageView
    private lateinit var focusReadout: TextView
    private lateinit var evRuler: EvRuler
    private lateinit var evBox: View
    private lateinit var evTitle: TextView
    private lateinit var evValue: TextView

    private lateinit var engine: CameraEngine
    private var cameraReady = false
    private lateinit var processExecutor: ExecutorService
    private val main = Handler(Looper.getMainLooper())
    private val look = Look()
    private val prefs by lazy { getSharedPreferences("flashcam", MODE_PRIVATE) }
    private val pending = AtomicInteger(0)

    private var pmMode = false
    private var far = false
    private var torchFlash = false
    private var burst = false
    private var drag = false
    private var front = false
    private var debug = false
    private var evAm = 0f
    private var evPm = 0.3f
    @Volatile private var busy = false
    @Volatile private var deviceOrientation = 0

    private lateinit var orientationListener: OrientationEventListener

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasPerms()) {
                openCamera()
            } else {
                statusText.text = "CAMERA PERMISSION NEEDED"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        processExecutor = Executors.newSingleThreadExecutor()

        root = findViewById(R.id.root)
        previewFrame = findViewById(R.id.previewFrame)
        texture = findViewById(R.id.preview)
        flashOverlay = findViewById(R.id.flashOverlay)
        counterText = findViewById(R.id.counter)
        statusText = findViewById(R.id.status)
        modeBtn = findViewById(R.id.modeBtn)
        focusBtn = findViewById(R.id.focusBtn)
        flashBtn = findViewById(R.id.flashBtn)
        burstBtn = findViewById(R.id.burstBtn)
        blurBtn = findViewById(R.id.blurBtn)
        flipBtn = findViewById(R.id.flipBtn)
        thumb = findViewById(R.id.thumb)
        focusReadout = findViewById(R.id.focusReadout)
        evRuler = findViewById(R.id.evRuler)
        evBox = findViewById(R.id.evBox)
        evTitle = findViewById(R.id.evTitle)
        evValue = findViewById(R.id.evValue)
        thumb.clipToOutline = true
        engine = CameraEngine(this, texture)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // 2:3 viewfinder (35mm frame)
        val frameW = resources.displayMetrics.widthPixels - (24 * resources.displayMetrics.density).toInt()
        previewFrame.layoutParams = (previewFrame.layoutParams as LinearLayout.LayoutParams).apply {
            height = frameW * 3 / 2
        }

        pmMode = prefs.getBoolean("pm", false)
        far = prefs.getBoolean("far", false)
        torchFlash = prefs.getBoolean("torch", false)
        burst = prefs.getBoolean("burst", false)
        drag = prefs.getBoolean("drag", false)
        front = prefs.getBoolean("front", false)
        debug = prefs.getBoolean("debug", false)
        evAm = prefs.getFloat("evAm", 0f)
        evPm = prefs.getFloat("evPm", 0.3f)
        refreshUi()

        setupViewfinderTouch()
        modeBtn.setOnClickListener { if (!busy) { pmMode = !pmMode; save(); refreshUi(); reopen() } }
        focusBtn.setOnClickListener { if (!busy) { far = !far; save(); refreshUi(); reopen() } }
        flashBtn.setOnClickListener { torchFlash = !torchFlash; save(); refreshUi() }
        burstBtn.setOnClickListener { burst = !burst; save(); refreshUi() }
        blurBtn.setOnClickListener { drag = !drag; save(); refreshUi() }
        flipBtn.setOnClickListener { if (!busy) { front = !front; save(); refreshUi(); reopen() } }
        thumb.setOnClickListener { startActivity(Intent(this, GalleryActivity::class.java)) }
        statusText.setOnLongClickListener {
            debug = !debug; save(); refreshUi()
            toast(if (debug) "Debug on: originals saved to FlashCam/Originals" else "Debug off")
            true
        }

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { engine.applyTransform() }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceOrientation = ((orientation + 45) / 90 * 90) % 360
            }
        }

        if (!hasPerms()) permLauncher.launch(neededPerms())
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        if (texture.isAvailable) openCamera()
        loadLastThumb()
    }

    override fun onPause() {
        super.onPause()
        orientationListener.disable()
        cameraReady = false
        engine.close()
        hideScreenFlash()
    }

    override fun onDestroy() {
        super.onDestroy()
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

    private fun openCamera() {
        if (!hasPerms() || !texture.isAvailable) return
        cameraReady = false
        engine.open(
            CameraEngine.Config(front = front, pm = pmMode, far = far, ev = currentEv()),
            onReady = { info ->
                cameraReady = true
                if (info.isFront != front) { front = info.isFront; refreshUi() }
            },
            onError = { msg -> toast(msg) },
        )
    }

    private fun reopen() {
        engine.close()
        openCamera()
    }

    private fun flashKind(): CameraEngine.Flash = when {
        engine.isFront -> CameraEngine.Flash.SCREEN
        pmMode -> CameraEngine.Flash.NONE
        !engine.hasFlash -> CameraEngine.Flash.NONE
        torchFlash || !engine.manualSensor -> CameraEngine.Flash.TORCH
        else -> CameraEngine.Flash.PULSE
    }

    // ---------- shooting ----------

    private fun shoot() {
        if (!cameraReady || busy || pending.get() >= MAX_PENDING) return
        busy = true
        texture.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val n = if (burst) BURST_COUNT else 1
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val params = Look.Params.forMode(if (pmMode) Look.Mode.PM else Look.Mode.AM, drag)
        val stripFrames: MutableList<Bitmap> = Collections.synchronizedList(ArrayList())
        captureNext(0, n, stamp, params, stripFrames)
    }

    private fun captureNext(i: Int, n: Int, stamp: String, params: Look.Params, stripFrames: MutableList<Bitmap>) {
        if (n > 1) {
            counterText.visibility = View.VISIBLE
            counterText.text = "${i + 1}/$n"
        }
        val started = SystemClock.elapsedRealtime()
        val kind = flashKind()
        val rotation = jpegRotation()
        val mirror = engine.isFront

        val take = {
            engine.capture(kind) { bytes ->
                val frame = bytes?.let { decode(it, rotation, mirror) }
                main.post {
                    if (kind == CameraEngine.Flash.SCREEN) hideScreenFlash()
                    if (frame == null) {
                        toast("Capture failed")
                        endShoot(stamp, n, stripFrames)
                        return@post
                    }
                    pending.incrementAndGet()
                    processExecutor.execute {
                        try { processAndSave(frame, stamp, i, n, params, stripFrames) } finally { pending.decrementAndGet() }
                    }
                    if (i + 1 < n) {
                        val wait = max(150L, BURST_INTERVAL_MS - (SystemClock.elapsedRealtime() - started))
                        main.postDelayed({ captureNext(i + 1, n, stamp, params, stripFrames) }, wait)
                    } else {
                        endShoot(stamp, n, stripFrames)
                    }
                }
            }
        }

        if (kind == CameraEngine.Flash.SCREEN) {
            showScreenFlash()
            main.postDelayed({ take() }, SCREEN_FLASH_MS)
        } else {
            take()
        }
    }

    private fun jpegRotation(): Int {
        val dev = deviceOrientation
        return if (engine.isFront) (engine.sensorOrientation - dev + 360) % 360
        else (engine.sensorOrientation + dev) % 360
    }

    /** Decode the JPEG at a manageable size and turn it upright. */
    private fun decode(bytes: ByteArray, rotation: Int, mirror: Boolean): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_RAW_EDGE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp == null) null else {
            val s = min(1f, MAX_RAW_EDGE.toFloat() / max(bmp.width, bmp.height))
            val m = Matrix()
            m.postScale(s, s)
            m.postRotate(rotation.toFloat())
            if (mirror) m.postScale(-1f, 1f)  // selfies match the viewfinder
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            out
        }
    } catch (t: Throwable) {
        null
    }

    private fun endShoot(stamp: String, n: Int, stripFrames: MutableList<Bitmap>) {
        counterText.visibility = View.GONE
        busy = false
        if (n <= 1) return
        processExecutor.execute {
            if (stripFrames.size >= 2) {
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
        }
    }

    private fun processAndSave(
        frame: Bitmap, stamp: String, i: Int, n: Int,
        params: Look.Params, stripFrames: MutableList<Bitmap>,
    ) {
        try {
            if (debug) {
                val tag = if (params.mode == Look.Mode.PM) "PM" else "AM"
                saveJpeg(frame, "ORIG_${stamp}_${i + 1}_$tag.jpg", "FlashCam/Originals", 97)
            }
            val out = look.process(frame, params, System.nanoTime())
            frame.recycle()
            val name = if (n > 1) "FLASH_${stamp}_${i + 1}.jpg" else "FLASH_$stamp.jpg"
            saveJpeg(out, name)
            if (n > 1) {
                stripFrames.add(Bitmap.createScaledBitmap(out, 720, max(1, 720 * out.height / out.width), true))
            }
            val th = Bitmap.createScaledBitmap(out, 160, max(1, 160 * out.height / out.width), true)
            out.recycle()
            main.post {
                thumb.setImageBitmap(th)
                bumpRoll()
            }
        } catch (t: Throwable) {
            main.post { toast("Processing failed: ${t.message}") }
        }
    }

    // ---------- saving ----------

    private fun saveJpeg(bmp: Bitmap, name: String, folder: String = "FlashCam", quality: Int = 92): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + folder)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), folder)
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Images.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        }
        return uri
    }

    private fun loadLastThumb() {
        processExecutor.execute {
            val last = Roll.load(this).firstOrNull() ?: return@execute
            val bmp = Roll.thumbnail(this, last, 160)
            if (bmp != null) main.post { thumb.setImageBitmap(bmp) }
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
        val grey = 0x80FFFFFF.toInt()
        // reference: fixed focus + flash controls. DAY: autofocus, no flash.
        modeBtn.text = if (pmMode) "DAY" else "reference"
        val modeColor = if (pmMode) WHITE else ORANGE
        modeBtn.setTextColor(modeColor)
        modeBtn.compoundDrawableTintList = ColorStateList.valueOf(modeColor)

        val fixedFocus = !pmMode && !front
        focusBtn.text = if (far) "FAR ˅" else "CLOSE ˅"
        focusBtn.alpha = if (fixedFocus) 1f else 0.4f
        focusBtn.isEnabled = fixedFocus
        focusBtn.compoundDrawableTintList = ColorStateList.valueOf(ORANGE)
        focusReadout.text = when {
            front -> "SELFIE"
            pmMode -> "AF"
            far -> "6ft"
            else -> "3ft"
        }
        focusReadout.compoundDrawableTintList = ColorStateList.valueOf(ORANGE)

        flashBtn.visibility = if (pmMode || front) View.GONE else View.VISIBLE
        flashBtn.text = if (torchFlash) "TORCH" else "PULSE"
        flashBtn.setTextColor(if (torchFlash) grey else ORANGE)
        burstBtn.text = if (burst) "×5" else "×1"
        burstBtn.setTextColor(if (burst) ORANGE else grey)
        blurBtn.setTextColor(if (drag) ORANGE else grey)
        refreshRoll()
    }

    // ---------- viewfinder: tap = shoot, hold + slide = exposure ----------

    private fun currentEv() = if (pmMode) evPm else evAm

    private fun setupViewfinderTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val holdMs = 280L
        var downY = 0f
        var downX = 0f
        var startEv = 0f
        var moved = false
        var adjusting = false
        val beginAdjust = Runnable {
            adjusting = true
            startEv = currentEv()
            texture.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showEv(startEv)
        }
        texture.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; moved = false; adjusting = false
                    main.postDelayed(beginAdjust, holdMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (adjusting) {
                        // full viewfinder height = 4 EV; slide up to brighten
                        val delta = (downY - ev.y) / v.height * 4f
                        val e = (Math.round((startEv + delta) * 10f) / 10f).coerceIn(-2f, 2f)
                        if (e != currentEv()) {
                            if (pmMode) evPm = e else evAm = e
                            showEv(e)
                            engine.setEv(e)
                        }
                    } else if (Math.abs(ev.x - downX) > slop || Math.abs(ev.y - downY) > slop) {
                        moved = true
                        main.removeCallbacks(beginAdjust)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(beginAdjust)
                    if (adjusting) {
                        save()
                        main.postDelayed(hideEvRunnable, 700)
                    } else if (!moved) {
                        shoot()
                    }
                    adjusting = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(beginAdjust)
                    if (adjusting) hideEv()
                    adjusting = false
                }
            }
            true
        }
    }

    private val hideEvRunnable = Runnable { hideEv() }

    private fun showEv(e: Float) {
        main.removeCallbacks(hideEvRunnable)
        evTitle.text = if (pmMode) "DAY EXP" else "reference EXP"
        evValue.text = (if (e > 0f) "+" else "") + "%.1f EV".format(e)
        evRuler.ev = e
        evRuler.visibility = View.VISIBLE
        evBox.visibility = View.VISIBLE
    }

    private fun hideEv() {
        evRuler.visibility = View.GONE
        evBox.visibility = View.GONE
    }

    private fun save() {
        prefs.edit()
            .putBoolean("pm", pmMode)
            .putBoolean("far", far)
            .putBoolean("torch", torchFlash)
            .putBoolean("burst", burst)
            .putBoolean("drag", drag)
            .putBoolean("front", front)
            .putBoolean("debug", debug)
            .putFloat("evAm", evAm)
            .putFloat("evPm", evPm)
            .apply()
    }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun refreshRoll() {
        val count = if (prefs.getString("rollDate", "") == today()) prefs.getInt("rollCount", 0) else 0
        statusText.text = "ROLL %02d".format(count) + if (debug) " · DBG" else ""
    }

    private fun bumpRoll() {
        val t = today()
        val count = (if (prefs.getString("rollDate", "") == t) prefs.getInt("rollCount", 0) else 0) + 1
        prefs.edit().putString("rollDate", t).putInt("rollCount", count).apply()
        refreshRoll()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
