package uz.pclos.kamera.core

import java.io.File
import java.util.Locale

/** Kuzatuv xulosasi: bitta faylda tajriba uchun barcha asosiy ko'rsatkichlar (parametr, qiymat). */
object Summary {
    fun build(meta: List<Pair<String, String>>, log: SessionLog, metrics: StudyMetrics?, durationMin: Double): List<Pair<String, String>> {
        val t = log.totals
        val out = ArrayList<Pair<String, String>>()
        out += meta
        out += "davomiylik_daqiqa" to "%.1f".format(Locale.US, durationMin)
        val ped = SessionLog.AGE_GROUPS.associateWith { t["piyoda:$it"] ?: 0 }
        val pedTotal = ped.values.sum()
        out += "piyoda_jami" to pedTotal.toString()
        for ((k, v) in ped) out += "piyoda_$k" to v.toString()
        for (g in listOf("erkak", "ayol", "noaniq")) out += "jins_$g" to (t["jins:$g"] ?: 0).toString()
        for (a in SessionLog.AGE_GROUPS.take(3)) for (g in SessionLog.GENDERS) out += "${a}_$g" to countAgeGender(log, a, g).toString()
        val hours = durationMin / 60.0
        fun perHour(n: Number) = if (hours > 0) "%.0f".format(Locale.US, n.toDouble() / hours) else ""
        out += "piyoda_soatlik_oqim" to perHour(pedTotal)
        out += "piyoda_eng_yuqori_${log.intervalMinutes}daq (HCM n15)" to log.peakInterval("piyoda").toString()
        val veh = SessionLog.VEHICLES.associateWith { t["transport:$it"] ?: 0 }
        val vehTotal = veh.values.sum()
        out += "transport_jami" to vehTotal.toString()
        for ((k, v) in veh) out += "transport_$k" to v.toString()
        val pcu = log.pcuTotal()
        out += "keltirilgan_birlik_jami" to "%.1f".format(Locale.US, pcu)
        out += "transport_soatlik_jadallik_avt_soat" to perHour(vehTotal)
        out += "transport_soatlik_jadallik_kb_soat (HCM oqim)" to perHour(pcu)
        out += "transport_eng_yuqori_${log.intervalMinutes}daq" to log.peakInterval("transport").toString()
        val peak = log.peakInterval("transport")
        if (peak > 0 && vehTotal > 0 && hours >= 1.0) {
            // soatlik notekislik koeffitsienti (PHF) = soatlik oqim / (4 x eng yuqori 15 daqiqa)
            val phf = (vehTotal / hours) / (peak * (60.0 / log.intervalMinutes))
            out += "PHF (soatlik notekislik)" to "%.2f".format(Locale.US, phf)
        }
        metrics?.let { out += it.summary() }
        return out
    }

    private fun countAgeGender(log: SessionLog, age: String, gender: String) = log.ageGender["$age:$gender"] ?: 0

    fun write(dir: File, rows: List<Pair<String, String>>) {
        val sb = StringBuilder("﻿parametr,qiymat\n")
        for ((k, v) in rows) sb.append(csv(k)).append(',').append(csv(v)).append('\n')
        File(dir, "xulosa.csv").writeText(sb.toString())
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
