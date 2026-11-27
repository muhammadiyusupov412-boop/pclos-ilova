package uz.pclos.kamera

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Oddiy kamera yoki telefonda yozilgan video faylni tahlil qilib sanash. */
class VideoActivity : AppCompatActivity() {
    private lateinit var cfg: RunConfig
    private lateinit var setup: SiteSetup
    private lateinit var image: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var progress: TextView
    private lateinit var btnRun: Button
    private val executor = Executors.newSingleThreadExecutor()

    private var uri: Uri? = null
    private var fileName = ""
    private var durationMs = 0L
    private var frameCount = 0
    private var videoFps = 25.0
    private var startCal: Calendar = Calendar.getInstance()
    @Volatile private var cancel = false
    @Volatile private var busy = false
    private var detector: Detector? = null
    private var attr: AttrClassifier? = null

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) openVideo(u) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cfg = RunConfig.from(intent)
        setup = SiteSetup(this, cfg.site, cfg.mode)
        image = findViewById(R.id.frame)
        overlay = findViewById(R.id.overlay)
        progress = findViewById(R.id.progress)
        btnRun = findViewById(R.id.btnRun)
        refreshShapes()

        findViewById<Button>(R.id.btnPick).setOnClickListener { if (!busy) picker.launch(arrayOf("video/*")) }
        findViewById<Button>(R.id.btnTime).setOnClickListener { if (!busy) pickTime() }
        findViewById<Button>(R.id.btnSetup).setOnClickListener {
            if (busy) return@setOnClickListener
            if (uri == null) { Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            setup.showMenu(it, overlay) { refreshShapes() }
        }
        btnRun.setOnClickListener {
            if (busy) { cancel = true; return@setOnClickListener }
            if (uri == null) { Toast.makeText(this, R.string.pick_first, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            runAnalysis()
        }
        executor.execute {
            try {
                detector = Detector(this).also { it.confThreshold = cfg.conf }
                attr = if (cfg.mode == RunConfig.MODE_VEH) null else AttrClassifier.createOrNull(this)
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Model yuklanmadi: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
        picker.launch(arrayOf("video/*"))
    }

    private fun refreshShapes() {
        overlay.lines = setup.visibleLines(); overlay.zones = setup.visibleZones(); overlay.invalidate()
    }

    private fun openVideo(u: Uri) {
        uri = u
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) fileName = it.getString(0) ?: ""
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(this, u)
            durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            frameCount = if (Build.VERSION.SDK_INT >= 28)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0 else 0
            videoFps = if (frameCount > 0 && durationMs > 0) frameCount * 1000.0 / durationMs else 25.0
            // yozilgan sana (bo'lsa), aks holda hozirgi vaqt
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { d ->
                listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyy MM dd").firstNotNullOfOrNull { p ->
                    try { SimpleDateFormat(p, Locale.US).apply { if (p.endsWith("'Z'")) timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d) } catch (_: Exception) { null }
                }
            }?.let { startCal.time = it }
            val first = r.getFrameAtTime(0)
            if (first != null) {
                image.setImageBitmap(first)
                overlay.frameW = first.width; overlay.frameH = first.height; overlay.invalidate()
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "Videoni ochib bo'lmadi: ${t.message}", Toast.LENGTH_LONG).show()
            uri = null
        } finally { r.release() }
        showInfo()
    }

    private fun showInfo() {
        if (uri == null) { progress.setText(R.string.pick_hint); return }
        progress.text = getString(R.string.video_info, fileName, durationMs / 60000, durationMs / 1000 % 60,
            String.format(Locale.US, "%.0f", videoFps), fmt.format(startCal.time))
    }

    private fun pickTime() {
        val c = startCal
        DatePickerDialog(this, { _, y, m, d ->
            c.set(y, m, d)
            TimePickerDialog(this, { _, hh, mm -> c.set(Calendar.HOUR_OF_DAY, hh); c.set(Calendar.MINUTE, mm); c.set(Calendar.SECOND, 0); showInfo() },
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun runAnalysis() {
        val det = detector
        if (det == null) { Toast.makeText(this, R.string.model_loading, Toast.LENGTH_SHORT).show(); return }
        val u = uri ?: return
        busy = true; cancel = false
        btnRun.setText(R.string.stop)
        val t0 = startCal.timeInMillis
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(t0))
        val dir = File(getExternalFilesDir(null), "PCLOS_Kamera/${cfg.safeSite}_${stamp}_video")
        val an = Analyzer(det, attr, cfg, setup, dir, "video fayl: $fileName", cfg.fps)
        executor.execute {
            val r = MediaMetadataRetriever()
            val wall0 = SystemClock.elapsedRealtime()
            var done = 0
            try {
                r.setDataSource(this, u)
                an.writeInfo(null)
                val byIndex = Build.VERSION.SDK_INT >= 28 && frameCount > 0
                val step = maxOf(1, Math.round(videoFps / cfg.fps).toInt())
                val stepMs = if (byIndex) step * 1000.0 / videoFps else 1000.0 / cfg.fps
                val total = if (byIndex) (frameCount + step - 1) / step else (durationMs / stepMs).toInt().coerceAtLeast(1)
                for (k in 0 until total) {
                    if (cancel) break
                    val posMs = k * stepMs
                    var bmp: Bitmap? = if (byIndex) {
                        val idx = (k * step).coerceAtMost(frameCount - 1)
                        try { r.getFrameAtIndex(idx) } catch (_: Throwable) { null }
                    } else null
                    if (bmp == null) bmp = r.getFrameAtTime((posMs * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)
                    if (bmp == null) continue
                    if (bmp.width > 1920) {
                        val s = 1920f / bmp.width
                        val sc = Bitmap.createScaledBitmap(bmp, 1920, (bmp.height * s).toInt(), true)
                        bmp.recycle(); bmp = sc
                    }
                    val items = an.process(bmp, t0 + posMs.toLong())
                    done++
                    val stats = an.stats()
                    val el = (SystemClock.elapsedRealtime() - wall0) / 1000.0
                    val videoSec = posMs / 1000.0
                    val speed = if (el > 0) videoSec / el else 0.0
                    val left = if (speed > 0) ((durationMs / 1000.0 - videoSec) / speed).toLong() else 0L
                    val shown = bmp
                    runOnUiThread {
                        image.setImageBitmap(shown)
                        overlay.frameW = shown.width; overlay.frameH = shown.height
                        overlay.items = items; overlay.stats = stats; overlay.invalidate()
                        progress.text = getString(R.string.video_progress, 100 * k / total,
                            String.format(Locale.US, "%.1f", speed), left / 60, left % 60)
                    }
                }
                an.finish()
                runOnUiThread {
                    progress.text = getString(if (cancel) R.string.video_cancelled else R.string.video_done, dir.name)
                    Toast.makeText(this, getString(R.string.saved_to, dir.name), Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                runCatching { an.finish() }
                runOnUiThread { progress.text = "Xato: ${t.message}" }
            } finally {
                r.release()
                busy = false
                runOnUiThread { btnRun.setText(R.string.analyze) }
            }
        }
    }

    override fun onDestroy() {
        cancel = true
        executor.execute { detector?.close(); attr?.close() }
        executor.shutdown()
        super.onDestroy()
    }
}
