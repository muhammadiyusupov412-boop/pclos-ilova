package uz.pclos.kamera.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ko'pburchak zona (nisbiy koordinatalar 0..1). */
data class Zone(val name: String, var pts: List<Pt>, val kind: Kind) {
    enum class Kind { CROSSWALK, WAIT }

    val ready get() = pts.size >= 3

    fun contains(p: Pt): Boolean {
        if (!ready) return false
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y + 1e-9f) + a.x) inside = !inside
            j = i
        }
        return inside
    }
}

/** Tajriba o'lchovlari uchun sozlamalar. */
data class StudySetup(
    val crosswalk: Zone? = null,
    val waits: List<Zone> = emptyList(),
    /** Transport tezligi: ikki chiziq va ular orasidagi masofa (m). */
    val speedA: CountLine? = null,
    val speedB: CountLine? = null,
    val speedDistM: Float? = null,
    /** Piyodalar o'tish joyining uzunligi (m) - piyoda tezligi uchun. */
    val crosswalkLengthM: Float? = null,
    /** PET chegarasi, soniya: shundan kichik bo'lsa xavfli holat deb yoziladi. */
    val petThresholdS: Float = 3f,
)

fun percentile(xs: List<Double>, p: Double): Double? {
    if (xs.isEmpty()) return null
    val s = xs.sorted()
    val r = p / 100.0 * (s.size - 1)
    val lo = kotlin.math.floor(r).toInt(); val hi = kotlin.math.ceil(r).toInt()
    return s[lo] + (s[hi] - s[lo]) * (r - lo)
}

/**
 * Piyoda kutish vaqti, o'tish vaqti va tezligi, transport tezligi va piyoda-transport
 * to'qnashuv xavfi (PET - post-encroachment time) ni o'lchaydi.
 */
class StudyMetrics(
    private val setup: StudySetup,
    private val dir: File,
    private val intervalOf: (Long) -> Long,
    private val attrOf: (Int) -> Pair<String, String>? = { null },
) {
    private class PedState {
        var waitStart: Long? = null
        var lastWait: Long = 0
        var crossEnter: Long? = null
        var crossLast: Long = 0
        var waitS: Double = 0.0
        var done = false
    }

    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val peds = HashMap<Int, PedState>()
    private val vehInCross = HashMap<Int, Boolean>()
    private val pedInCross = HashSet<Int>()
    private val recentPedExits = ArrayList<Pair<Int, Long>>()
    private val recentVehExits = ArrayList<Triple<Int, Long, Int>>()  // trek, vaqt, sinf
    private val speedA = setup.speedA?.let { LineCounter(it) }
    private val speedB = setup.speedB?.let { LineCounter(it) }
    private val speedTimes = HashMap<Int, LongArray>()

    // natijalar
    val waits = ArrayList<Double>()
    val crossTimes = ArrayList<Double>()
    val walkSpeeds = ArrayList<Double>()
    val vehSpeeds = ArrayList<Double>()
    val pets = ArrayList<Double>()
    private val perInterval = java.util.TreeMap<Long, HashMap<String, MutableList<Double>>>()

    private val crossFile = File(dir, "piyoda_otish.csv")
    private val speedFile = File(dir, "transport_tezlik.csv")
    private val conflictFile = File(dir, "konfliktlar.csv")

    init {
        dir.mkdirs()
        if (setup.crosswalk?.ready == true) {
            if (!crossFile.exists()) crossFile.writeText("﻿vaqt,trek_id,yosh_guruhi,jins,kutish_s,otish_s,tezlik_m_s\n")
            if (!conflictFile.exists()) conflictFile.writeText("﻿vaqt,piyoda_trek,transport_trek,transport_turi,PET_s,tartib,xavf\n")
        }
        if (speedA != null && speedB != null && (setup.speedDistM ?: 0f) > 0f && !speedFile.exists()) {
            speedFile.writeText("﻿vaqt,trek_id,turi,tezlik_km_soat\n")
        }
    }

    private fun stat(t: Long, key: String, v: Double) {
        perInterval.getOrPut(intervalOf(t)) { HashMap() }.getOrPut(key) { ArrayList() }.add(v)
    }

    private fun f1(v: Double) = "%.2f".format(Locale.US, v)

    /** Kadrdagi tasdiqlangan treklar bilan yangilash (oyoq nuqtasi nisbiy koordinatada). */
    fun update(tracks: List<Track>, w: Int, h: Int, now: Long) {
        val cw = setup.crosswalk?.takeIf { it.ready }
        for (t in tracks) {
            if (t.isRider) continue
            val p = Pt(t.foot.x / w, t.foot.y / h)
            if (t.isPerson) {
                if (cw != null) updatePed(t.id, p, now, cw)
            } else {
                if (cw != null) updateVeh(t, p, now, cw)
                updateSpeed(t, p, now)
            }
        }
        recentPedExits.removeAll { now - it.second > setup.petThresholdS * 1000 }
        recentVehExits.removeAll { now - it.second > setup.petThresholdS * 1000 }
    }

    private fun updatePed(id: Int, p: Pt, now: Long, cw: Zone) {
        val s = peds.getOrPut(id) { PedState() }
        if (s.done) return
        val inWait = setup.waits.any { it.ready && it.contains(p) }
        val inCross = cw.contains(p)
        if (inWait && !inCross) {
            if (s.waitStart == null) s.waitStart = now
            s.lastWait = now
        } else if (!inCross && s.crossEnter == null && s.waitStart != null && now - s.lastWait > 1500) {
            s.waitStart = null  // kutish joyidan o'tmasdan ketdi
        }
        if (inCross) {
            if (s.crossEnter == null) {
                s.crossEnter = now
                s.waitS = s.waitStart?.let { (now - it) / 1000.0 } ?: 0.0
                pedInCross.add(id)
                // transport hozirgina o'tib ketgan bo'lsa: PET (piyoda transportdan keyin kirdi)
                recentVehExits.lastOrNull()?.let { (vid, tExit, cls) ->
                    conflict(id, vid, cls, (now - tExit) / 1000.0, "piyoda transportdan keyin", now)
                }
            }
            s.crossLast = now
        } else if (s.crossEnter != null && now - s.crossLast > 700) {
            finishCrossing(id, s)
        }
    }

    private fun finishCrossing(id: Int, s: PedState) {
        val enter = s.crossEnter ?: return
        s.done = true
        pedInCross.remove(id)
        recentPedExits.add(id to s.crossLast)
        val dur = (s.crossLast - enter) / 1000.0
        if (dur < 1.0) return  // zonani chetidan qisqa tegib o'tgan
        val (age, gender) = attrOf(id) ?: ("noaniq" to "noaniq")
        val L = setup.crosswalkLengthM
        val v = if (L != null && L > 0) L / dur else null
        waits += s.waitS; crossTimes += dur
        stat(enter, "kutish", s.waitS); stat(enter, "otish", dur)
        if (v != null && v in 0.2..4.0) { walkSpeeds += v; stat(enter, "tezlik", v) }
        crossFile.appendText(listOf(tsFmt.format(Date(enter)), id, age, gender, f1(s.waitS), f1(dur), v?.let { f1(it) } ?: "").joinToString(",") + "\n")
    }

    private fun updateVeh(t: Track, p: Pt, now: Long, cw: Zone) {
        val inCross = cw.contains(p)
        val was = vehInCross[t.id] ?: false
        if (inCross && !was) {
            if (pedInCross.isNotEmpty()) {
                conflict(pedInCross.first(), t.id, t.cls, 0.0, "bir vaqtda zonada", now)
            } else recentPedExits.lastOrNull()?.let { (pid, tExit) ->
                conflict(pid, t.id, t.cls, (now - tExit) / 1000.0, "transport piyodadan keyin", now)
            }
        }
        if (!inCross && was) recentVehExits.add(Triple(t.id, now, t.cls))
        vehInCross[t.id] = inCross
    }

    private fun conflict(pid: Int, vid: Int, cls: Int, pet: Double, order: String, now: Long) {
        if (pet > setup.petThresholdS) return
        pets += pet; stat(now, "pet", pet)
        val risk = when { pet < 1.0 -> "yuqori"; pet < 2.0 -> "o'rta"; else -> "past" }
        conflictFile.appendText(listOf(tsFmt.format(Date(now)), pid, vid, Coco.vehicleName(cls), f1(pet), order, risk).joinToString(",") + "\n")
    }

    private fun updateSpeed(t: Track, p: Pt, now: Long) {
        val a = speedA ?: return; val b = speedB ?: return
        val d = setup.speedDistM ?: return
        if (d <= 0f) return
        val arr = speedTimes.getOrPut(t.id) { longArrayOf(0, 0, 0) }
        if (a.update(t.id, p) != null) arr[0] = now
        if (b.update(t.id, p) != null) arr[1] = now
        if (arr[0] > 0 && arr[1] > 0 && arr[2] == 0L) {
            arr[2] = 1
            val dt = kotlin.math.abs(arr[1] - arr[0]) / 1000.0
            if (dt <= 0.05) return
            val kmh = d / dt * 3.6
            if (kmh !in 2.0..160.0) return
            vehSpeeds += kmh; stat(now, "vtezlik", kmh)
            speedFile.appendText(listOf(tsFmt.format(Date(now)), t.id, Coco.vehicleName(t.cls), "%.1f".format(Locale.US, kmh)).joinToString(",") + "\n")
        }
    }

    fun forget(id: Int) {
        peds[id]?.let { if (!it.done && it.crossEnter != null) finishCrossing(id, it) }
        peds.remove(id); vehInCross.remove(id); pedInCross.remove(id)
        speedA?.forget(id); speedB?.forget(id); speedTimes.remove(id)
    }

    fun flush() {
        for ((id, s) in peds) if (!s.done && s.crossEnter != null) finishCrossing(id, s)
        writeIntervals()
    }

    private fun avg(l: List<Double>?) = if (l.isNullOrEmpty()) "" else "%.2f".format(Locale.US, l.average())
    private fun pct(l: List<Double>?, p: Double) = l?.let { percentile(it, p) }?.let { "%.2f".format(Locale.US, it) } ?: ""

    fun writeIntervals() {
        if (perInterval.isEmpty()) return
        val sb = StringBuilder("﻿interval_boshi,kutish_ortacha_s,kutish_85%_s,otish_ortacha_s,piyoda_tezligi_ortacha_m_s,piyoda_tezligi_15%_m_s,transport_tezligi_ortacha_km_soat,transport_tezligi_85%_km_soat,konfliktlar_PET<3s,konfliktlar_PET<1s\n")
        for ((t, m) in perInterval) {
            sb.append(listOf(tsFmt.format(Date(t)), avg(m["kutish"]), pct(m["kutish"], 85.0), avg(m["otish"]),
                avg(m["tezlik"]), pct(m["tezlik"], 15.0), avg(m["vtezlik"]), pct(m["vtezlik"], 85.0),
                m["pet"]?.size ?: 0, m["pet"]?.count { it < 1.0 } ?: 0).joinToString(",")).append('\n')
        }
        File(dir, "korsatkichlar.csv").writeText(sb.toString())
    }

    /** Xulosa uchun asosiy qiymatlar. */
    fun summary(): List<Pair<String, String>> = listOf(
        "kuzatilgan_otishlar_soni" to crossTimes.size.toString(),
        "kutish_ortacha_s" to avg(waits),
        "kutish_85%_s" to pct(waits, 85.0),
        "kutish_maks_s" to (waits.maxOrNull()?.let { "%.1f".format(Locale.US, it) } ?: ""),
        "otish_vaqti_ortacha_s" to avg(crossTimes),
        "piyoda_tezligi_ortacha_m_s" to avg(walkSpeeds),
        "piyoda_tezligi_15%_m_s (HCM loyiha tezligi)" to pct(walkSpeeds, 15.0),
        "transport_tezligi_ortacha_km_soat" to avg(vehSpeeds),
        "transport_tezligi_85%_km_soat (v85)" to pct(vehSpeeds, 85.0),
        "konfliktlar_PET<3s" to pets.size.toString(),
        "konfliktlar_PET<2s" to pets.count { it < 2.0 }.toString(),
        "konfliktlar_PET<1s (yuqori xavf)" to pets.count { it < 1.0 }.toString(),
    )
}
