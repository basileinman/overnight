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
        private const val ORANGE = Ui.ORANGE
        private const val WHITE = Ui.SOFT
        private const val HINT_SHOTS = 3   // the TAP ANYWHERE hint disappears after this many shots
        private const val BURST_COUNT = 5
        private const val BURST_INTERVAL_MS = 1000L
        private const val MAX_RAW_EDGE = 2600
        private const val MAX_PENDING = 3
        private const val AUTO_MODE_AFTER_MS = 30 * 60 * 1000L
        private const val NIGHT_EV = 6.0   // dimmer than a lamp-lit room -> NIGHT
        private const val DAY_EV = 8.0     // window-lit room or brighter -> DAY
    }

    private lateinit var root: View
    private lateinit var previewFrame: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var shutterBlink: View
    private lateinit var hint: TextView
    private lateinit var toggleLabel: TextView
    private lateinit var counterText: TextView
    private lateinit var statusText: TextView
    private lateinit var modeBtn: TextView
    private lateinit var burstBtn: ImageView
    private lateinit var blurBtn: ImageView
    private lateinit var burstIcon: Ui.IconDrawable
    private lateinit var blurIcon: Ui.IconDrawable
    private lateinit var flipBtn: TextView   // now the ROLL button
    private lateinit var thumb: ImageView
    private lateinit var thumbBadge: TextView
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
    private var far = false          // locked FAR 6 ft (settings)
    private var autoFocus = true     // AUTO: focus + flash follow subject distance
    private var autoMode = true      // NIGHT/DAY follows the light (settings)
    private var secureMode = false   // opened from the lock screen
    private var modeLocked = false   // user picked NIGHT/DAY by hand; auto switching paused
    private var darkVotes = 0
    private var burst = false
    private var double = false        // DOUBLE exposure mode (two taps, one frame)
    private var firstExposure: Bitmap? = null
    private var firstMeta: org.json.JSONObject? = null
    private lateinit var ghost: ImageView
    private var drag = false       // fake BLUR effect
    private var realDrag = false   // slow shutter + flash
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
        shutterBlink = findViewById(R.id.shutterBlink)
        hint = findViewById(R.id.hint)
        toggleLabel = findViewById(R.id.toggleLabel)
        counterText = findViewById(R.id.counter)
        statusText = findViewById(R.id.status)
        modeBtn = findViewById(R.id.modeBtn)
        burstBtn = findViewById(R.id.burstBtn)
        blurBtn = findViewById(R.id.blurBtn)
        flipBtn = findViewById(R.id.flipBtn)
        thumb = findViewById(R.id.thumb)
        thumbBadge = findViewById(R.id.thumbBadge)
        thumbBadge.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(ORANGE); cornerRadius = Ui.dp(this@MainActivity, 9f)
        }
        ghost = findViewById(R.id.ghost)
        focusReadout = findViewById(R.id.focusReadout)
        evRuler = findViewById(R.id.evRuler)
        evRuler.range = 3f
        evBox = findViewById(R.id.evBox)
        evTitle = findViewById(R.id.evTitle)
        evValue = findViewById(R.id.evValue)
        thumb.clipToOutline = true
        burstIcon = Ui.IconDrawable(this, Ui.Icon.SINGLE, Ui.OFF).also { burstBtn.setImageDrawable(it) }
        blurIcon = Ui.IconDrawable(this, Ui.Icon.NO_EFFECT, Ui.OFF).also { blurBtn.setImageDrawable(it) }
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

        loadSettings()
        // NIGHT/DAY follows the clock on launch (the light meter takes over), unless set to manual
        pmMode = if (autoMode) SunClock.isDaytime() else prefs.getBoolean("pm", false)
        burst = prefs.getBoolean("burst", false)
        double = prefs.getBoolean("double", false) && !burst
        drag = false  // BLUR always starts off
        evAm = prefs.getFloat("evAm", 0f)
        evPm = prefs.getFloat("evPm2", 0.1f)
        refreshUi()

        setupViewfinderTouch()
        modeBtn.setOnClickListener { if (!busy) { pmMode = !pmMode; modeLocked = true; save(); refreshUi(); engine.reconfigure(camConfig()) } }
        modeBtn.setOnLongClickListener {
            if (!autoMode) { toast("Auto NIGHT / DAY is off in settings"); return@setOnLongClickListener true }
            modeLocked = false; refreshUi(); flashLabel("NIGHT / DAY · AUTO"); true
        }
        burstBtn.setOnClickListener {
            // SINGLE -> BURST -> DOUBLE -> SINGLE
            when {
                double -> { double = false; cancelDouble() }
                burst -> { burst = false; double = true }
                else -> burst = true
            }
            save(); refreshUi()
            flashLabel(when { double -> "DOUBLE · TWO TAPS, ONE FRAME"; burst -> "BURST · 5 FRAMES"; else -> "SINGLE" })
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
            flashLabel(when { drag -> "BLUR"; realDrag -> "DRAG · SLOW SHUTTER"; else -> "NO EFFECT" })
        }
        flipBtn.setOnClickListener { if (!busy) rollMenu() }
        thumb.setOnLongClickListener {
            if (debug) startActivity(Intent(this, LookLabActivity::class.java))
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
        // one-time move of old Pictures/FlashCam photos into Pictures/Overnight
        processExecutor.execute { try { Paths.migrate(this) } catch (_: Throwable) {} }
    }

    /** Settings live on the roll page; re-read them whenever the camera comes back. */
    private fun loadSettings() {
        val f = Settings.focus(this)
        autoFocus = f == Settings.Focus.AUTO
        far = f == Settings.Focus.FAR
        val wasAuto = autoMode
        autoMode = Settings.autoMode(this)
        if (!autoMode) modeLocked = true else if (!wasAuto) modeLocked = false
        debug = Settings.debug(this)
    }

    /** Brief label over the viewfinder when a toggle changes. */
    private val hideLabel = Runnable {
        toggleLabel.animate().alpha(0f).setDuration(250).withEndAction { toggleLabel.visibility = View.GONE }.start()
    }

    private fun flashLabel(text: String) {
        main.removeCallbacks(hideLabel)
        hint.visibility = View.GONE
        toggleLabel.animate().cancel()
        toggleLabel.text = text
        toggleLabel.visibility = View.VISIBLE
        toggleLabel.alpha = 1f
        main.postDelayed(hideLabel, 1100)
    }

    /** Very soft white blink on the viewfinder as the shot is taken (no sound). */
    private fun blink() {
        shutterBlink.animate().cancel()
        shutterBlink.visibility = View.VISIBLE
        shutterBlink.alpha = 0.22f
        shutterBlink.animate().alpha(0f).setDuration(160).withEndAction { shutterBlink.visibility = View.GONE }.start()
    }

    private fun countShot() {
        val n = prefs.getInt("shotsTaken", 0) + 1
        prefs.edit().putInt("shotsTaken", n).apply()
        if (n >= HINT_SHOTS) hint.animate().alpha(0f).setDuration(400).withEndAction { hint.visibility = View.GONE }.start()
    }

    // ---------- first launch intro ----------

    private var introView: View? = null

    private fun maybeShowIntro() {
        if (Settings.introSeen(this) || introView != null || secureMode) return
        val pages = listOf(
            "OVERNIGHT" to "A flash camera with a film look. Tap anywhere on the viewfinder to shoot. The flash fires the instant you tap.",
            "EXPOSURE" to "Hold the viewfinder, then slide up to brighten or down to darken.",
            "NIGHT / DAY" to "NIGHT uses the flash, DAY doesn't. It switches by itself with the light. Tap it to choose by hand.",
            "ROLLS" to "Load a roll and your shots stay hidden until it's finished. It develops at 8 AM. Settings are behind the gear on the roll page.",
        )
        var page = 0
        val dp = { v: Int -> Ui.dpi(this, v) }
        val title = Ui.text(this, "", Ui.TITLE, ORANGE)
        val body = Ui.body(this, "", 14f, Ui.SOFT).apply { setPadding(0, dp(12), 0, 0) }
        val dots = Ui.text(this, "", Ui.SMALL, Ui.DIM).apply { setPadding(0, dp(28), 0, 0) }
        val next = Ui.text(this, "", Ui.BUTTON, ORANGE).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            background = Ui.button(this@MainActivity, true)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(34), 0, dp(34), dp(48))
            gravity = android.view.Gravity.BOTTOM
            setBackgroundColor(0xF2000000.toInt())
            isClickable = true
            addView(title); addView(body); addView(dots)
            addView(next, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        }
        fun render() {
            title.text = pages[page].first
            body.text = pages[page].second
            dots.text = pages.indices.joinToString("  ") { if (it == page) "●" else "○" }
            next.text = if (page == pages.size - 1) "START SHOOTING" else "NEXT"
        }
        val advance = {
            if (page < pages.size - 1) { page++; render() }
            else {
                Settings.setIntroSeen(this, true)
                col.animate().alpha(0f).setDuration(200).withEndAction { (root as FrameLayout).removeView(col); introView = null }.start()
            }
        }
        next.setOnClickListener { advance() }
        col.setOnClickListener { advance() }
        render()
        (root as FrameLayout).addView(col, FrameLayout.LayoutParams(-1, -1))
        introView = col
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
        val wasAuto = autoFocus; val wasFar = far
        loadSettings()
        if (cameraReady && (wasAuto != autoFocus || wasFar != far)) engine.reconfigure(camConfig())
        hint.visibility = if (prefs.getInt("shotsTaken", 0) < HINT_SHOTS) View.VISIBLE else View.GONE
        hint.alpha = 1f
        refreshUi()
        maybeShowIntro()
        // Back after a while (or first launch): re-pick NIGHT/DAY from the clock.
        // A manual switch sticks while you keep using the app.
        val away = System.currentTimeMillis() - prefs.getLong("pausedAt", 0L)
        if (away > AUTO_MODE_AFTER_MS && autoMode) {
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
            onReady = { cameraReady = true },
            onError = { msg -> toast(msg) },
        )
    }

    private fun reopen() {
        engine.close()
        openCamera()
    }

    private fun flashKind(): CameraEngine.Flash = when {
        pmMode || !engine.hasFlash -> CameraEngine.Flash.NONE
        else -> CameraEngine.Flash.PULSE
    }

    // ---------- shooting ----------

    private fun shoot() {
        if (double) { shootDouble(); return }
        if (!cameraReady || busy || pending.get() >= MAX_PENDING) return
        busy = true
        texture.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        countShot()
        val roll = Rolls.active(this)
        val n = if (roll != null) Rolls.reserve(this, if (burst) BURST_COUNT else 1) else if (burst) BURST_COUNT else 1
        if (roll != null && n == 0) { busy = false; rollMenu(); return }
        val rollId = roll?.id
        val rollStart = (roll?.used ?: 0) + 1
        val rollSize = roll?.size ?: 0
        refreshRoll()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val params = LookSettings.params(this, if (pmMode) Look.Mode.PM else Look.Mode.AM, drag && !pmMode)
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

        val useDrag = realDrag && kind == CameraEngine.Flash.PULSE
        blink()
        run {
            engine.captureFrames(useDrag, kind) { bytes, amb ->
                val frame = bytes?.let { b ->
                    val f = decode(b, rotation)
                    val a = amb?.let { decode(it, rotation) }
                    if (f != null && a != null) look.dragComposite(f, a).also { f.recycle(); a.recycle() } else f
                }
                val meta = buildMeta(kind, i, n, rollId, rollStart, rollSize)
                main.post {
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
        if (firstExposure == null) countShot()
        val kind = flashKind()
        val rotation = jpegRotation()
        val useDrag = realDrag && kind == CameraEngine.Flash.PULSE
        blink()
        run {
            engine.captureFrames(useDrag, kind) { bytes, amb ->
                val frame = bytes?.let { b ->
                    val f = decode(b, rotation)
                    val a = amb?.let { decode(it, rotation) }
                    if (f != null && a != null) look.dragComposite(f, a).also { f.recycle(); a.recycle() } else f
                }
                val first = firstExposure == null
                val meta = if (first) buildMeta(kind, 0, 1, null, 0, 0) else null
                main.post {
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
        val params = LookSettings.params(this, if (pmMode) Look.Mode.PM else Look.Mode.AM, drag && !pmMode)
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
        return (engine.sensorOrientation + deviceOrientation) % 360
    }

    /** Decode the JPEG at a manageable size and turn it upright. */
    private fun decode(bytes: ByteArray, rotation: Int): Bitmap? = try {
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
                saveJpeg(frame, "ORIG_${stamp}_${i + 1}_$tag.jpg", Paths.ORIGINALS, 97)
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
            if (rollId == null) main.post { thumb.setImageBitmap(th); thumbBadge.visibility = View.GONE }  // shown even when locked: only what you just took
            else {
                // roll shots stay hidden: show the frame as an undeveloped, blurred negative instead
                val neg = latentImage(th)
                try { File(filesDir, "latent_$rollId.jpg").outputStream().use { neg.compress(Bitmap.CompressFormat.JPEG, 85, it) } } catch (_: Throwable) {}
                main.post { showLatent(neg, rollId) }
            }
        } catch (t: Throwable) {
            main.post { toast("Processing failed: ${t.message}") }
        }
    }

    // ---------- saving ----------

    private fun saveJpeg(bmp: Bitmap, name: String, folder: String = Paths.ROLL, quality: Int = 92, meta: org.json.JSONObject? = null): Uri? {
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

    /**
     * Thumbnail logic: while a roll is shooting or at the lab, show its latest frame as a latent image
     * (blurred, inverted, orange-cast like a color negative) with a frame count. Otherwise the newest photo.
     */
    private fun loadLastThumb() {
        processExecutor.execute {
            val a = Rolls.active(this)
            val roll: Pair<String, Int>? = when {
                a != null && a.used > 0 -> a.id to a.used
                else -> Rolls.lab(this).maxByOrNull { it.ready }?.let { it.id to it.used }
            }
            val latent = roll?.let { (id, _) ->
                File(filesDir, "latent_$id.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
            if (roll != null && latent != null) { main.post { showLatent(latent, roll.first, roll.second) }; return@execute }
            // clean up negatives of rolls that have developed
            filesDir.listFiles { f -> f.name.startsWith("latent_") && f.name != "latent_${roll?.first}.jpg" }?.forEach { it.delete() }
            val last = Roll.load(this).firstOrNull()
            val bmp = last?.let { Roll.thumbnail(this, it, 160) }
            main.post { if (bmp != null) thumb.setImageBitmap(bmp); thumbBadge.visibility = View.GONE }
        }
    }

    private fun showLatent(neg: Bitmap, rollId: String, count: Int? = null) {
        if (secureMode) return
        val n = count ?: Rolls.active(this)?.takeIf { it.id == rollId }?.used ?: return
        thumb.setImageBitmap(neg)
        thumb.alpha = 0.4f
        thumb.animate().alpha(1f).setDuration(350).start()
        thumbBadge.text = n.toString()
        thumbBadge.visibility = View.VISIBLE
    }

    /**
     * Tiny frame -> heavy blur -> color negative (mostly a plain inversion, just a hint of film-base warmth).
     * Made square with rounded corners baked in, and blurred past the edges, so no hard edges show in the thumbnail.
     */
    private fun latentImage(src: Bitmap): Bitmap {
        val side = min(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val tiny = Bitmap.createScaledBitmap(sq, 8, 8, true)
        if (sq !== src) sq.recycle()
        val size = 160
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            -0.92f, 0f, 0f, 0f, 240f,
            0f, -0.92f, 0f, 0f, 232f,
            0f, 0f, -0.92f, 0f, 222f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        }
        // match the thumbnail frame's rounded corners
        val r = size * 16.5f / 53f
        c.clipPath(android.graphics.Path().apply { addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, android.graphics.Path.Direction.CW) })
        // two upscale passes for a smooth blur; drawn larger than the frame so the soft edges fall outside it
        val mid = Bitmap.createScaledBitmap(tiny, 32, 32, true)
        tiny.recycle()
        val over = size * 0.18f
        c.drawBitmap(mid, null, android.graphics.RectF(-over, -over, size + over, size + over), p)
        mid.recycle()
        return out
    }

    // ---------- ui state ----------

    private fun refreshUi() {
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

        focusReadout.text = focusText()
        val lockedFocus = !pmMode && !autoFocus
        focusReadout.setTextColor(if (pmMode) Ui.DIM else ORANGE)
        focusReadout.compoundDrawableTintList = ColorStateList.valueOf(if (pmMode) Ui.DIM else ORANGE)
        focusReadout.alpha = if (lockedFocus) 0.85f else 1f

        burstIcon.icon = when { double -> Ui.Icon.DOUBLE; burst -> Ui.Icon.BURST; else -> Ui.Icon.SINGLE }
        burstIcon.color = if (burst || double) ORANGE else Ui.OFF
        burstBtn.invalidate(); burstIcon.invalidateSelf()
        blurIcon.icon = when { realDrag -> Ui.Icon.DRAG; drag -> Ui.Icon.BLUR; else -> Ui.Icon.NO_EFFECT }
        blurIcon.color = if (drag || realDrag) ORANGE else Ui.OFF
        blurBtn.invalidate(); blurIcon.invalidateSelf()
        blurBtn.visibility = if (pmMode) View.INVISIBLE else View.VISIBLE
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
                        // full viewfinder height = 6 EV (-3..+3); slide up to brighten
                        val delta = (downY - ev.y) / v.height * 6f
                        val e = (Math.round((startEv + delta) * 10f) / 10f).coerceIn(-3f, 3f)
                        if (e != currentEv()) {
                            if (pmMode) evPm = e else evAm = e
                            Haptics.tick(v)
                            showEv(e)
                            engine.setEv(e)
                        }
                    } else {
                        val dx = Math.abs(ev.x - downX); val dy = Math.abs(ev.y - downY)
                        if (dy > slop && dy > dx) {
                            // vertical slide: start adjusting right away (no need to wait for the hold)
                            main.removeCallbacks(beginAdjust)
                            beginAdjust.run()
                            downY = ev.y
                        } else if (dx > slop * 3) {
                            // clearly not a tap or an exposure drag
                            moved = true
                            main.removeCallbacks(beginAdjust)
                        }
                        // small wobble while holding still is ignored, so hold-then-slide works
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(beginAdjust)
                    if (!adjusting && Math.abs(ev.y - downY) > slop * 3) moved = true
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
            .putBoolean("burst", burst)
            .putBoolean("double", double)
            .putBoolean("drag", drag)
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
            pmMode -> "AUTO"
            autoFocus -> engine.subjectDistanceM()?.let { "AUTO · %.1f FT".format(it * 3.281f) } ?: "AUTO · FIXED 4 FT (DARK)"
            far -> "FAR · 6 FT"
            else -> "CLOSE · 3 FT"
        })
        m.put("flash", kind.name.let { if (it == "NONE") "OFF" else it })
        m.put("effect", when { realDrag && kind == CameraEngine.Flash.PULSE -> "DRAG"; drag && !pmMode -> "BLUR"; else -> "NONE" })
        m.put("burstOf", n); m.put("burstIdx", i + 1)
        m.put("ev", currentEv().toDouble())
        m.put("expNs", engine.lastExpNs); m.put("iso", engine.lastIso)
        m.put("fNumber", engine.fNumber.toDouble()); m.put("focalMm", engine.focalMm.toDouble()); m.put("focal35", engine.focal35)
        m.put("camera", "BACK")
        m.put("phone", Build.MODEL)
        if (rollId != null) { m.put("rollFrame", rollStart + i); m.put("rollSize", rollSize) }
        return m
    }

    private fun camConfig() = CameraEngine.Config(
        pm = pmMode, far = far, ev = currentEv(), drag = realDrag, autoFocus = autoFocus,
    )

    private fun focusText(): String = when {
        pmMode -> "AF"
        autoFocus -> engine.subjectDistanceM()?.let { d ->
            val ft = d * 3.281f
            if (ft >= 10f) "10ft+" else "${Math.round(ft).coerceAtLeast(1)}ft"
        } ?: "~4ft"
        far -> "6ft · LOCKED"
        else -> "3ft · LOCKED"
    }

    /**
     * Every half second: refresh the live distance readout, and (unless locked by hand) switch
     * NIGHT/DAY by how bright the scene actually is. Hysteresis + a few agreeing readings so it
     * doesn't flip back and forth. Clock is only the starting guess.
     */
    private val lightTick = object : Runnable {
        override fun run() {
            if (cameraReady) {
                if (autoFocus && !pmMode) focusReadout.text = focusText()
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
        statusText.text = if (debug) "DBG" else ""
        flipBtn.text = if (a == null) "LOAD\nROLL" else "%02d\nLEFT".format(a.left)
        flipBtn.setTextColor(if (a == null) 0xB3FFFFFF.toInt() else ORANGE)
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
                Rolls.sendToLab(this); refreshRoll(); loadLastThumb(); toast("Developing · ready 8:00 AM")
            }))
    }

    private fun confirmDiscard() {
        FlashDialog.show(this, "Discard this roll?", "Every photo on it will be deleted. This can't be undone.",
            listOf(FlashDialog.Option("Discard roll", FlashDialog.Style.DANGER) { Rolls.discardActive(this); refreshRoll(); loadLastThumb() }))
    }

    private fun rollFinishedDialog() {
        loadLastThumb()
        FlashDialog.loadRoll(this, "Roll finished", "Developing · ready 8:00 AM. Load another roll?") { loadRoll(it) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
