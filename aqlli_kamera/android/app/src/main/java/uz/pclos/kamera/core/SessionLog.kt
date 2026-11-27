package uz.pclos.kamera.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bitta sanash hodisasi (chiziqdan o'tish). */
data class CountEvent(
    val timeMs: Long,
    val trackId: Int,
    val obj: String,        // "piyoda" | "transport"
    val category: String,   // piyoda: bola/kattalar/keksa/noaniq; transport: yengil/avtobus/...
    val gender: String,     // piyoda: erkak/ayol/noaniq; transport: ""
    val confidence: Float,
    val line: String,
    val direction: String,
)

/**
 * Hodisalarni jurnalga yozadi va intervallar (standart 15 daqiqa) bo'yicha jamlaydi.
 * Fayllar: hodisalar.csv (har hodisa), intervallar.csv (har yangilanishda qayta yoziladi).
 */
class SessionLog(
    val dir: File,
    private val intervalMin: Int = 15,
    private val pcu: Map<String, Float> = DEFAULT_PCU,
) {
    companion object {
        val AGE_GROUPS = listOf("bola", "kattalar", "keksa", "noaniq")
        val VEHICLES = listOf("yengil", "avtobus", "yuk", "mototsikl", "velosiped")
        /** Yengil avtomobilga keltirish koeffitsientlari (o'z me'yoringizga moslang). */
        val DEFAULT_PCU = mapOf(
            "yengil" to 1.0f, "mototsikl" to 0.5f, "velosiped" to 0.3f,
            "avtobus" to 2.5f, "yuk" to 2.0f,
        )
        private const val BOM = "﻿"
    }

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val eventsFile = File(dir, "hodisalar.csv")
    private val intervalsFile = File(dir, "intervallar.csv")
    // (intervalBoshi, chiziq) -> (toifa -> soni)
    private val buckets = java.util.TreeMap<Pair<Long, String>, HashMap<String, Int>>(
        compareBy<Pair<Long, String>>({ it.first }, { it.second }))
    val totals = HashMap<String, Int>()

    init {
        dir.mkdirs()
        if (!eventsFile.exists()) {
            eventsFile.writeText(BOM + "vaqt,trek_id,obyekt,toifa,jins,ishonch,chiziq,yonalish\n")
        }
    }

    fun intervalStart(t: Long): Long {
        val ms = intervalMin * 60_000L
        // mahalliy vaqt bo'yicha yaxlitlash (15:00, 15:15, ...)
        val off = java.util.TimeZone.getDefault().getOffset(t)
        return ((t + off) / ms) * ms - off
    }

    @Synchronized
    fun add(e: CountEvent) {
        eventsFile.appendText(listOf(
            tsFmt.format(Date(e.timeMs)), e.trackId, e.obj, e.category, e.gender,
            "%.2f".format(Locale.US, e.confidence), e.line, e.direction,
        ).joinToString(",") + "\n")
        val key = intervalStart(e.timeMs) to e.line
        val m = buckets.getOrPut(key) { HashMap() }
        val k = "${e.obj}:${e.category}"
        m[k] = (m[k] ?: 0) + 1
        if (e.obj == "piyoda" && e.gender.isNotEmpty()) m["jins:${e.gender}"] = (m["jins:${e.gender}"] ?: 0) + 1
        totals[k] = (totals[k] ?: 0) + 1
        if (e.obj == "piyoda" && e.gender.isNotEmpty()) totals["jins:${e.gender}"] = (totals["jins:${e.gender}"] ?: 0) + 1
    }

    @Synchronized
    fun writeIntervals() {
        val sb = StringBuilder(BOM)
        val head = mutableListOf("interval_boshi", "interval_oxiri", "chiziq")
        AGE_GROUPS.forEach { head += "piyoda_$it" }
        head += listOf("piyoda_jami", "erkak", "ayol")
        AGE_GROUPS.take(3).forEach { head += "${it}_%" }
        VEHICLES.forEach { head += it }
        head += listOf("transport_jami", "keltirilgan_birlik", "soatlik_jadallik_avt", "soatlik_jadallik_kb")
        VEHICLES.forEach { head += "${it}_%" }
        sb.append(head.joinToString(",")).append('\n')
        val hours = intervalMin / 60.0
        for ((key, m) in buckets) {
            val (start, line) = key
            val row = mutableListOf<Any>(tsFmt.format(Date(start)), tsFmt.format(Date(start + intervalMin * 60_000L)), line)
            val ped = AGE_GROUPS.map { m["piyoda:$it"] ?: 0 }
            val pedTotal = ped.sum()
            row.addAll(ped); row += pedTotal
            row += (m["jins:erkak"] ?: 0); row += (m["jins:ayol"] ?: 0)
            ped.take(3).forEach { row += pct(it, pedTotal) }
            val veh = VEHICLES.map { m["transport:$it"] ?: 0 }
            val vehTotal = veh.sum()
            val pcuSum = VEHICLES.indices.sumOf { (veh[it] * (pcu[VEHICLES[it]] ?: 1f)).toDouble() }
            row.addAll(veh); row += vehTotal
            row += "%.1f".format(Locale.US, pcuSum)
            row += "%.0f".format(Locale.US, vehTotal / hours)
            row += "%.0f".format(Locale.US, pcuSum / hours)
            veh.forEach { row += pct(it, vehTotal) }
            sb.append(row.joinToString(",")).append('\n')
        }
        intervalsFile.writeText(sb.toString())
    }

    private fun pct(x: Int, total: Int) = if (total == 0) "0.0" else "%.1f".format(Locale.US, 100.0 * x / total)
}
