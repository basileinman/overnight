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
        private const val AUTO_MODE_AFTER_MS = 30 * 60 * 1000L
        private const val NIGHT_EV = 6.0   // dimmer than a lamp-lit room -> NIGHT
        private const val DAY_EV = 8.0     // window-lit room or brighter -> DAY
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
    private var far = false          // fixed FAR (when not AUTO)
    private var autoFocus = true     // AUTO: focus + flash follow subject distance
    private var secureMode = false   // opened from the lock screen
    private var modeLocked = false   // user picked NIGHT/DAY by hand; auto switching paused
    private var darkVotes = 0
    private var torchFlash = false
    private var burst = false
    private var double = false        // DOUBLE exposure mode (two taps, one frame)
    private var firstExposure: Bitmap? = null
    private var firstMeta: org.json.JSONObject? = null
    private lateinit var ghost: ImageView
    private var drag = false       // fake BLUR effect
    private var realDrag = false   // slow shutter + flash
    private var front = false
    private var debug = false
    private var evAm = 0f
    private var evPm = 0.1f
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

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyLaunchMode(intent)
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
        ghost = findViewById(R.id.ghost)
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

        pmMode = SunClock.isDaytime()  // NIGHT/DAY follows the clock on launch
        far = prefs.getBoolean("far", false)
        autoFocus = true  // focus is always automatic now (with a fixed 4 ft fallback in the dark)
        torchFlash = false  // TORCH is only an automatic fallback for phones without PULSE support
        burst = prefs.getBoolean("burst", false)
        double = prefs.getBoolean("double", false) && !burst
        drag = false  // BLUR always starts off
        front = prefs.getBoolean("front", false)
        debug = prefs.getBoolean("debug", false)
        evAm = prefs.getFloat("evAm", 0f)
        evPm = prefs.getFloat("evPm2", 0.1f)
        refreshUi()

        setupViewfinderTouch()
        modeBtn.setOnClickListener { if (!busy) { pmMode = !pmMode; modeLocked = true; save(); refreshUi(); engine.reconfigure(camConfig()) } }
        modeBtn.setOnLongClickListener {
            modeLocked = false; refreshUi(); toast("NIGHT / DAY back on auto"); true
        }
        focusBtn.setOnClickListener {
            if (busy) return@setOnClickListener
            // AUTO -> CLOSE -> FAR -> AUTO
            when {
                autoFocus -> { autoFocus = false; far = false }
                !far -> far = true
                else -> { far = false; autoFocus = true }
            }
            save(); refreshUi(); engine.reconfigure(camConfig())
        }
        flashBtn.setOnClickListener { torchFlash = !torchFlash; save(); refreshUi() }
        burstBtn.setOnClickListener {
            // SINGLE -> BURST -> DOUBLE -> SINGLE
            when {
                double -> { double = false; cancelDouble() }
                burst -> { burst = false; double = true }
                else -> burst = true
            }
            save(); refreshUi()
        }
        counterText.setOnClickListener { if (firstExposure != null) { cancelDouble(); toast("Double exposure cancelled") } }
        blurBtn.setOnClickListener {
            // OFF -> BLUR (effect) -> DRAG (real slow shutter + flash) -> OFF
            when {
                !drag && !realDrag -> drag = true
                drag -> { drag = false; realDrag = true }
                else -> realDrag = false
            }
            engine.setDrag(realDrag)
            refreshUi()
        }
        flipBtn.setOnClickListener { if (!busy) { front = !front; save(); refreshUi(); reopen() } }
        thumb.setOnLongClickListener {
            if (debug) startActivity(Intent(this, LookLabActivity::class.java))
            else toast("Look Lab: turn on debug first (hold the roll counter)")
            true
        }
        thumb.setOnClickListener {
            if (secureMode) {
                // from the lock screen: unlock first, then show the roll
                val km = getSystemService(android.app.KeyguardManager::class.java)
                km?.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() { startActivity(Intent(this@MainActivity, GalleryActivity::class.java)) }
                })
            } else startActivity(Intent(this, GalleryActivity::class.java))
        }
        statusText.setOnClickListener { if (!busy) rollMenu() }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyLaunchMode(intent)
    }

    private fun applyLaunchMode(i: Intent?) {
        secureMode = i?.action == android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
        setShowWhenLocked(secureMode)
        setTurnScreenOn(secureMode)
        if (::thumb.isInitialized && secureMode) thumb.setImageDrawable(null)  // don't show the last photo on a locked phone
    }

    override fun onResume() {
        super.onResume()
        // Back after a while (or first launch): re-pick NIGHT/DAY from the clock.
        // A manual switch sticks while you keep using the app.
        val away = System.currentTimeMillis() - prefs.getLong("pausedAt", 0L)
        if (away > AUTO_MODE_AFTER_MS) {
            modeLocked = false
            val day = SunClock.isDaytime()   // first guess; the light meter takes over once the camera is up
            if (day != pmMode) { pmMode = day; refreshUi() }
        }
        main.removeCallbacks(lightTick)
        main.postDelayed(lightTick, 800)
        orientationListener.enable()
        if (texture.isAvailable) openCamera()
        processExecutor.execute {
            val dev = Rolls.tick(this)
            main.post {
                refreshRoll()
                if (dev.isNotEmpty()) toast(if (dev.size == 1) "Your roll is developed" else "${dev.size} rolls developed")
                if (!secureMode) loadLastThumb()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        prefs.edit().putLong("pausedAt", System.currentTimeMillis()).apply()
        main.removeCallbacks(lightTick)
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
            camConfig(),
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
        if (double) { shootDouble(); return }
        if (!cameraReady || busy || pending.get() >= MAX_PENDING) return
        busy = true
        texture.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val roll = Rolls.active(this)
        val n = if (roll != null) Rolls.reserve(this, if (burst) BURST_COUNT else 1) else if (burst) BURST_COUNT else 1
        if (roll != null && n == 0) { busy = false; rollMenu(); return }
        val rollId = roll?.id
        val rollStart = (roll?.used ?: 0) + 1
        val rollSize = roll?.size ?: 0
        refreshRoll()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val params = LookSettings.params(this, if (pmMode) Look.Mode.PM else Look.Mode.AM, drag && !pmMode && !engine.isFront)
        val stripFrames: MutableList<Bitmap> = Collections.synchronizedList(ArrayList())
        captureNext(0, n, stamp, params, stripFrames, rollId, rollStart, rollSize)
    }

    private fun captureNext(i: Int, n: Int, stamp: String, params: Look.Params, stripFrames: MutableList<Bitmap>, rollId: String?, rollStart: Int = 0, rollSize: Int = 0) {
        if (n > 1) {
            counterText.visibility = View.VISIBLE
            counterText.text = "${i + 1}/$n"
        }
        val started = SystemClock.elapsedRealtime()
        val kind = flashKind()
        val rotation = jpegRotation()
        val mirror = engine.isFront

        val useDrag = realDrag && kind == CameraEngine.Flash.PULSE
        val take = {
            engine.captureFrames(useDrag, kind) { bytes, amb ->
                val frame = bytes?.let { b ->
                    val f = decode(b, rotation, mirror)
                    val a = amb?.let { decode(it, rotation, mirror) }
                    if (f != null && a != null) look.dragComposite(f, a).also { f.recycle(); a.recycle() } else f
                }
                val meta = buildMeta(kind, i, n, rollId, rollStart, rollSize)
                main.post {
                    if (kind == CameraEngine.Flash.SCREEN) hideScreenFlash()
                    if (frame == null) {
                        toast("Capture failed")
                        endShoot(stamp, n, stripFrames, rollId)
                        return@post
                    }
                    pending.incrementAndGet()
                    processExecutor.execute {
                        try { processAndSave(frame, stamp, i, n, params, stripFrames, rollId, meta) } finally { pending.decrementAndGet() }
                    }
                    if (i + 1 < n) {
                        val wait = max(150L, BURST_INTERVAL_MS - (SystemClock.elapsedRealtime() - started))
                        main.postDelayed({ captureNext(i + 1, n, stamp, params, stripFrames, rollId, rollStart, rollSize) }, wait)
                    } else {
                        endShoot(stamp, n, stripFrames, rollId)
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

    // ---------- double exposure ----------

    /**
     * Two taps, one frame. Exposure 1 is held (with a ghost on the viewfinder); exposure 2 lands on top.
     * The two are added as light, each pulled down ~0.7 EV like a film shooter would, then developed once.
     */
    private fun shootDouble() {
        if (!cameraReady || busy || pending.get() >= MAX_PENDING) return
        val roll = Rolls.active(this)
        if (roll != null && roll.left <= 0) { rollMenu(); return }
        busy = true
        texture.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val kind = flashKind()
        val rotation = jpegRotation()
        val mirror = engine.isFront
        val useDrag = realDrag && kind == CameraEngine.Flash.PULSE
        val take = {
            engine.captureFrames(useDrag, kind) { bytes, amb ->
                val frame = bytes?.let { b ->
                    val f = decode(b, rotation, mirror)
                    val a = amb?.let { decode(it, rotation, mirror) }
                    if (f != null && a != null) look.dragComposite(f, a).also { f.recycle(); a.recycle() } else f
                }
                val first = firstExposure == null
                val meta = if (first) buildMeta(kind, 0, 1, null, 0, 0) else null
                main.post {
                    if (kind == CameraEngine.Flash.SCREEN) hideScreenFlash()
                    busy = false
                    if (frame == null) { toast("Capture failed"); return@post }
                    if (first) {
                        firstExposure = frame
                        firstMeta = meta
                        ghost.setImageBitmap(frame)
                        ghost.visibility = View.VISIBLE
                        counterText.visibility = View.VISIBLE
                        counterText.text = "1/2 · TAP AGAIN  ✕"
                    } else {
                        val a = firstExposure!!
                        val m = firstMeta
                        firstExposure = null; firstMeta = null
                        ghost.setImageDrawable(null); ghost.visibility = View.GONE
                        counterText.visibility = View.GONE
                        finishDouble(a, frame, m)
                    }
                }
            }
        }
        if (kind == CameraEngine.Flash.SCREEN) { showScreenFlash(); main.postDelayed({ take() }, SCREEN_FLASH_MS) } else take()
    }

    private fun finishDouble(a: Bitmap, b: Bitmap, meta: org.json.JSONObject?) {
        val roll = Rolls.active(this)
        val rollId = roll?.id
        val rollStart = (roll?.used ?: 0) + 1
        if (roll != null && Rolls.reserve(this, 1) == 0) { a.recycle(); b.recycle(); rollMenu(); return }
        refreshRoll()
        meta?.put("double", true)
        if (rollId != null) { meta?.put("rollFrame", rollStart); meta?.put("rollSize", roll.size) }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val params = LookSettings.params(this, if (pmMode) Look.Mode.PM else Look.Mode.AM, drag && !pmMode && !engine.isFront)
        pending.incrementAndGet()
        processExecutor.execute {
            try {
                val merged = look.doubleExposure(a, b)
                a.recycle(); b.recycle()
                processAndSave(merged, stamp, 0, 1, params, Collections.synchronizedList(ArrayList()), rollId, meta)
            } catch (t: Throwable) {
                main.post { toast("Double exposure failed: ${t.message}") }
            } finally { pending.decrementAndGet() }
            main.post {
                val r = Rolls.active(this)
                if (rollId != null && r != null && r.id == rollId && r.left <= 0) { Rolls.sendToLab(this); refreshRoll(); rollFinishedDialog() }
            }
        }
    }

    private fun cancelDouble() {
        firstExposure?.recycle()
        firstExposure = null; firstMeta = null
        if (::ghost.isInitialized) { ghost.setImageDrawable(null); ghost.visibility = View.GONE }
        counterText.visibility = View.GONE
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

    private fun endShoot(stamp: String, n: Int, stripFrames: MutableList<Bitmap>, rollId: String?) {
        counterText.visibility = View.GONE
        busy = false
        if (rollId != null) {
            val a = Rolls.active(this)
            if (a != null && a.id == rollId && a.left <= 0) {
                // wait for this shot to finish saving into the roll, then send it off
                processExecutor.execute { main.post { Rolls.sendToLab(this); refreshRoll(); rollFinishedDialog() } }
            }
        }
        if (n <= 1) return
        processExecutor.execute {
            if (stripFrames.size >= 2) {
                try {
                    val frames = synchronized(stripFrames) { stripFrames.toList() }
                    val date = SimpleDateFormat("dd.MM.yy", Locale.US).format(Date())
                    val strip = Strip.make(frames, "OVERNIGHT  $date  ×${frames.size}")
                    if (rollId != null) Rolls.saveFrame(this, rollId, strip, "FLASH_${stamp}_strip.jpg")
                    else saveJpeg(strip, "FLASH_${stamp}_strip.jpg")
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
        params: Look.Params, stripFrames: MutableList<Bitmap>, rollId: String?, meta: org.json.JSONObject? = null,
    ) {
        try {
            if (debug) {
                val tag = if (params.mode == Look.Mode.PM) "PM" else "AM"
                saveJpeg(frame, "ORIG_${stamp}_${i + 1}_$tag.jpg", "FlashCam/Originals", 97)
            }
            val out = look.process(frame, params, System.nanoTime())
            frame.recycle()
            val name = if (n > 1) "FLASH_${stamp}_${i + 1}.jpg" else "FLASH_$stamp.jpg"
            if (rollId != null) Rolls.saveFrame(this, rollId, out, name, meta) else saveJpeg(out, name, meta = meta)
            if (n > 1) {
                stripFrames.add(Bitmap.createScaledBitmap(out, 720, max(1, 720 * out.height / out.width), true))
            }
            val th = Bitmap.createScaledBitmap(out, 160, max(1, 160 * out.height / out.width), true)
            out.recycle()
            // roll shots stay hidden until developed
            if (rollId == null) main.post { thumb.setImageBitmap(th) }  // shown even when locked: only what you just took
        } catch (t: Throwable) {
            main.post { toast("Processing failed: ${t.message}") }
        }
    }

    // ---------- saving ----------

    private fun saveJpeg(bmp: Bitmap, name: String, folder: String = "FlashCam", quality: Int = 92, meta: org.json.JSONObject? = null): Uri? {
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
        val bytes = PhotoMeta.encode(this, bmp, quality, meta)
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
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
        modeBtn.text = if (pmMode) "DAY" else "NIGHT"
        val modeColor = if (pmMode) WHITE else ORANGE
        modeBtn.setTextColor(modeColor)
        modeBtn.compoundDrawableTintList = ColorStateList.valueOf(modeColor)

        // small "A" when NIGHT/DAY is switching on its own; hold the button to return to auto
        if (!modeLocked) {
            val t = android.text.SpannableString(modeBtn.text.toString() + " A")
            t.setSpan(android.text.style.RelativeSizeSpan(0.55f), t.length - 1, t.length, 0)
            t.setSpan(android.text.style.SuperscriptSpan(), t.length - 1, t.length, 0)
            modeBtn.text = t
        }

        val fixedFocus = !pmMode && !front
        focusBtn.text = when { autoFocus -> "AUTO ˅"; far -> "FAR ˅"; else -> "CLOSE ˅" }
        focusBtn.visibility = View.INVISIBLE  // manual CLOSE/FAR removed; AUTO only
        focusBtn.isEnabled = false
        focusBtn.compoundDrawableTintList = ColorStateList.valueOf(ORANGE)
        focusReadout.text = focusText()
        focusReadout.compoundDrawableTintList = ColorStateList.valueOf(ORANGE)

        flashBtn.visibility = View.GONE
        flashBtn.text = if (torchFlash) "TORCH" else "PULSE"
        flashBtn.setTextColor(if (torchFlash) grey else ORANGE)
        burstBtn.text = when { double -> "DOUBLE"; burst -> "BURST"; else -> "SINGLE" }
        burstBtn.setTextColor(if (burst || double) ORANGE else grey)
        blurBtn.text = if (realDrag) "DRAG" else "BLUR"
        blurBtn.setTextColor(if (drag || realDrag) ORANGE else grey)
        blurBtn.visibility = if (pmMode || front) View.GONE else View.VISIBLE
        val d = resources.displayMetrics.density
        fun pill(on: Boolean) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 18 * d
            setStroke((1 * d).toInt().coerceAtLeast(1), if (on) ORANGE else 0x40FFFFFF)
            setColor(if (on) 0x14FF7A1F else 0x00000000)
        }
        flashBtn.background = pill(!torchFlash)
        burstBtn.background = pill(burst || double)
        blurBtn.background = pill(drag || realDrag)
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
        evTitle.text = if (pmMode) "DAY EXP" else "NIGHT EXP"
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
            .putBoolean("autoFocus", autoFocus)
            .putBoolean("burst", burst)
            .putBoolean("double", double)
            .putBoolean("drag", drag)
            .putBoolean("front", front)
            .putBoolean("debug", debug)
            .putFloat("evAm", evAm)
            .putFloat("evPm2", evPm)
            .apply()
    }

    /** Settings captured with each frame; shown in the viewer's INFO panel. */
    private fun buildMeta(kind: CameraEngine.Flash, i: Int, n: Int, rollId: String?, rollStart: Int, rollSize: Int): org.json.JSONObject {
        val m = org.json.JSONObject()
        m.put("v", 1)
        m.put("time", System.currentTimeMillis())
        m.put("mode", if (pmMode) "DAY" else "NIGHT")
        m.put("focus", when {
            engine.isFront -> "SELFIE"
            pmMode -> "AUTO"
            autoFocus -> engine.subjectDistanceM()?.let { "AUTO · %.1f FT".format(it * 3.281f) } ?: "AUTO · FIXED 4 FT (DARK)"
            far -> "FAR · 6 FT"
            else -> "CLOSE · 3 FT"
        })
        m.put("flash", kind.name.let { if (it == "NONE") "OFF" else it })
        m.put("effect", when { realDrag && kind == CameraEngine.Flash.PULSE -> "DRAG"; drag && !pmMode && !engine.isFront -> "BLUR"; else -> "NONE" })
        m.put("burstOf", n); m.put("burstIdx", i + 1)
        m.put("ev", currentEv().toDouble())
        m.put("expNs", engine.lastExpNs); m.put("iso", engine.lastIso)
        m.put("fNumber", engine.fNumber.toDouble()); m.put("focalMm", engine.focalMm.toDouble()); m.put("focal35", engine.focal35)
        m.put("camera", if (engine.isFront) "FRONT" else "BACK")
        m.put("phone", Build.MODEL)
        if (rollId != null) { m.put("rollFrame", rollStart + i); m.put("rollSize", rollSize) }
        return m
    }

    private fun camConfig() = CameraEngine.Config(
        front = front, pm = pmMode, far = far, ev = currentEv(), drag = realDrag, autoFocus = autoFocus,
    )

    private fun focusText(): String = when {
        front -> "SELFIE"
        pmMode -> "AF"
        autoFocus -> engine.subjectDistanceM()?.let { d ->
            val ft = d * 3.281f
            if (ft >= 10f) "10ft+" else "${Math.round(ft).coerceAtLeast(1)}ft"
        } ?: "~4ft"
        far -> "6ft"
        else -> "3ft"
    }

    /**
     * Every half second: refresh the live distance readout, and (unless locked by hand) switch
     * NIGHT/DAY by how bright the scene actually is. Hysteresis + a few agreeing readings so it
     * doesn't flip back and forth. Clock is only the starting guess.
     */
    private val lightTick = object : Runnable {
        override fun run() {
            if (cameraReady) {
                if (autoFocus && !pmMode && !front) focusReadout.text = focusText()
                val ev = engine.sceneEv()
                if (!modeLocked && !busy && ev != null) {
                    val wantNight = ev < NIGHT_EV
                    val wantDay = ev > DAY_EV
                    darkVotes = when {
                        wantNight && pmMode -> darkVotes + 1
                        wantDay && !pmMode -> darkVotes + 1
                        else -> 0
                    }
                    if (darkVotes >= 4) {
                        darkVotes = 0
                        pmMode = !pmMode
                        refreshUi()
                        engine.reconfigure(camConfig())
                    }
                }
            }
            main.postDelayed(this, 500)
        }
    }

    private fun refreshRoll() {
        val a = Rolls.active(this)
        statusText.text = (if (a == null) "LOAD ROLL" else "%02d LEFT".format(a.left)) + if (debug) " · DBG" else ""
        statusText.setTextColor(if (a == null) 0x80FFFFFF.toInt() else ORANGE)
    }

    // ---------- rolls ----------

    private fun rollMenu() {
        val a = Rolls.active(this)
        if (a == null) {
            FlashDialog.loadRoll(this) { loadRoll(it) }
        } else {
            FlashDialog.show(this, "Roll · ${a.used}/${a.size}",
                "Photos stay hidden until developed. Send to the lab to finish early and develop at 8:00 AM.",
                listOf(
                    FlashDialog.Option("Send to lab", FlashDialog.Style.PRIMARY) { confirmSendToLab() },
                    FlashDialog.Option("Keep shooting") {},
                    FlashDialog.Option("Discard roll", FlashDialog.Style.DANGER) { confirmDiscard() },
                ), cancel = null)
        }
    }

    private fun loadRoll(size: Int) {
        Rolls.load(this, size)
        refreshRoll()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun confirmSendToLab() {
        FlashDialog.show(this, "Send to lab?", "The roll is finished and develops at 8:00 AM. No more photos can be added to it.",
            listOf(FlashDialog.Option("Send to lab", FlashDialog.Style.PRIMARY) {
                Rolls.sendToLab(this); refreshRoll(); toast("Developing · ready 8:00 AM")
            }))
    }

    private fun confirmDiscard() {
        FlashDialog.show(this, "Discard this roll?", "Every photo on it will be deleted. This can't be undone.",
            listOf(FlashDialog.Option("Discard roll", FlashDialog.Style.DANGER) { Rolls.discardActive(this); refreshRoll() }))
    }

    private fun rollFinishedDialog() {
        FlashDialog.loadRoll(this, "Roll finished", "Developing · ready 8:00 AM. Load another roll?") { loadRoll(it) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
