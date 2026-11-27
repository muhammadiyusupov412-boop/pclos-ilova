package uz.pclos.kamera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import uz.pclos.kamera.core.Coco
import uz.pclos.kamera.core.CountLine
import uz.pclos.kamera.core.CountingEngine
import uz.pclos.kamera.core.Pt
import uz.pclos.kamera.core.SessionLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CountActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SITE = "site"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_VIDEO = "video"
        const val EXTRA_FPS = "fps"
        const val EXTRA_CONF = "conf"
        private const val VIDEO_SEGMENT_MS = 60 * 60_000L
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var dimView: View
    private lateinit var btnStart: Button
    private lateinit var analysisExecutor: ExecutorService

    private var detector: Detector? = null
    private var attr: AttrClassifier? = null
    private var engine: CountingEngine? = null
    private var log: SessionLog? = null
    @Volatile private var running = false
    private var frameIdx = 0
    private var lastProcessed = 0L
    private var fpsEma = 0f
    private var lastIntervalWrite = 0L
    private var sessionStart = 0L
    private var sessionDir: File? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var segmentStart = 0L
    private var segmentNo = 0

    private lateinit var site: String
    private var intervalMin = 15
    private var recordVideo = false
    private var maxFps = 10
    private var conf = 0.35f

    private val lines = mutableListOf(
        CountLine("1-chiziq (piyoda)", Pt(0.1f, 0.6f), Pt(0.9f, 0.6f), CountLine.Target.PEDESTRIAN),
        CountLine("2-chiziq (transport)", Pt(0.5f, 0.1f), Pt(0.5f, 0.9f), CountLine.Target.VEHICLE),
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_count)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        site = intent.getStringExtra(EXTRA_SITE)?.ifBlank { null } ?: "chorraha"
        intervalMin = intent.getIntExtra(EXTRA_INTERVAL, 15)
        recordVideo = intent.getBooleanExtra(EXTRA_VIDEO, false)
        maxFps = intent.getIntExtra(EXTRA_FPS, 10)
        conf = intent.getFloatExtra(EXTRA_CONF, 0.35f)

        previewView = findViewById(R.id.preview)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        dimView = findViewById(R.id.dim)
        btnStart = findViewById(R.id.btnStart)
        loadLines()
        overlay.lines = lines
        overlay.onLinesChanged = { saveLines() }

        findViewById<Button>(R.id.btnLine1).setOnClickListener { editLine(0) }
        findViewById<Button>(R.id.btnLine2).setOnClickListener { editLine(1) }
        btnStart.setOnClickListener { if (running) stopSession() else startSession() }
        findViewById<Button>(R.id.btnDim).setOnClickListener { setDim(true) }
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { setDim(false); return true }
        })
        dimView.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }

        analysisExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor.execute {
            try {
                detector = Detector(this).also { it.confThreshold = conf }
                attr = AttrClassifier.createOrNull(this)
                if (attr == null) runOnUiThread {
                    Toast.makeText(this, getString(R.string.no_attr_model), Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Model yuklanmadi: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
        startCamera()
    }

    private fun editLine(i: Int) {
        if (running) { Toast.makeText(this, R.string.stop_first, Toast.LENGTH_SHORT).show(); return }
        overlay.editIndex = i
        overlay.invalidate()
    }

    private fun prefs() = getSharedPreferences("lines_$site", Context.MODE_PRIVATE)

    private fun loadLines() {
        val p = prefs()
        for ((i, l) in lines.withIndex()) {
            if (!p.contains("l${i}ax")) continue
            l.a = Pt(p.getFloat("l${i}ax", l.a.x), p.getFloat("l${i}ay", l.a.y))
            l.b = Pt(p.getFloat("l${i}bx", l.b.x), p.getFloat("l${i}by", l.b.y))
        }
    }

    private fun saveLines() {
        val e = prefs().edit()
        for ((i, l) in lines.withIndex()) {
            e.putFloat("l${i}ax", l.a.x).putFloat("l${i}ay", l.a.y)
            e.putFloat("l${i}bx", l.b.x).putFloat("l${i}by", l.b.y)
        }
        e.apply()
    }

    private fun setDim(on: Boolean) {
        dimView.visibility = if (on) View.VISIBLE else View.GONE
        val lp = window.attributes
        lp.screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { analyze(it) }
            provider.unbindAll()
            val cam = CameraSelector.DEFAULT_BACK_CAMERA
            if (recordVideo) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                try {
                    provider.bindToLifecycle(this, cam, preview, analysis, vc)
                    videoCapture = vc
                    return@addListener
                } catch (t: Throwable) {
                    provider.unbindAll()
                    Toast.makeText(this, R.string.video_unsupported, Toast.LENGTH_LONG).show()
                    recordVideo = false
                }
            }
            provider.bindToLifecycle(this, cam, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProcessed < 1000L / maxFps) { image.close(); return }
        val det = detector
        if (det == null) { image.close(); return }
        val dt = now - lastProcessed
        lastProcessed = now
        val rot = image.imageInfo.rotationDegrees
        var bmp: Bitmap = image.toBitmap()
        image.close()
        if (rot != 0) {
            val m = Matrix().apply { postRotate(rot.toFloat()) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.recycle(); bmp = r
        }
        val dets = det.detect(bmp)
        fpsEma = if (fpsEma == 0f) 1000f / dt else 0.9f * fpsEma + 0.1f * (1000f / dt)

        val eng = engine
        val items: List<OverlayView.Item>
        if (running && eng != null) {
            frameIdx++
            val a = attr
            eng.process(dets, frameIdx, bmp.width, bmp.height, System.currentTimeMillis(),
                if (a != null) uz.pclos.kamera.core.AttrSource { _, box -> a.classify(bmp, box) } else null)
            items = eng.tracker.tracks.filter { it.lastFrame == frameIdx && eng.tracker.confirmed(it) }.map { t ->
                val label = if (t.isPerson) {
                    if (t.isRider) "#${t.id} haydovchi"
                    else eng.attrResult(t.id)?.let { "#${t.id} ${it.first}" } ?: "#${t.id} piyoda"
                } else "#${t.id} ${Coco.vehicleName(t.cls)}"
                OverlayView.Item(t.box, label, t.isPerson)
            }
            if (now - lastIntervalWrite > 30_000) { log?.writeIntervals(); lastIntervalWrite = now }
        } else {
            items = dets.map { OverlayView.Item(it.box, if (it.cls == Coco.PERSON) "piyoda" else Coco.vehicleName(it.cls), it.cls == Coco.PERSON) }
        }
        val stats = buildStats()
        val w = bmp.width; val h = bmp.height
        bmp.recycle()
        runOnUiThread {
            overlay.frameW = w; overlay.frameH = h
            overlay.items = items
            overlay.stats = stats
            overlay.invalidate()
            if (running && recordVideo && System.currentTimeMillis() - segmentStart > VIDEO_SEGMENT_MS) rotateVideo()
        }
    }

    private fun batteryInfo(): Pair<Int, Float> {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1 to 0f
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        return (lvl * 100 / maxOf(1, scale)) to temp
    }

    private fun buildStats(): List<String> {
        val out = ArrayList<String>()
        val (bat, temp) = batteryInfo()
        val dur = if (running) (System.currentTimeMillis() - sessionStart) / 1000 else 0
        out += "%s  %.1f kadr/s  🔋%d%%  %.0f°C".format(Locale.US, if (running) "● YOZILMOQDA" else "○ tayyor", fpsEma, bat, temp)
        if (running) out += "Davomiyligi: %02d:%02d:%02d".format(dur / 3600, dur / 60 % 60, dur % 60)
        val t = log?.totals ?: return out
        val ped = SessionLog.AGE_GROUPS.map { t["piyoda:$it"] ?: 0 }
        out += "Piyoda: ${ped.sum()}  (bola ${ped[0]}, kattalar ${ped[1]}, keksa ${ped[2]}, ? ${ped[3]})"
        val veh = SessionLog.VEHICLES.associateWith { t["transport:$it"] ?: 0 }
        out += "Transport: ${veh.values.sum()}"
        out += "  yengil ${veh["yengil"]}, avtobus ${veh["avtobus"]}, yuk ${veh["yuk"]}"
        out += "  moto ${veh["mototsikl"]}, velo ${veh["velosiped"]}"
        if (temp >= 45f) out += "⚠ Telefon qizib ketdi: soyaga qo'ying"
        return out
    }

    private fun startSession() {
        if (detector == null) { Toast.makeText(this, R.string.model_loading, Toast.LENGTH_SHORT).show(); return }
        sessionStart = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(sessionStart))
        val safeSite = site.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
        val dir = File(getExternalFilesDir(null), "PCLOS_Kamera/${safeSite}_$stamp")
        sessionDir = dir
        val l = SessionLog(dir, intervalMin)
        val lineSnapshot = lines.map { it.copy() }
        analysisExecutor.execute {
            log = l
            frameIdx = 0
            engine = CountingEngine(lineSnapshot, l, maxAgeFrames = maxOf(10, maxFps * 3))
            running = true
        }
        writeInfo(dir, null)
        if (recordVideo) { segmentNo = 0; startVideo() }
        btnStart.setText(R.string.stop)
    }

    private fun stopSession() {
        analysisExecutor.execute {
            running = false
            engine?.flush()
            engine = null
        }
        recording?.stop(); recording = null
        sessionDir?.let { writeInfo(it, System.currentTimeMillis()) }
        btnStart.setText(R.string.start)
        Toast.makeText(this, getString(R.string.saved_to, sessionDir?.name ?: ""), Toast.LENGTH_LONG).show()
    }

    private fun writeInfo(dir: File, end: Long?) {
        dir.mkdirs()
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append("chorraha=").append(site).append('\n')
        sb.append("boshlandi=").append(f.format(Date(sessionStart))).append('\n')
        if (end != null) sb.append("tugadi=").append(f.format(Date(end))).append('\n')
        sb.append("interval_daqiqa=").append(intervalMin).append('\n')
        sb.append("kadr_s_chegara=").append(maxFps).append('\n')
        sb.append("ishonch_chegara=").append(conf).append('\n')
        sb.append("yosh_modeli=").append(if (attr != null) "PA-100K PULC" else "yo'q").append('\n')
        sb.append("qurilma=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        for (l in lines) sb.append("chiziq=").append(l.name).append(';')
            .append("%.3f,%.3f;%.3f,%.3f".format(Locale.US, l.a.x, l.a.y, l.b.x, l.b.y)).append('\n')
        File(dir, "malumot.txt").writeText(sb.toString())
    }

    @SuppressLint("MissingPermission")
    private fun startVideo() {
        val vc = videoCapture ?: return
        segmentNo++
        segmentStart = System.currentTimeMillis()
        val name = "${sessionDir?.name ?: "pclos"}_q$segmentNo"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PCLOS_Kamera")
        }
        val opts = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values).build()
        recording = vc.output.prepareRecording(this, opts).start(ContextCompat.getMainExecutor(this)) { }
    }

    private fun rotateVideo() {
        recording?.stop(); recording = null
        startVideo()
    }

    override fun onDestroy() {
        if (running) stopSession()
        analysisExecutor.execute {
            detector?.close(); attr?.close()
        }
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
