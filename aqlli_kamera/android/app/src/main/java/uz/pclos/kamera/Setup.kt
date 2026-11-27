package uz.pclos.kamera

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import uz.pclos.kamera.core.CountLine
import uz.pclos.kamera.core.Pt
import uz.pclos.kamera.core.StudySetup
import uz.pclos.kamera.core.Zone

/** Kuzatuv sozlamalari (bosh sahifadan kamera/video oynasiga uzatiladi). */
data class RunConfig(
    val site: String,
    val intervalMin: Int,
    val fps: Int,
    val conf: Float,
    val mode: Int,
    val recordVideo: Boolean,
    val crosswalkLenM: Float?,
    val speedDistM: Float?,
    val meta: LinkedHashMap<String, String>,
) {
    companion object {
        const val MODE_BOTH = 0
        const val MODE_PED = 1
        const val MODE_VEH = 2

        fun from(i: Intent) = RunConfig(
            site = i.getStringExtra("site")?.ifBlank { null } ?: "chorraha",
            intervalMin = i.getIntExtra("interval", 15),
            fps = i.getIntExtra("fps", 10),
            conf = i.getFloatExtra("conf", 0.35f),
            mode = i.getIntExtra("mode", MODE_BOTH),
            recordVideo = i.getBooleanExtra("video", false),
            crosswalkLenM = i.getFloatExtra("cwlen", 0f).takeIf { it > 0f },
            speedDistM = i.getFloatExtra("spdist", 0f).takeIf { it > 0f },
            meta = LinkedHashMap<String, String>().apply {
                val keys = i.getStringArrayExtra("metaKeys") ?: emptyArray()
                val vals = i.getStringArrayExtra("metaVals") ?: emptyArray()
                keys.indices.forEach { put(keys[it], vals.getOrElse(it) { "" }) }
            },
        )
    }

    fun put(i: Intent) {
        i.putExtra("site", site); i.putExtra("interval", intervalMin); i.putExtra("fps", fps)
        i.putExtra("conf", conf); i.putExtra("mode", mode); i.putExtra("video", recordVideo)
        i.putExtra("cwlen", crosswalkLenM ?: 0f); i.putExtra("spdist", speedDistM ?: 0f)
        i.putExtra("metaKeys", meta.keys.toTypedArray()); i.putExtra("metaVals", meta.values.toTypedArray())
    }

    val modeName get() = when (mode) { MODE_PED -> "faqat piyoda"; MODE_VEH -> "faqat transport"; else -> "piyoda va transport" }
    val safeSite get() = site.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
}

/** Chorraha bo'yicha chiziq va zonalar (eslab qolinadi). */
class SiteSetup(private val ctx: Context, private val site: String, private val mode: Int) {
    val pedLine = CountLine("1-chiziq (piyoda)", Pt(0.1f, 0.6f), Pt(0.9f, 0.6f), CountLine.Target.PEDESTRIAN)
    val vehLine = CountLine("2-chiziq (transport)", Pt(0.45f, 0.1f), Pt(0.45f, 0.9f), CountLine.Target.VEHICLE)
    val speedLine = CountLine("3-chiziq (tezlik)", Pt(0f, 0f), Pt(0f, 0f), CountLine.Target.VEHICLE)
    val crosswalk = Zone("O'tish joyi", emptyList(), Zone.Kind.CROSSWALK)
    val wait1 = Zone("Kutish 1", emptyList(), Zone.Kind.WAIT)
    val wait2 = Zone("Kutish 2", emptyList(), Zone.Kind.WAIT)
    private val allLines = listOf(pedLine, vehLine, speedLine)
    private val allZones = listOf(crosswalk, wait1, wait2)

    private val prefs = ctx.getSharedPreferences("setup_$site", Context.MODE_PRIVATE)

    init { load() }

    private fun speedLineSet() = speedLine.a != speedLine.b

    /** Ekranda ko'rsatiladigan va sanashda ishlatiladigan chiziqlar. */
    fun visibleLines(): List<CountLine> = buildList {
        if (mode != RunConfig.MODE_VEH) add(pedLine)
        if (mode != RunConfig.MODE_PED) { add(vehLine); if (speedLineSet()) add(speedLine) }
    }

    fun countLines(): List<CountLine> = visibleLines().filter { it !== speedLine }

    fun visibleZones(): List<Zone> = when (mode) {
        RunConfig.MODE_VEH -> emptyList()
        else -> allZones.filter { it.pts.isNotEmpty() }
    }

    fun study(cfg: RunConfig) = StudySetup(
        crosswalk = crosswalk.takeIf { it.ready && mode != RunConfig.MODE_VEH },
        waits = listOf(wait1, wait2).filter { it.ready && mode != RunConfig.MODE_VEH },
        speedA = vehLine.takeIf { mode != RunConfig.MODE_PED && speedLineSet() },
        speedB = speedLine.takeIf { mode != RunConfig.MODE_PED && speedLineSet() },
        speedDistM = cfg.speedDistM,
        crosswalkLengthM = cfg.crosswalkLenM,
    )

    private fun load() {
        allLines.forEachIndexed { i, l ->
            if (prefs.contains("l${i}ax")) {
                l.a = Pt(prefs.getFloat("l${i}ax", 0f), prefs.getFloat("l${i}ay", 0f))
                l.b = Pt(prefs.getFloat("l${i}bx", 0f), prefs.getFloat("l${i}by", 0f))
            }
        }
        allZones.forEachIndexed { i, z ->
            val s = prefs.getString("z$i", null) ?: return@forEachIndexed
            z.pts = s.split(";").filter { it.isNotBlank() }.map { p -> p.split(",").let { Pt(it[0].toFloat(), it[1].toFloat()) } }
        }
    }

    fun save() {
        val e = prefs.edit()
        allLines.forEachIndexed { i, l ->
            e.putFloat("l${i}ax", l.a.x).putFloat("l${i}ay", l.a.y).putFloat("l${i}bx", l.b.x).putFloat("l${i}by", l.b.y)
        }
        allZones.forEachIndexed { i, z -> e.putString("z$i", z.pts.joinToString(";") { "${it.x},${it.y}" }) }
        e.apply()
    }

    fun describe(): List<String> = buildList {
        visibleLines().forEach { add("chiziq=${it.name};%.3f,%.3f;%.3f,%.3f".format(java.util.Locale.US, it.a.x, it.a.y, it.b.x, it.b.y)) }
        visibleZones().forEach { z -> add("zona=${z.name};" + z.pts.joinToString(";") { "%.3f,%.3f".format(java.util.Locale.US, it.x, it.y) }) }
    }

    /** "Sozlash" menyusi: chiziq va zonalarni chizish. */
    fun showMenu(anchor: View, overlay: OverlayView, onChange: () -> Unit) {
        val m = PopupMenu(ctx, anchor)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (mode != RunConfig.MODE_VEH) items += "1-chiziq: piyodalarni sanash" to { overlay.startLine(pedLine) }
        if (mode != RunConfig.MODE_PED) {
            items += "2-chiziq: transportni sanash" to { overlay.startLine(vehLine) }
            items += "3-chiziq: tezlik uchun (2-chiziqdan ma'lum masofada)" to {
                if (!speedLineSet()) { speedLine.a = Pt(0.7f, 0.1f); speedLine.b = Pt(0.7f, 0.9f) }
                overlay.lines = visibleLines(); overlay.startLine(speedLine)
            }
        }
        if (mode != RunConfig.MODE_VEH) {
            items += "O'tish joyi zonasi (4 burchak)" to { overlay.zones = allZones; overlay.startZone(crosswalk) }
            items += "Kutish joyi 1 (4 burchak)" to { overlay.zones = allZones; overlay.startZone(wait1) }
            items += "Kutish joyi 2 (4 burchak)" to { overlay.zones = allZones; overlay.startZone(wait2) }
        }
        items += "Zonalar va 3-chiziqni o'chirish" to {
            allZones.forEach { it.pts = emptyList() }; speedLine.a = Pt(0f, 0f); speedLine.b = Pt(0f, 0f)
            save(); onChange()
        }
        items.forEachIndexed { i, (t, _) -> m.menu.add(0, i, i, t) }
        m.setOnMenuItemClickListener { items[it.itemId].second(); true }
        overlay.onSetupChanged = { save(); onChange() }
        m.show()
    }
}
