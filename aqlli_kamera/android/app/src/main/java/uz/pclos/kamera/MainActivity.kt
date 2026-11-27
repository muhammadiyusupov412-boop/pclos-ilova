package uz.pclos.kamera

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var site: EditText
    private lateinit var interval: EditText
    private lateinit var video: CheckBox
    private lateinit var fps: Spinner
    private lateinit var conf: Spinner
    private lateinit var mode: Spinner
    private lateinit var sessions: LinearLayout

    private val fpsValues = intArrayOf(5, 10, 15)
    private val confValues = floatArrayOf(0.25f, 0.35f, 0.45f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        site = findViewById(R.id.site)
        interval = findViewById(R.id.interval)
        video = findViewById(R.id.video)
        fps = findViewById(R.id.fps)
        conf = findViewById(R.id.conf)
        mode = findViewById(R.id.mode)
        mode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Piyoda va transport (birga)", "Faqat piyodalar", "Faqat transport"))
        sessions = findViewById(R.id.sessions)
        fps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("5 kadr/s (tejamkor)", "10 kadr/s (tavsiya)", "15 kadr/s (aniq)"))
        fps.setSelection(1)
        conf.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("0.25 (ko'proq topadi)", "0.35 (tavsiya)", "0.45 (kamroq xato)"))
        conf.setSelection(1)
        val p = getPreferences(MODE_PRIVATE)
        site.setText(p.getString("site", ""))
        mode.setSelection(p.getInt("mode", 0))
        findViewById<Button>(R.id.btnGo).setOnClickListener { go() }
    }

    override fun onResume() {
        super.onResume()
        listSessions()
    }

    private fun go() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
            return
        }
        getPreferences(MODE_PRIVATE).edit().putString("site", site.text.toString())
            .putInt("mode", mode.selectedItemPosition).apply()
        startActivity(Intent(this, CountActivity::class.java).apply {
            putExtra(CountActivity.EXTRA_SITE, site.text.toString().trim())
            putExtra(CountActivity.EXTRA_INTERVAL, interval.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 15)
            putExtra(CountActivity.EXTRA_VIDEO, video.isChecked)
            putExtra(CountActivity.EXTRA_FPS, fpsValues[fps.selectedItemPosition])
            putExtra(CountActivity.EXTRA_CONF, confValues[conf.selectedItemPosition])
            putExtra(CountActivity.EXTRA_MODE, mode.selectedItemPosition)
        })
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) go()
        else Toast.makeText(this, R.string.need_camera, Toast.LENGTH_LONG).show()
    }

    private fun root() = File(getExternalFilesDir(null), "PCLOS_Kamera")

    private fun listSessions() {
        sessions.removeAllViews()
        val dirs = root().listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
        if (dirs.isEmpty()) {
            sessions.addView(TextView(this).apply { setText(R.string.no_sessions) })
            return
        }
        for (d in dirs) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
            val n = File(d, "hodisalar.csv").takeIf { it.exists() }?.useLines { it.count() - 1 } ?: 0
            row.addView(TextView(this).apply {
                text = "${d.name}\n$n ta hodisa"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Button(this).apply { setText(R.string.share); setOnClickListener { share(d) } })
            row.addView(Button(this).apply { setText(R.string.to_downloads); setOnClickListener { toDownloads(d) } })
            sessions.addView(row)
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
