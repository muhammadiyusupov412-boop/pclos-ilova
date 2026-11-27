package uz.pclos.kamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var site: EditText
    private lateinit var interval: EditText
    private lateinit var video: CheckBox
    private lateinit var fps: Spinner
    private lateinit var conf: Spinner
    private lateinit var mode: Spinner
    private lateinit var weather: Spinner
    private lateinit var light: Spinner
    private lateinit var control: Spinner
    private lateinit var lanes: EditText
    private lateinit var cwLen: EditText
    private lateinit var spDist: EditText
    private lateinit var note: EditText
    private lateinit var gps: CheckBox
    private lateinit var gpsText: TextView
    private lateinit var sessions: LinearLayout
    private var location: Location? = null
    private var pendingVideo = false

    private val fpsValues = intArrayOf(5, 10, 15)
    private val confValues = floatArrayOf(0.25f, 0.35f, 0.45f)
    private val weathers = listOf("quyoshli", "bulutli", "yomg'ir", "qor", "tuman")
    private val lights = listOf("kunduzi", "tong / shom", "kechasi (yoritilgan)", "kechasi (yoritilmagan)")
    private val controls = listOf("svetoforli", "chaqiruv tugmali svetofor", "svetoforsiz", "regulyovchi")

    private fun spinner(id: Int, items: List<String>) = findViewById<Spinner>(id).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        site = findViewById(R.id.site)
        interval = findViewById(R.id.interval)
        video = findViewById(R.id.video)
        fps = spinner(R.id.fps, listOf("5 kadr/s (tejamkor)", "10 kadr/s (tavsiya)", "15 kadr/s (aniq)"))
        conf = spinner(R.id.conf, listOf("0.25 (ko'proq topadi)", "0.35 (tavsiya)", "0.45 (kamroq xato)"))
        mode = spinner(R.id.mode, listOf("Piyoda va transport (birga)", "Faqat piyodalar", "Faqat transport"))
        weather = spinner(R.id.weather, weathers)
        light = spinner(R.id.light, lights)
        control = spinner(R.id.control, controls)
        lanes = findViewById(R.id.lanes)
        cwLen = findViewById(R.id.cwlen)
        spDist = findViewById(R.id.spdist)
        note = findViewById(R.id.note)
        gps = findViewById(R.id.gps)
        gpsText = findViewById(R.id.gpsText)
        sessions = findViewById(R.id.sessions)
        fps.setSelection(1); conf.setSelection(1)
        val p = getPreferences(MODE_PRIVATE)
        site.setText(p.getString("site", ""))
        mode.setSelection(p.getInt("mode", 0))
        control.setSelection(p.getInt("control", 0))
        lanes.setText(p.getString("lanes", ""))
        cwLen.setText(p.getString("cwlen", ""))
        spDist.setText(p.getString("spdist", ""))
        findViewById<Button>(R.id.btnGo).setOnClickListener { pendingVideo = false; go() }
        findViewById<Button>(R.id.btnVideo).setOnClickListener { pendingVideo = true; go() }
        gps.setOnCheckedChangeListener { _, on -> if (on) requestLocation() else { location = null; gpsText.text = "" } }
    }

    override fun onResume() {
        super.onResume()
        listSessions()
    }

    private fun num(e: EditText) = e.text.toString().replace(',', '.').toFloatOrNull()

    private fun config(): RunConfig {
        val meta = LinkedHashMap<String, String>()
        meta["ob_havo"] = weathers[weather.selectedItemPosition]
        meta["yoritilganlik"] = lights[light.selectedItemPosition]
        meta["boshqaruv_turi"] = controls[control.selectedItemPosition]
        meta["harakat_tasmalari_soni"] = lanes.text.toString().trim()
        location?.let {
            meta["gps_kenglik"] = String.format(Locale.US, "%.6f", it.latitude)
            meta["gps_uzunlik"] = String.format(Locale.US, "%.6f", it.longitude)
        }
        meta["izoh"] = note.text.toString().trim().replace('\n', ' ')
        return RunConfig(
            site = site.text.toString().trim(),
            intervalMin = interval.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 15,
            fps = fpsValues[fps.selectedItemPosition],
            conf = confValues[conf.selectedItemPosition],
            mode = mode.selectedItemPosition,
            recordVideo = video.isChecked,
            crosswalkLenM = num(cwLen),
            speedDistM = num(spDist),
            meta = meta,
        )
    }

    private fun go() {
        if (!pendingVideo && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        getPreferences(MODE_PRIVATE).edit().putString("site", site.text.toString())
            .putInt("mode", mode.selectedItemPosition).putInt("control", control.selectedItemPosition)
            .putString("lanes", lanes.text.toString()).putString("cwlen", cwLen.text.toString())
            .putString("spdist", spDist.text.toString()).apply()
        val target = if (pendingVideo) VideoActivity::class.java else CountActivity::class.java
        startActivity(Intent(this, target).also { config().put(it) })
    }

    private fun requestLocation() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.none { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 2); return
        }
        fetchLocation()
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        gpsText.setText(R.string.gps_wait)
        val best = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        best?.let { setLoc(it) }
        if (Build.VERSION.SDK_INT >= 30) {
            val prov = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            runCatching { lm.getCurrentLocation(prov, null, mainExecutor) { it?.let { l -> setLoc(l) } } }
        }
    }

    private fun setLoc(l: Location) {
        location = l
        gpsText.text = String.format(Locale.US, "%.6f, %.6f  (±%.0f m)", l.latitude, l.longitude, l.accuracy)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val ok = res.any { it == PackageManager.PERMISSION_GRANTED }
        when (code) {
            1 -> if (ok) go() else Toast.makeText(this, R.string.need_camera, Toast.LENGTH_LONG).show()
            2 -> if (ok) fetchLocation() else gps.isChecked = false
        }
    }

    private fun root() = File(getExternalFilesDir(null), "PCLOS_Kamera")

    private fun listSessions() {
        sessions.removeAllViews()
        val dirs = root().listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (dirs.isEmpty()) {
            sessions.addView(TextView(this).apply { setText(R.string.no_sessions) })
            return
        }
        for (d in dirs) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 10, 0, 10) }
            val n = File(d, "hodisalar.csv").takeIf { it.exists() }?.useLines { it.count() - 1 } ?: 0
            box.addView(TextView(this).apply { text = "${d.name}\n$n ta hodisa" })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(this).apply { setText(R.string.share); setOnClickListener { share(d) } })
            row.addView(Button(this).apply { setText(R.string.to_downloads); setOnClickListener { toDownloads(d) } })
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            sessions.addView(box)
        }
    }

    private fun zip(d: File): File {
        val out = File(cacheDir, "${d.name}.zip")
        ZipOutputStream(out.outputStream()).use { z ->
            d.listFiles()?.filter { it.isFile }?.forEach { f ->
                z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return out
    }

    private fun share(d: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", zip(d))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.share)))
    }

    /** Download/PCLOS_Kamera papkasiga ZIP sifatida saqlash (fayl menejerida ko'rinadi). */
    private fun toDownloads(d: File) {
        if (Build.VERSION.SDK_INT < 29) { share(d); return }
        val z = zip(d)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, z.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/PCLOS_Kamera")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) { Toast.makeText(this, "Saqlab bo'lmadi", Toast.LENGTH_LONG).show(); return }
        contentResolver.openOutputStream(uri)?.use { o -> z.inputStream().use { it.copyTo(o) } }
        Toast.makeText(this, getString(R.string.saved_downloads, z.name), Toast.LENGTH_LONG).show()
    }
}
