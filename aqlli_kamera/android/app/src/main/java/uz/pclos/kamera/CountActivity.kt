package uz.pclos.kamera

import android.annotation.SuppressLint
import android.content.ContentValues
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
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import uz.pclos.kamera.core.Coco
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Jonli kamera orqali sanash. */
class CountActivity : AppCompatActivity() {
    companion object {
        private const val VIDEO_SEGMENT_MS = 60 * 60_000L
    }

    private lateinit var cfg: RunConfig
    private lateinit var setup: SiteSetup
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var dimView: View
    private lateinit var btnStart: Button
    private lateinit var executor: ExecutorService

    private var detector: Detector? = null
    private var attr: AttrClassifier? = null
    @Volatile private var analyzer: Analyzer? = null
    @Volatile private var running = false
    private var lastProcessed = 0L
    private var fpsEma = 0f
    private var sessionStart = 0L
    private var sessionDir: File? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var segmentStart = 0L
    private var segmentNo = 0
    private var recordVideo = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_count)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cfg = RunConfig.from(intent)
        recordVideo = cfg.recordVideo
        setup = SiteSetup(this, cfg.site, cfg.mode)

        previewView = findViewById(R.id.preview)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        dimView = findViewById(R.id.dim)
        btnStart = findViewById(R.id.btnStart)
        refreshShapes()

        findViewById<Button>(R.id.btnSetup).setOnClickListener {
            if (running) Toast.makeText(this, R.string.stop_first, Toast.LENGTH_SHORT).show()
            else setup.showMenu(it, overlay) { refreshShapes() }
        }
        btnStart.setOnClickListener { if (running) stopSession() else startSession() }
        findViewById<Button>(R.id.btnDim).setOnClickListener { setDim(true) }
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { setDim(false); return true }
        })
        dimView.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }

        executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                detector = Detector(this).also { it.confThreshold = cfg.conf }
                attr = if (cfg.mode == RunConfig.MODE_VEH) null else AttrClassifier.createOrNull(this)
                if (attr == null && cfg.mode != RunConfig.MODE_VEH) runOnUiThread {
                    Toast.makeText(this, getString(R.string.no_attr_model), Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Model yuklanmadi: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
        startCamera()
    }

    private fun refreshShapes() {
        overlay.lines = setup.visibleLines()
        overlay.zones = setup.visibleZones()
        overlay.invalidate()
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
            val ratio = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
            val preview = Preview.Builder().setResolutionSelector(ratio).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysisSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(android.util.Size(1280, 960),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSel)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
            analysis.setAnalyzer(executor) { analyze(it) }
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
        if (now - lastProcessed < 1000L / cfg.fps) { image.close(); return }
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
        fpsEma = if (fpsEma == 0f) 1000f / dt else 0.9f * fpsEma + 0.1f * (1000f / dt)
        val an = analyzer
        val items = if (running && an != null) {
            val res = an.process(bmp, System.currentTimeMillis())
            an.periodicSave(now)
            res
        } else {
            det.detect(bmp).filter {
                when (cfg.mode) { RunConfig.MODE_PED -> it.cls == Coco.PERSON; RunConfig.MODE_VEH -> it.cls != Coco.PERSON; else -> true }
            }.map { OverlayView.Item(it.box, if (it.cls == Coco.PERSON) "piyoda" else Coco.vehicleName(it.cls), it.cls == Coco.PERSON) }
        }
        val stats = buildStats(an)
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

    private fun buildStats(an: Analyzer?): List<String> {
        val out = ArrayList<String>()
        val (bat, temp) = batteryInfo()
        val dur = if (running) (System.currentTimeMillis() - sessionStart) / 1000 else 0
        out += "%s  %.1f kadr/s  🔋%d%%  %.0f°C".format(Locale.US, if (running) "● YOZILMOQDA" else "○ tayyor", fpsEma, bat, temp)
        if (running) out += "Davomiyligi: %02d:%02d:%02d".format(dur / 3600, dur / 60 % 60, dur % 60)
        if (an != null) out += an.stats()
        if (temp >= 45f) out += "⚠ Telefon qizib ketdi: soyaga qo'ying"
        return out
    }

    private fun startSession() {
        val det = detector
        if (det == null) { Toast.makeText(this, R.string.model_loading, Toast.LENGTH_SHORT).show(); return }
        sessionStart = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(sessionStart))
        val dir = File(getExternalFilesDir(null), "PCLOS_Kamera/${cfg.safeSite}_$stamp")
        sessionDir = dir
        val an = Analyzer(det, attr, cfg, setup, dir, "jonli kamera", cfg.fps)
        executor.execute { analyzer = an; running = true; an.writeInfo(null) }
        if (recordVideo) { segmentNo = 0; startVideo() }
        btnStart.setText(R.string.stop)
    }

    private fun stopSession() {
        executor.execute {
            running = false
            analyzer?.finish()
            analyzer = null
        }
        recording?.stop(); recording = null
        btnStart.setText(R.string.start)
        Toast.makeText(this, getString(R.string.saved_to, sessionDir?.name ?: ""), Toast.LENGTH_LONG).show()
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
        executor.execute { detector?.close(); attr?.close() }
        executor.shutdown()
        super.onDestroy()
    }
}
