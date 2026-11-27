package uz.pclos.kamera

import android.graphics.Bitmap
import android.os.Build
import uz.pclos.kamera.core.AttrSource
import uz.pclos.kamera.core.Coco
import uz.pclos.kamera.core.CountingEngine
import uz.pclos.kamera.core.SessionLog
import uz.pclos.kamera.core.Summary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Kamera va video fayl uchun umumiy tahlil: aniqlash -> kuzatish -> sanash -> o'lchovlar -> fayllar. */
class Analyzer(
    private val detector: Detector,
    private val attr: AttrClassifier?,
    val cfg: RunConfig,
    private val setup: SiteSetup,
    val dir: File,
    private val source: String,
    private val processFps: Int,
) {
    val log = SessionLog(dir, cfg.intervalMin)
    val engine = CountingEngine(setup.countLines().map { it.copy() }, log,
        maxAgeFrames = maxOf(10, processFps * 3), study = setup.study(cfg))
    private var frame = 0
    var startMs = 0L; private set
    var lastMs = 0L; private set
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun filterDets(d: List<uz.pclos.kamera.core.Detection>) =
        if (cfg.mode == RunConfig.MODE_VEH) d.filter { it.cls != Coco.PERSON } else d

    /** Bitta kadrni qayta ishlaydi; ekranga chiziladigan qutilarni qaytaradi. */
    fun process(bmp: Bitmap, nowMs: Long): List<OverlayView.Item> {
        if (frame == 0) startMs = nowMs
        lastMs = nowMs
        frame++
        val dets = filterDets(detector.detect(bmp))
        val a = if (cfg.mode == RunConfig.MODE_VEH) null else attr
        engine.process(dets, frame, bmp.width, bmp.height, nowMs, a?.let { c -> AttrSource { _, box -> c.classify(bmp, box) } })
        return engine.tracker.tracks.filter { it.lastFrame == frame && engine.tracker.confirmed(it) }
            .filter { cfg.mode != RunConfig.MODE_PED || it.isPerson }
            .map { t ->
                val label = if (t.isPerson) {
                    if (t.isRider) "#${t.id} haydovchi"
                    else engine.attrResult(t.id)?.let { "#${t.id} ${it.first} ${if (it.second == "noaniq") "" else it.second}" } ?: "#${t.id} piyoda"
                } else "#${t.id} ${Coco.vehicleName(t.cls)}"
                OverlayView.Item(t.box, label, t.isPerson)
            }
    }

    fun stats(): List<String> {
        val out = ArrayList<String>()
        val t = log.totals
        if (cfg.mode != RunConfig.MODE_VEH) {
            val ped = SessionLog.AGE_GROUPS.map { t["piyoda:$it"] ?: 0 }
            out += "Piyoda: ${ped.sum()}  (bola ${ped[0]}, kattalar ${ped[1]}, keksa ${ped[2]}, ? ${ped[3]})"
            out += "  erkak ${t["jins:erkak"] ?: 0}, ayol ${t["jins:ayol"] ?: 0}"
        }
        if (cfg.mode != RunConfig.MODE_PED) {
            val veh = SessionLog.VEHICLES.associateWith { t["transport:$it"] ?: 0 }
            out += "Transport: ${veh.values.sum()}"
            out += "  yengil ${veh["yengil"]}, avtobus ${veh["avtobus"]}, yuk ${veh["yuk"]}, moto ${veh["mototsikl"]}, velo ${veh["velosiped"]}"
        }
        engine.metrics?.let { m ->
            val parts = ArrayList<String>()
            if (m.waits.isNotEmpty()) parts += "kutish %.1f s".format(Locale.US, m.waits.average())
            if (m.walkSpeeds.isNotEmpty()) parts += "piyoda %.2f m/s".format(Locale.US, m.walkSpeeds.average())
            if (m.vehSpeeds.isNotEmpty()) parts += "transport %.0f km/soat".format(Locale.US, m.vehSpeeds.average())
            if (m.pets.isNotEmpty()) parts += "xavfli holat ${m.pets.size}"
            if (parts.isNotEmpty()) out += parts.joinToString(", ")
        }
        return out
    }

    private var lastWrite = 0L
    fun periodicSave(nowWall: Long) {
        if (nowWall - lastWrite > 30_000) { log.writeIntervals(); engine.metrics?.writeIntervals(); lastWrite = nowWall }
    }

    fun meta(end: Long?): List<Pair<String, String>> = buildList {
        add("chorraha" to cfg.site)
        add("manba" to source)
        add("rejim" to cfg.modeName)
        if (startMs > 0) add("boshlandi" to ts.format(Date(startMs)))
        if (end != null) add("tugadi" to ts.format(Date(end)))
        add("interval_daqiqa" to cfg.intervalMin.toString())
        add("tahlil_kadr_s" to processFps.toString())
        add("ishonch_chegara" to cfg.conf.toString())
        cfg.crosswalkLenM?.let { add("otish_joyi_uzunligi_m" to it.toString()) }
        cfg.speedDistM?.let { add("tezlik_chiziqlari_masofasi_m" to it.toString()) }
        cfg.meta.forEach { (k, v) -> if (v.isNotBlank()) add(k to v) }
        add("yosh_jins_modeli" to if (attr != null) "PaddleClas PULC person_attribute (PA-100K)" else "yo'q")
        add("aniqlash_modeli" to "YOLO11n (COCO), 416 px")
        add("qurilma" to "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun writeInfo(end: Long?) {
        dir.mkdirs()
        val lines = meta(end).map { "${it.first}=${it.second}" } + setup.describe()
        File(dir, "malumot.txt").writeText(lines.joinToString("\n") + "\n")
    }

    /** Kuzatuv tugaganda: barcha fayllarni yozadi. */
    fun finish() {
        engine.flush()
        val end = lastMs
        writeInfo(end)
        val dur = if (startMs > 0) (end - startMs) / 60000.0 else 0.0
        Summary.write(dir, Summary.build(meta(end), log, engine.metrics, dur))
    }
}
