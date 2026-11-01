package app.flashcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Thin Camera2 wrapper built for disposable-camera behaviour:
 *  - reference: fixed focus, fixed exposure, real flash pulse on the capture frame (no pre-flash metering)
 *  - DAY: autofocus, auto exposure, no flash
 */
class CameraEngine(context: Context, private val textureView: TextureView) {

    enum class Flash { PULSE, TORCH, SCREEN, NONE }

    data class Config(val front: Boolean, val pm: Boolean, val far: Boolean, val ev: Float = 0f, val drag: Boolean = false)

    data class Info(val isFront: Boolean, val hasFlash: Boolean, val manualSensor: Boolean)

    companion object {
        // Disposable-camera style fixed exposure for flash pulses
        // Fallback fixed exposure if the viewfinder hasn't metered yet
        const val PULSE_EXPOSURE_NS = 12_500_000L   // 1/80 s
        const val PULSE_ISO = 900
        // Metered pulse caps: in dim rooms these stop the background from brightening,
        // so the flash does the work and the background falls dark (the reference look)
        // v0.10: lowered after close portraits blew out — the flash adds on top of the
        // ambient reading, so dim rooms must not be metered brighter than this
        const val PULSE_MAX_EXPOSURE_NS = 12_500_000L  // 1/80 s
        const val PULSE_MAX_ISO = 800
        const val CLOSE_M = 0.91f                   // 3 ft
        const val FAR_M = 1.83f                     // 6 ft
        const val TORCH_SETTLE_MS = 300L
        // DRAG: slow shutter so lights streak, flash freezes the subject
        const val DRAG_EXPOSURE_NS = 200_000_000L  // 1/5 s
        const val DRAG_MAX_ISO = 400
    }

    private val manager = context.getSystemService(CameraManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var chars: CameraCharacteristics? = null
    private var config: Config? = null
    private var torchOn = false
    @Volatile private var pending: ((ByteArray?) -> Unit)? = null
    // latest auto-exposure reading from the live viewfinder
    @Volatile private var meteredExposure = 0L
    @Volatile private var meteredIso = 0
    private val meter = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            if (e != null && iso != null && e > 0 && iso > 0) {
                meteredExposure = e
                meteredIso = iso
            }
        }
    }

    var sensorOrientation = 90; private set
    var isFront = false; private set
    var hasFlash = false; private set
    var manualSensor = false; private set
    private var minFocus = 0f
    @Volatile var lastExpNs = 0L; private set
    @Volatile var lastIso = 0; private set
    var fNumber = 0f; private set
    var focalMm = 0f; private set
    var focal35 = 0; private set
    private var previewSize = Size(1440, 1080)

    // ---------- lifecycle ----------

    @SuppressLint("MissingPermission")
    fun open(cfg: Config, onReady: (Info) -> Unit, onError: (String) -> Unit) {
        close()
        config = cfg
        val t = HandlerThread("camera").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        val id = pickCamera(cfg.front) ?: run { onError("No camera"); return }
        val c = manager.getCameraCharacteristics(id)
        chars = c
        isFront = c.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
        sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        fNumber = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f
        focalMm = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        focal35 = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { sz ->
            val diag = Math.hypot(sz.width.toDouble(), sz.height.toDouble())
            if (diag > 0 && focalMm > 0) (focalMm * 43.27 / diag).roundToInt() else 0
        } ?: 0
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: run { onError("No stream config"); return }
        val previews = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        previewSize = previews.filter { is43(it) && it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: previews.firstOrNull() ?: Size(1440, 1080)
        val jpegs = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
        val jpeg = jpegs.filter { is43(it) }.minByOrNull { abs(it.width - 3264) }
            ?: jpegs.maxByOrNull { it.width * it.height } ?: Size(1920, 1440)

        val r = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireNextImage() } catch (t: Throwable) { null }
            val bytes = img?.let {
                val buf = it.planes[0].buffer
                ByteArray(buf.remaining()).also { b -> buf.get(b) }
            }
            img?.close()
            if (torchOn) setTorch(false)
            val cb = pending
            pending = null
            cb?.invoke(bytes)
        }, h)
        reader = r

        main.post { applyTransform() }

        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    startSession(onReady, onError)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); device = null }
                override fun onError(cam: CameraDevice, error: Int) {
                    cam.close(); device = null
                    main.post { onError("Camera error $error") }
                }
            }, h)
        } catch (t: Throwable) {
            onError("Can't open camera: ${t.message}")
        }
    }

    fun close() {
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        session = null; device = null; reader = null
        previewSurface?.release(); previewSurface = null
        thread?.quitSafely(); thread = null; handler = null
        pending = null
        torchOn = false
    }

    private fun pickCamera(front: Boolean): String? {
        val want = if (front) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK
        val ids = manager.cameraIdList
        return ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == want }
            ?: ids.firstOrNull()
    }

    private fun is43(s: Size) = abs(s.width.toFloat() / s.height - 4f / 3f) < 0.02f

    @Suppress("DEPRECATION")
    private fun startSession(onReady: (Info) -> Unit, onError: (String) -> Unit) {
        val cam = device ?: return
        val tex = textureView.surfaceTexture ?: run { main.post { onError("Preview not ready") }; return }
        tex.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(tex)
        previewSurface = surface
        val rd = reader ?: return
        try {
            cam.createCaptureSession(listOf(surface, rd.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreview()
                    main.post { onReady(Info(isFront, hasFlash, manualSensor)) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    main.post { onError("Camera session failed") }
                }
            }, handler)
        } catch (t: Throwable) {
            main.post { onError("Camera session error: ${t.message}") }
        }
    }

    // ---------- requests ----------

    private fun applyCommon(b: CaptureRequest.Builder) {
        val cfg = config ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        // film is daylight balanced: flash-lit skin natural, room lights stay warm
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
        if (cfg.pm) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else if (!isFront && minFocus > 0f) {
            val diopters = min(1f / (if (cfg.far) FAR_M else CLOSE_M), minFocus)
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
        }
        val range = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = chars?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
        if (range != null && step > 0f) {
            val ev = cfg.ev
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (ev / step).roundToInt().coerceIn(range.lower, range.upper))
        }
    }

    private fun previewRequest(torch: Boolean): CaptureRequest? {
        val cam = device ?: return null
        val surface = previewSurface ?: return null
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(surface)
        applyCommon(b)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        return b.build()
    }

    /** Change exposure compensation live (DAY preview + pulse ISO for the next shot). */
    fun setDrag(on: Boolean) {
        val cfg = config ?: return
        config = cfg.copy(drag = on)
    }

    fun setEv(ev: Float) {
        val cfg = config ?: return
        config = cfg.copy(ev = ev)
        handler?.post { if (!torchOn) startPreview() }
    }

    private fun startPreview() {
        val s = session ?: return
        val req = previewRequest(false) ?: return
        try { s.setRepeatingRequest(req, meter, handler) } catch (_: Throwable) {}
    }

    private fun setTorch(on: Boolean) {
        val s = session ?: return
        val req = previewRequest(on) ?: return
        torchOn = on
        try { s.setRepeatingRequest(req, if (on) null else meter, handler) } catch (_: Throwable) {}
    }

    // ---------- capture ----------

    /** Captures one JPEG. [onJpeg] runs on the camera thread with the bytes, or null on failure. */
    fun capture(flash: Flash, onJpeg: (ByteArray?) -> Unit) {
        val h = handler ?: run { onJpeg(null); return }
        h.post {
            val s = session
            val cam = device
            val rd = reader
            if (s == null || cam == null || rd == null) { onJpeg(null); return@post }
            pending = onJpeg
            try {
                when (flash) {
                    Flash.PULSE -> s.capture(pulseRequest(cam, rd), null, h)
                    Flash.TORCH -> {
                        setTorch(true)
                        h.postDelayed({
                            try { s.capture(autoRequest(cam, rd, torch = true), null, h) } catch (t: Throwable) { fail() }
                        }, TORCH_SETTLE_MS)
                    }
                    Flash.SCREEN, Flash.NONE -> s.capture(autoRequest(cam, rd, torch = false), null, h)
                }
            } catch (t: Throwable) {
                fail()
            }
        }
    }

    private fun fail() {
        if (torchOn) setTorch(false)
        val cb = pending
        pending = null
        cb?.invoke(null)
    }

    /** Fixed exposure + single flash fire on this frame. No AE precapture, so it's instant. */
    private fun pulseRequest(cam: CameraDevice, rd: ImageReader): CaptureRequest {
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(rd.surface)
        previewSurface?.let { b.addTarget(it) }
        applyCommon(b)
        val expRange = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val minIso = isoRange?.lower ?: 50
        val mExp = meteredExposure
        val mIso = meteredIso
        var exp: Long
        var iso: Int
        if (mExp > 0 && mIso > 0) {
            // Use the viewfinder's own meter reading (it already includes the exposure slider),
            // capped so dim rooms stay dark and the flash lights the subject.
            val target = mExp.toDouble() * mIso
            exp = min(mExp, PULSE_MAX_EXPOSURE_NS)
            // exposure slider still nudges the cap so dark/far shots can be brightened
            val evScale = Math.pow(2.0, (config?.ev ?: 0f).toDouble().coerceAtLeast(0.0))
            val isoCap = (PULSE_MAX_ISO * evScale).toInt()
            iso = (target / exp).roundToInt().coerceIn(minIso, isoCap)
            // very bright scenes: drop shutter time instead of going below base ISO
            if (iso == minIso && target / exp < minIso) exp = max(100_000L, (target / minIso).toLong())
        } else {
            exp = PULSE_EXPOSURE_NS
            iso = (PULSE_ISO * Math.pow(2.0, (config?.ev ?: 0f).toDouble())).toInt()
        }
        if (config?.drag == true) {
            // keep the room's overall brightness about the same, but over a long shutter
            val target = if (mExp > 0 && mIso > 0) mExp.toDouble() * mIso else exp.toDouble() * iso
            exp = DRAG_EXPOSURE_NS
            iso = (target / exp).roundToInt().coerceIn(minIso, DRAG_MAX_ISO)
        }
        expRange?.let { exp = exp.coerceIn(it.lower, it.upper) }
        isoRange?.let { iso = iso.coerceIn(it.lower, it.upper) }
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        lastExpNs = exp; lastIso = iso
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(exp, 33_333_333L))
        b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
        if (config?.drag == true) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
        b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
        return b.build()
    }

    private fun autoRequest(cam: CameraDevice, rd: ImageReader, torch: Boolean): CaptureRequest {
        val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(rd.surface)
        lastExpNs = meteredExposure; lastIso = meteredIso  // auto exposure: closest reading we have
        applyCommon(b)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.FLASH_MODE, if (torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        b.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
        return b.build()
    }

    // ---------- preview fit ----------

    /** Scale the (sensor-rotated) preview to fill the view without stretching. */
    fun applyTransform() {
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        // portrait UI: preview buffer is landscape, shown rotated
        val cw = previewSize.height.toFloat()
        val ch = previewSize.width.toFloat()
        val s = max(vw / cw, vh / ch)
        val m = Matrix()
        m.setScale(s * cw / vw, s * ch / vh, vw / 2f, vh / 2f)
        textureView.setTransform(m)
    }
}
