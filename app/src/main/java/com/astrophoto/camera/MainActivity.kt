package com.astrophoto.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToInt

class Lens(val logicalId: String, val physicalId: String?, val label: String, val detail: String)

class Meta(
    val type: String,
    val iso: Int,
    val expNs: Long,
    val index: Int,
    val session: String,
    val exif: Int,
    val chars: CameraCharacteristics,
    val physicalId: String?
)

class Pending {
    var raw: Image? = null
    var jpeg: Image? = null
    var result: TotalCaptureResult? = null
}

class HistView(ctx: Context) : View(ctx) {
    var bins = IntArray(256)
    private val p = Paint()
    override fun onDraw(c: Canvas) {
        c.drawColor(0x66000000)
        val mx = (bins.maxOrNull() ?: 1).coerceAtLeast(1)
        val w = width / 256f
        p.color = 0xCCFFFFFF.toInt()
        for (i in 0..255) {
            val h = Math.sqrt(bins[i].toDouble() / mx).toFloat() * height
            c.drawRect(i * w, height - h, (i + 1) * w, height.toFloat(), p)
        }
    }
}

class MainActivity : Activity() {

    companion object {
        val TYPES = listOf("Lights", "Darks", "Flats", "Bias")
        val FORMATS = listOf("DNG", "JPG", "DNG+JPG")
        const val FMT_DNG = 0
        const val FMT_JPG = 1
        const val FMT_BOTH = 2
        val WB_LABELS = linkedMapOf(
            CameraMetadata.CONTROL_AWB_MODE_AUTO to "Auto",
            CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
            CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
            CameraMetadata.CONTROL_AWB_MODE_SHADE to "Shade",
            CameraMetadata.CONTROL_AWB_MODE_TWILIGHT to "Twilight",
            CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT to "Incandescent",
            CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent",
            CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT to "Warm fluorescent"
        )
    }

    // ---------- state ----------
    private lateinit var prefs: SharedPreferences
    private lateinit var cm: CameraManager
    private val main = Handler(Looper.getMainLooper())
    private lateinit var camThread: HandlerThread
    private lateinit var camHandler: Handler
    private lateinit var saveThread: HandlerThread
    private lateinit var saveHandler: Handler
    private val exec = Executor { r -> camHandler.post(r) }

    private var lenses: List<Lens> = emptyList()
    private var lensIndex = 0
    private var fmt = FMT_DNG
    private var typeIdx = 0
    private var expNs = 1_000_000_000L // 0 = auto
    private var iso = 800
    private var delaySec = 0
    private var frames = 10 // 0 = until stopped
    private var gapSec = 0
    private var wbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
    private var focusAuto = true
    private var focusDiopter = 0f
    private var zoom = 1f
    private var sessionName = ""
    private var showHist = true

    @Volatile private var chars: CameraCharacteristics? = null
    @Volatile private var camera: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var previewSize: Size? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var jpgReader: ImageReader? = null
    @Volatile private var useRaw = false
    @Volatile private var useJpeg = true

    // capture bookkeeping (camHandler thread only, except flags)
    private val pending = HashMap<Long, Pending>()
    @Volatile private var captureActive = false
    private var currentMeta: Meta? = null

    // sequence (main thread)
    private var running = false
    private var framesDone = 0
    private var runToken = 0

    // ---------- views ----------
    private lateinit var root: FrameLayout
    private lateinit var textureView: TextureView
    private lateinit var statusView: TextView
    private lateinit var hist: HistView
    private lateinit var shutterInner: View
    private val btns = ArrayList<Btn>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("astro", Context.MODE_PRIVATE)
        cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        camThread = HandlerThread("cam").also { it.start() }
        camHandler = Handler(camThread.looper)
        saveThread = HandlerThread("save").also { it.start() }
        saveHandler = Handler(saveThread.looper)

        loadPrefs()
        lenses = try { listLenses() } catch (e: Exception) { emptyList() }
        val key = prefs.getString("lens", "") ?: ""
        lensIndex = lenses.indexOfFirst { "${it.logicalId}|${it.physicalId}" == key }.let { if (it < 0) 0 else it }

        buildUi()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            if (textureView.isAvailable) openCamera()
        } else {
            toast("Camera permission is required")
        }
    }

    override fun onResume() {
        super.onResume()
        if (textureView.isAvailable) openCamera()
        histLoop()
    }

    override fun onPause() {
        if (running) stopRun("Stopped (app paused)")
        camHandler.post { closeCamera() }
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onDestroy() {
        camThread.quitSafely()
        saveThread.quitSafely()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    // ---------- prefs ----------
    private fun loadPrefs() {
        fmt = prefs.getInt("fmt", FMT_DNG)
        typeIdx = prefs.getInt("type", 0)
        expNs = prefs.getLong("exp", 1_000_000_000L)
        iso = prefs.getInt("iso", 800)
        delaySec = prefs.getInt("delay", 0)
        frames = prefs.getInt("frames", 10)
        gapSec = prefs.getInt("gap", 0)
        wbMode = prefs.getInt("wb", CameraMetadata.CONTROL_AWB_MODE_AUTO)
        focusAuto = prefs.getBoolean("focusAuto", true)
        focusDiopter = prefs.getFloat("focus", 0f)
        zoom = prefs.getFloat("zoom", 1f)
        showHist = prefs.getBoolean("hist", true)
        sessionName = prefs.getString("session", null)
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private fun savePrefs() {
        prefs.edit()
            .putInt("fmt", fmt).putInt("type", typeIdx).putLong("exp", expNs).putInt("iso", iso)
            .putInt("delay", delaySec).putInt("frames", frames).putInt("gap", gapSec)
            .putInt("wb", wbMode).putBoolean("focusAuto", focusAuto).putFloat("focus", focusDiopter)
            .putFloat("zoom", zoom).putBoolean("hist", showHist).putString("session", sessionName)
            .putString("lens", lenses.getOrNull(lensIndex)?.let { "${it.logicalId}|${it.physicalId}" } ?: "")
            .apply()
    }

    // ---------- UI ----------
    private inner class Btn(val label: String, val value: () -> String, val onTap: () -> Unit) {
        val view = LinearLayout(this@MainActivity)
        private val v = TextView(this@MainActivity)

        init {
            view.orientation = LinearLayout.VERTICAL
            view.gravity = Gravity.CENTER
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC333333.toInt())
            }
            v.setTextColor(Color.WHITE)
            v.textSize = 13f
            v.gravity = Gravity.CENTER
            v.maxLines = 1
            val l = TextView(this@MainActivity)
            l.text = label
            l.setTextColor(0xFFAAAAAA.toInt())
            l.textSize = 8f
            l.gravity = Gravity.CENTER
            view.addView(v)
            view.addView(l)
            view.setOnClickListener { onTap() }
            refresh()
        }

        fun refresh() { v.text = value() }
    }

    private fun refreshAll() { btns.forEach { it.refresh() } }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        textureView = TextureView(this)
        root.addView(textureView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
            }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean {
                camHandler.post { closeCamera() }
                return true
            }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPreview() }

        // top bar
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(0x55000000)
        top.setPadding(dp(28), dp(2), dp(28), dp(2))
        val title = TextView(this)
        title.text = "ASTROPHOTO\nCAMERA"
        title.setTextColor(Color.WHITE)
        title.textSize = 12f
        top.addView(title)
        statusView = TextView(this)
        statusView.setTextColor(0xFFFFCC66.toInt())
        statusView.textSize = 13f
        statusView.gravity = Gravity.CENTER
        statusView.text = "Ready"
        top.addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val eye = TextView(this)
        eye.text = "👁"
        eye.textSize = 22f
        eye.setTextColor(Color.WHITE)
        eye.setPadding(dp(12), dp(4), dp(12), dp(4))
        eye.setOnClickListener {
            showHist = !showHist
            hist.visibility = if (showHist) View.VISIBLE else View.GONE
            savePrefs()
            histLoop()
        }
        top.addView(eye)
        val menu = TextView(this)
        menu.text = "⋮"
        menu.textSize = 24f
        menu.setTextColor(Color.WHITE)
        menu.setPadding(dp(12), dp(0), dp(12), dp(0))
        menu.setOnClickListener { showMenu(it) }
        top.addView(menu)
        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // histogram
        hist = HistView(this)
        hist.visibility = if (showHist) View.VISIBLE else View.GONE
        val hp = FrameLayout.LayoutParams(dp(180), dp(70), Gravity.TOP or Gravity.END)
        hp.topMargin = dp(52)
        hp.rightMargin = dp(32)
        root.addView(hist, hp)

        // bottom bar: buttons + shutter
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setBackgroundColor(0x99000000.toInt())
        bottom.setPadding(dp(28), dp(6), dp(28), dp(6))

        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        scroll.addView(row)

        fun add(label: String, value: () -> String, tap: () -> Unit) {
            val b = Btn(label, value, tap)
            btns.add(b)
            val lp = LinearLayout.LayoutParams(dp(54), dp(54))
            lp.rightMargin = dp(8)
            row.addView(b.view, lp)
        }
        add("LENS", { lenses.getOrNull(lensIndex)?.label ?: "-" }) { onLens() }
        add("FORMAT", { FORMATS[fmt] }) { onFormat() }
        add("TYPE", { TYPES[typeIdx].uppercase() }) { onType() }
        add("SHUTTER", { if (expNs <= 0) "AUTO" else fmtShutter(expNs) }) { onShutter() }
        add("ISO", { iso.toString() }) { onIso() }
        add("DELAY", { "${delaySec}s" }) { pickNumber("Delay before first frame", delaySec, "Seconds") { delaySec = it; afterChange() } }
        add("FRAMES", { if (frames == 0) "∞" else frames.toString() }) { pickNumber("Number of frames", frames, "0 = until stopped") { frames = it; afterChange() } }
        add("GAP", { "${gapSec}s" }) { pickNumber("Pause between frames", gapSec, "Seconds") { gapSec = it; afterChange() } }
        add("WB", { WB_LABELS[wbMode]?.take(5)?.uppercase() ?: "?" }) { onWb() }
        add("FOCUS", { if (focusAuto) "AUTO" else if (focusDiopter <= 0f) "∞" else String.format(Locale.US, "%.2f", focusDiopter) }) { onFocus() }
        add("ZOOM", { String.format(Locale.US, "%.1fx", zoom) }) { onZoom() }

        bottom.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val shutter = FrameLayout(this)
        shutter.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(dp(4), Color.WHITE)
        }
        shutterInner = View(this)
        shutterInner.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF0A0A1E.toInt()) }
        val ip = FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER)
        shutter.addView(shutterInner, ip)
        shutter.setOnClickListener { toggleRun() }
        val sp = LinearLayout.LayoutParams(dp(66), dp(66))
        sp.leftMargin = dp(8)
        bottom.addView(shutter, sp)

        root.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
    }

    private fun afterChange() {
        savePrefs()
        refreshAll()
        updatePreview()
    }

    private fun setStatus(s: String) = runOnUiThread { statusView.text = s }
    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    private fun updateShutterUi() {
        (shutterInner.background as GradientDrawable).setColor(if (running) 0xFFCC2222.toInt() else 0xFF0A0A1E.toInt())
    }

    @Suppress("DEPRECATION")
    private fun fitPreview() {
        val ps = previewSize ?: return
        val pw = root.width
        val ph = root.height
        if (pw == 0 || ph == 0) return
        val ar = ps.width.toFloat() / ps.height
        var w = pw
        var h = (pw / ar).toInt()
        if (h > ph) { h = ph; w = (ph * ar).toInt() }
        val lp = textureView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            textureView.layoutParams = lp
        }
        val m = Matrix()
        if (windowManager.defaultDisplay.rotation == Surface.ROTATION_270) m.postRotate(180f, w / 2f, h / 2f)
        textureView.setTransform(m)
    }

    // ---------- dialogs ----------
    private fun pickList(title: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { d, w -> onPick(w); d.dismiss() }
            .show()
    }

    private fun pickNumber(title: String, cur: Int, hint: String, onSet: (Int) -> Unit) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.setText(cur.toString())
        et.setSelection(et.text.length)
        AlertDialog.Builder(this).setTitle(title).setMessage(hint).setView(et)
            .setPositiveButton("OK") { _, _ -> onSet(et.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: cur) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun pickSlider(
        title: String, lo: Float, hi: Float, cur: Float, fmtv: (Float) -> String,
        neutral: String?, onNeutral: (() -> Unit)?, onSet: (Float) -> Unit
    ) {
        val sb = SeekBar(this)
        sb.max = 1000
        sb.progress = (((cur - lo) / (hi - lo)) * 1000).toInt().coerceIn(0, 1000)
        val tv = TextView(this)
        tv.text = fmtv(cur)
        tv.textSize = 22f
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(8), 0, dp(8))
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(12), dp(24), 0)
        box.addView(tv)
        box.addView(sb)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val v = lo + (hi - lo) * p / 1000f
                tv.text = fmtv(v)
                if (fromUser) onSet(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        val b = AlertDialog.Builder(this).setTitle(title).setView(box).setPositiveButton("OK", null)
        if (neutral != null) b.setNeutralButton(neutral) { _, _ -> onNeutral?.invoke() }
        b.show()
    }

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Camera info")
        pm.menu.add(0, 2, 1, "Session name: $sessionName")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> showCameraInfo()
                2 -> {
                    val et = EditText(this)
                    et.setText(sessionName)
                    AlertDialog.Builder(this).setTitle("Session name (folder)").setView(et)
                        .setPositiveButton("OK") { _, _ ->
                            val n = et.text.toString().trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
                            if (n.isNotEmpty()) { sessionName = n; savePrefs() }
                        }.setNegativeButton("Cancel", null).show()
                }
            }
            true
        }
        pm.show()
    }

    private fun showCameraInfo() {
        val tv = TextView(this)
        tv.text = cameraInfo()
        tv.typeface = android.graphics.Typeface.MONOSPACE
        tv.textSize = 12f
        tv.setPadding(dp(20), dp(12), dp(20), dp(12))
        val sv = ScrollView(this)
        sv.addView(tv)
        AlertDialog.Builder(this).setTitle("Camera info").setView(sv).setPositiveButton("Close", null).show()
    }

    // ---------- button handlers ----------
    private fun onLens() {
        if (running) { toast("Stop the sequence first"); return }
        if (lenses.isEmpty()) { toast("No cameras found"); return }
        pickList("Lens", lenses.map { it.detail }, lensIndex) {
            lensIndex = it
            savePrefs()
            refreshAll()
            camHandler.post { closeCamera() }
            camHandler.postDelayed({ openCameraNow() }, 300)
        }
    }

    private fun onFormat() {
        if (running) { toast("Stop the sequence first"); return }
        pickList("Save format", FORMATS, fmt) {
            fmt = it
            savePrefs()
            refreshAll()
            camHandler.post { startSession() }
        }
    }

    private fun onType() {
        pickList("Frame type", TYPES, typeIdx) {
            typeIdx = it
            savePrefs()
            refreshAll()
        }
    }

    private fun shutterOptions(): List<Long> {
        val r = chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: Range(1_000_000L, 1_000_000_000L)
        val secs = doubleArrayOf(
            1 / 4000.0, 1 / 2000.0, 1 / 1000.0, 1 / 500.0, 1 / 250.0, 1 / 125.0, 1 / 60.0, 1 / 30.0,
            1 / 15.0, 1 / 8.0, 1 / 4.0, 1 / 3.0, 1 / 2.0, 1.0, 2.0, 4.0, 5.0, 8.0, 10.0, 15.0, 20.0,
            25.0, 30.0, 45.0, 60.0, 90.0, 120.0, 180.0, 240.0, 300.0, 600.0
        )
        val out = secs.map { (it * 1e9).toLong() }.filter { it >= r.lower && it <= r.upper }.toMutableList()
        if (r.upper <= 600_000_000_000L && (out.isEmpty() || out.last() < r.upper * 0.9)) out.add(r.upper)
        return out
    }

    private fun onShutter() {
        val opts = shutterOptions()
        val labels = listOf("Auto") + opts.map { fmtShutter(it) }
        val sel = if (expNs <= 0) 0 else (opts.indexOfFirst { abs(it - expNs) < expNs * 0.05 } + 1).coerceAtLeast(0)
        pickList("Shutter", labels, sel) {
            expNs = if (it == 0) 0L else opts[it - 1]
            afterChange()
        }
    }

    private fun onIso() {
        val r = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 3200)
        val base = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600)
        val opts = base.filter { it >= r.lower && it <= r.upper }.toMutableList()
        if (opts.isEmpty() || opts.last() < r.upper * 0.8) opts.add(r.upper)
        pickList("ISO (ignored when shutter is Auto)", opts.map { it.toString() }, opts.indexOf(iso)) {
            iso = opts[it]
            afterChange()
        }
    }

    private fun onWb() {
        val avail = chars?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        val modes = WB_LABELS.keys.filter { it in avail }
        if (modes.isEmpty()) return
        pickList("White balance", modes.map { WB_LABELS[it] ?: "?" }, modes.indexOf(wbMode)) {
            wbMode = modes[it]
            afterChange()
        }
    }

    private fun onFocus() {
        val minF = chars?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        if (minF <= 0f) { toast("This lens has fixed focus"); return }
        pickSlider(
            "Focus (left = infinity)", 0f, minF, if (focusAuto) 0f else focusDiopter,
            { if (it < 0.05f) "∞" else String.format(Locale.US, "%.2f D  (~%.0f cm)", it, 100f / it) },
            "Auto", { focusAuto = true; afterChange() }
        ) { focusAuto = false; focusDiopter = it; afterChange() }
    }

    private fun onZoom() {
        val maxZ = minOf(chars?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4f, 10f)
        pickSlider(
            "Digital zoom", 1f, maxZ, zoom.coerceIn(1f, maxZ),
            { String.format(Locale.US, "%.1fx", it) },
            "Reset", { zoom = 1f; afterChange() }
        ) { zoom = it; afterChange() }
    }

    // ---------- formatting ----------
    private fun fmtShutter(ns: Long): String {
        val s = ns / 1e9
        return if (s >= 0.95) {
            if (abs(s - s.roundToInt()) < 0.01) "${s.roundToInt()}s" else String.format(Locale.US, "%.1fs", s)
        } else "1/${(1.0 / s).roundToInt()}"
    }

    // ---------- lenses ----------
    private fun focalEquiv(c: CameraCharacteristics): Int? {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diag = Math.hypot(s.width.toDouble(), s.height.toDouble())
        if (diag <= 0) return null
        return (f * 43.267 / diag).roundToInt()
    }

    private fun listLenses(): List<Lens> {
        val out = ArrayList<Lens>()
        val top = cm.cameraIdList.toSet()
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val eq = focalEquiv(c)
            val lbl = if (eq != null) "${eq}mm" else "cam$id"
            out.add(Lens(id, null, lbl, "$lbl   (camera $id)"))
            for (p in c.physicalCameraIds) {
                if (p in top) continue
                try {
                    val pc = cm.getCameraCharacteristics(p)
                    val pe = focalEquiv(pc)
                    val pl = if (pe != null) "${pe}mm" else "p$p"
                    out.add(Lens(id, p, pl, "$pl   (physical $p of camera $id)"))
                } catch (e: Exception) { /* hidden physical camera not queryable */ }
            }
        }
        return out
    }

    private fun charsFor(l: Lens): CameraCharacteristics {
        if (l.physicalId != null) {
            try { return cm.getCameraCharacteristics(l.physicalId) } catch (e: Exception) { }
        }
        return cm.getCameraCharacteristics(l.logicalId)
    }

    private fun hasRaw(c: CameraCharacteristics): Boolean {
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: return false
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) return false
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)
        return sizes != null && sizes.isNotEmpty()
    }

    private fun cameraInfo(): String = buildString {
        for (l in lenses) {
            val c = try { charsFor(l) } catch (e: Exception) { continue }
            append(l.label).append("  [camera ").append(l.logicalId)
            l.physicalId?.let { append(" / physical ").append(it) }
            append("]\n")
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.let { append("  focal length: ").append(it).append(" mm\n") }
            focalEquiv(c)?.let { append("  35mm equivalent: ~").append(it).append(" mm\n") }
            append("  RAW/DNG: ").append(if (hasRaw(c)) "yes" else "no").append("\n")
            c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { append("  shutter: ").append(fmtShutter(it.lower)).append(" .. ").append(fmtShutter(it.upper)).append("\n") }
            c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { append("  ISO: ").append(it.lower).append(" .. ").append(it.upper).append("\n") }
            append("  min focus dist: ").append(c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f).append(" D\n")
            append("  max digital zoom: ").append(c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).append("x\n\n")
        }
        append("All camera IDs: ").append(cm.cameraIdList.joinToString())
    }

    // ---------- camera ----------
    private fun openCamera() { camHandler.post { openCameraNow() } }

    // must run on camHandler
    private fun openCameraNow() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        closeCamera()
        val lens = lenses.getOrNull(lensIndex)
        if (lens == null) { toast("No back camera found"); return }
        try {
            chars = charsFor(lens)
            cm.openCamera(lens.logicalId, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) { camera = cd; startSession() }
                override fun onDisconnected(cd: CameraDevice) { cd.close(); camera = null; session = null }
                override fun onError(cd: CameraDevice, error: Int) {
                    cd.close(); camera = null; session = null
                    toast("Camera error $error")
                }
            }, camHandler)
        } catch (e: Exception) {
            toast("Cannot open camera: ${e.message}")
        }
        main.post { refreshAll() }
    }

    private fun closeSession() {
        try { session?.close() } catch (e: Exception) { }
        session = null
        try { rawReader?.close() } catch (e: Exception) { }
        try { jpgReader?.close() } catch (e: Exception) { }
        rawReader = null
        jpgReader = null
        pending.values.forEach { it.raw?.close(); it.jpeg?.close() }
        pending.clear()
        captureActive = false
    }

    private fun closeCamera() {
        closeSession()
        try { camera?.close() } catch (e: Exception) { }
        camera = null
    }

    private fun choosePreview(sizes: Array<Size>, ref: Size): Size {
        val ar = ref.width.toFloat() / ref.height
        val cap = 1920L * 1440L
        fun area(s: Size) = s.width.toLong() * s.height
        return sizes.filter { area(it) <= cap && abs(it.width.toFloat() / it.height - ar) < 0.02f }.maxByOrNull { area(it) }
            ?: sizes.filter { area(it) <= cap }.maxByOrNull { area(it) }
            ?: sizes[0]
    }

    // must run on camHandler
    private fun startSession() {
        val cam = camera ?: return
        val tex = textureView.surfaceTexture ?: return
        val ch = chars ?: return
        val lens = lenses.getOrNull(lensIndex) ?: return
        val phys = lens.physicalId
        closeSession()
        try {
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            fun area(s: Size) = s.width.toLong() * s.height
            val rawSz = if (hasRaw(ch)) map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { area(it) } else null
            val jpgSz = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { area(it) }
            useRaw = fmt != FMT_JPG && rawSz != null
            useJpeg = (fmt != FMT_DNG || !useRaw) && jpgSz != null
            if (fmt != FMT_JPG && rawSz == null) toast("This lens does not offer RAW to apps, saving JPEG instead")
            if (!useRaw && !useJpeg) { toast("No usable output format"); return }

            val ref = rawSz ?: jpgSz ?: Size(4000, 3000)
            val ps = choosePreview(map.getOutputSizes(SurfaceTexture::class.java), ref)
            previewSize = ps
            tex.setDefaultBufferSize(ps.width, ps.height)
            runOnUiThread { fitPreview() }

            val prevSurf = Surface(tex)
            previewSurface = prevSurf
            val outs = ArrayList<OutputConfiguration>()
            fun cfg(s: Surface): OutputConfiguration {
                val c = OutputConfiguration(s)
                if (phys != null) c.setPhysicalCameraId(phys)
                return c
            }
            outs.add(cfg(prevSurf))
            if (useRaw && rawSz != null) {
                val r = ImageReader.newInstance(rawSz.width, rawSz.height, ImageFormat.RAW_SENSOR, 2)
                r.setOnImageAvailableListener({ rd -> onImage(rd, true) }, camHandler)
                rawReader = r
                outs.add(cfg(r.surface))
            }
            if (useJpeg && jpgSz != null) {
                val r = ImageReader.newInstance(jpgSz.width, jpgSz.height, ImageFormat.JPEG, 2)
                r.setOnImageAvailableListener({ rd -> onImage(rd, false) }, camHandler)
                jpgReader = r
                outs.add(cfg(r.surface))
            }
            val sc = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, exec,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        updatePreview()
                        setStatus("Ready  (${if (useRaw) "DNG" else ""}${if (useRaw && useJpeg) "+" else ""}${if (useJpeg) "JPG" else ""})")
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        toast("Could not start this lens/format combination. Try another format or lens.")
                    }
                })
            cam.createCaptureSession(sc)
        } catch (e: Exception) {
            toast("Session error: ${e.message}")
        }
    }

    private fun cropFor(ch: CameraCharacteristics): Rect? {
        val a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val z = zoom.coerceAtLeast(1f)
        val w = (a.width() / z).toInt()
        val h = (a.height() / z).toInt()
        val l = a.left + (a.width() - w) / 2
        val t = a.top + (a.height() - h) / 2
        return Rect(l, t, l + w, t + h)
    }

    private fun applyControls(b: CaptureRequest.Builder, exp: Long, preview: Boolean) {
        val ch = chars ?: return
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (exp <= 0L) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val er = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            var e = if (preview) minOf(exp, 500_000_000L) else exp
            if (er != null) e = e.coerceIn(er.lower, er.upper)
            val ir = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val i = if (ir != null) iso.coerceIn(ir.lower, ir.upper) else iso
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, i)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(e, 33_333_333L))
        }
        val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        if (focusAuto && CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else if (!focusAuto && (ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
        }
        val awb = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList()
        if (wbMode in awb) b.set(CaptureRequest.CONTROL_AWB_MODE, wbMode)
        cropFor(ch)?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }
        val nr = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList() ?: emptyList()
        if (CaptureRequest.NOISE_REDUCTION_MODE_OFF in nr) b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        if (!preview) {
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            b.set(CaptureRequest.JPEG_QUALITY, 98.toByte())
        }
    }

    @Suppress("DEPRECATION")
    private fun displayDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun jpegOrientation(): Int {
        val s = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return (s - displayDegrees() + 360) % 360
    }

    private fun exifFor(deg: Int) = when (deg) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun updatePreview() {
        camHandler.post {
            val cam = camera
            val s = session
            val ps = previewSurface
            if (cam == null || s == null || ps == null) return@post
            try {
                val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(ps)
                applyControls(b, expNs, true)
                s.setRepeatingRequest(b.build(), null, camHandler)
            } catch (e: Exception) { /* session closing */ }
        }
    }

    // ---------- capture sequence ----------
    private fun toggleRun() { if (running) stopRun("Stopped") else startRun() }

    private fun startRun() {
        if (session == null) { toast("Camera not ready yet"); return }
        running = true
        framesDone = 0
        val tok = ++runToken
        updateShutterUi()
        if (delaySec > 0) countdown(delaySec, tok) else captureNext(tok)
    }

    private fun stopRun(msg: String) {
        running = false
        runToken++
        updateShutterUi()
        setStatus(msg)
        camHandler.post {
            try { session?.abortCaptures() } catch (e: Exception) { }
            captureActive = false
            pending.values.forEach { it.raw?.close(); it.jpeg?.close() }
            pending.clear()
            updatePreview()
        }
    }

    private fun countdown(left: Int, tok: Int) {
        if (!running || tok != runToken) return
        if (left <= 0) { captureNext(tok); return }
        setStatus("Starting in ${left}s")
        main.postDelayed({ countdown(left - 1, tok) }, 1000)
    }

    private fun captureNext(tok: Int) {
        if (!running || tok != runToken) return
        if (frames > 0 && framesDone >= frames) { stopRun("Done: $framesDone frames saved"); return }
        setStatus("Capturing ${framesDone + 1}/${if (frames == 0) "∞" else frames.toString()}  (${TYPES[typeIdx]})")
        camHandler.post { doCapture() }
    }

    private fun nextIndex(sess: String, type: String): Int {
        val k = "cnt|$sess|$type"
        val n = prefs.getInt(k, 0) + 1
        prefs.edit().putInt(k, n).apply()
        return n
    }

    // camHandler thread
    private fun doCapture() {
        val cam = camera
        val s = session
        val ch = chars
        if (cam == null || s == null || ch == null) { main.post { stopRun("Camera not ready") }; return }
        try {
            val type = TYPES[typeIdx]
            val range = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            var exp = expNs
            if (type == "Bias" && range != null && exp > 0) exp = range.lower
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            if (useRaw) rawReader?.let { b.addTarget(it.surface) }
            if (useJpeg) jpgReader?.let { b.addTarget(it.surface) }
            applyControls(b, exp, false)
            val idx = nextIndex(sessionName, type)
            currentMeta = Meta(type, iso, exp, idx, sessionName, exifFor(jpegOrientation()), ch,
                lenses.getOrNull(lensIndex)?.physicalId)
            pending.values.forEach { it.raw?.close(); it.jpeg?.close() }
            pending.clear()
            captureActive = true
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(sx: CameraCaptureSession, rq: CaptureRequest, result: TotalCaptureResult) {
                    onResult(result)
                }
                override fun onCaptureFailed(sx: CameraCaptureSession, rq: CaptureRequest, f: CaptureFailure) {
                    captureActive = false
                    main.post { if (running) stopRun("Capture failed") }
                }
            }, camHandler)
        } catch (e: Exception) {
            main.post { stopRun("Capture error: ${e.message}") }
        }
    }

    private fun onImage(reader: ImageReader, isRaw: Boolean) {
        var got: Image? = null
        try { got = reader.acquireNextImage() } catch (e: Exception) { }
        val img = got ?: return
        if (!captureActive) { img.close(); return }
        val p = pending.getOrPut(img.timestamp) { Pending() }
        if (isRaw) p.raw = img else p.jpeg = img
        tryFinish(img.timestamp)
    }

    private fun onResult(result: TotalCaptureResult) {
        val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        if (!captureActive) return
        pending.getOrPut(ts) { Pending() }.result = result
        tryFinish(ts)
    }

    private fun tryFinish(ts: Long) {
        val p = pending[ts] ?: return
        if (p.result == null) return
        if (useRaw && p.raw == null) return
        if (useJpeg && p.jpeg == null) return
        pending.remove(ts)
        captureActive = false
        val meta = currentMeta ?: return
        saveHandler.post { process(p, meta) }
    }

    // ---------- saving ----------
    private fun insert(name: String, mime: String, dir: String): Pair<Uri, OutputStream>? {
        val v = ContentValues()
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return null
        val os = contentResolver.openOutputStream(uri) ?: return null
        return Pair(uri, os)
    }

    private fun process(p: Pending, m: Meta) {
        var ok = true
        var err = ""
        try {
            val res = p.result
            val e = res?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: m.expNs
            val i = res?.get(CaptureResult.SENSOR_SENSITIVITY) ?: m.iso
            val base = "${m.type.uppercase()}_${String.format(Locale.US, "%04d", m.index)}_ISO${i}_${fmtShutter(e).replace("/", "-")}"
            val dir = "Pictures/AstroPhotoCamera/${m.session}/${m.type}"
            p.raw?.let { img ->
                val (_, os) = insert("$base.dng", "image/x-adobe-dng", dir) ?: throw IllegalStateException("cannot create DNG file")
                os.use { o ->
                    var cr: CaptureResult = res!!
                    if (m.physicalId != null) {
                        try { cr = res.physicalCameraTotalResults[m.physicalId] ?: res } catch (x: Exception) { }
                    }
                    val d = DngCreator(m.chars, cr)
                    try {
                        d.setOrientation(m.exif)
                        d.writeImage(o, img)
                    } finally { d.close() }
                }
            }
            p.jpeg?.let { img ->
                val (_, os) = insert("$base.jpg", "image/jpeg", dir) ?: throw IllegalStateException("cannot create JPG file")
                os.use { o ->
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    o.write(bytes)
                }
            }
        } catch (t: Throwable) {
            ok = false
            err = t.message ?: t.javaClass.simpleName
        } finally {
            p.raw?.close()
            p.jpeg?.close()
        }
        main.post { onFrameSaved(ok, err) }
    }

    private fun onFrameSaved(ok: Boolean, err: String) {
        if (!running) return
        if (!ok) { stopRun("Save failed: $err"); return }
        framesDone++
        setStatus("Saved $framesDone/${if (frames == 0) "∞" else frames.toString()}")
        val tok = runToken
        main.postDelayed({ captureNext(tok) }, gapSec * 1000L)
    }

    // ---------- histogram ----------
    private fun histLoop() {
        main.removeCallbacks(histRunnable)
        if (showHist) main.postDelayed(histRunnable, 600)
    }

    private val histRunnable = object : Runnable {
        override fun run() {
            if (showHist && textureView.isAvailable) {
                try {
                    val bmp = textureView.getBitmap(128, 96)
                    if (bmp != null) {
                        val px = IntArray(128 * 96)
                        bmp.getPixels(px, 0, 128, 0, 0, 128, 96)
                        val bins = IntArray(256)
                        for (c in px) {
                            val lum = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000
                            bins[lum.coerceIn(0, 255)]++
                        }
                        bmp.recycle()
                        hist.bins = bins
                        hist.invalidate()
                    }
                } catch (e: Exception) { }
            }
            if (showHist) main.postDelayed(this, 600)
        }
    }
}
